package edu.illinois.cs.cs125.jeed.core

import mu.KotlinLogging
import java.io.*
import java.lang.module.ModuleFinder
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.security.AccessController
import java.security.PrivilegedAction
import java.util.*
import javax.lang.model.element.Modifier
import javax.lang.model.element.NestingKind
import javax.tools.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

private val systemCompiler = ToolProvider.getSystemJavaCompiler() ?: error("systemCompiler not found: you are probably running a JRE, not a JDK")

data class CompilationArguments(
        val wError: Boolean = DEFAULT_WERROR,
        val Xlint: String = DEFAULT_XLINT,
        @Transient val parentClassLoader: ClassLoader? = null
) {
    companion object {
        const val DEFAULT_WERROR = false
        const val DEFAULT_XLINT = "all"
    }
}

class CompiledSource(val source: Source, val messages: List<CompilationMessage>, val classLoader: JeedClassLoader)

class CompilationFailed(errors: List<CompilationError>) : JeepError(errors)

@Throws(CompilationFailed::class)
private fun compile(
        source: Source,
        compilationArguments: CompilationArguments = CompilationArguments(),
        parentClassLoader: ClassLoader?
): CompiledSource {
    val units = source.sources.entries.map { Unit(it) }
    val results = Results()
    val fileManager = FileManager(results)

    val options = mutableSetOf<String>()
    options.add("-Xlint:${compilationArguments.Xlint}")

    systemCompiler.getTask(null, fileManager, results, options.toList(), null, units).call()

    fileManager.close()

    val errors = results.diagnostics.filter {
        it.kind == Diagnostic.Kind.ERROR || (it.kind == Diagnostic.Kind.WARNING && compilationArguments.wError)
    }.map {
        val originalLocation = SourceLocation(it.source.name, it.lineNumber.toInt(), it.columnNumber.toInt())
        val remappedLocation = source.mapLocation(originalLocation)
        CompilationError(remappedLocation, it.getMessage(Locale.US))
    }
    if (errors.isNotEmpty()) {
        throw CompilationFailed(errors)
    }

    val messages = results.diagnostics.map {
        val originalLocation = SourceLocation(it.source.name, it.lineNumber.toInt(), it.columnNumber.toInt())
        val remappedLocation = source.mapLocation(originalLocation)
        CompilationMessage(it.kind.toString(), remappedLocation, it.getMessage(Locale.US))
    }
    val classLoader = AccessController.doPrivileged(PrivilegedAction<JeedClassLoader> {
        JeedClassLoader(fileManager, parentClassLoader ?: ClassLoader.getSystemClassLoader())
    })

    return CompiledSource(source, messages, classLoader)
}

fun Source.compile(
        compilationArguments: CompilationArguments = CompilationArguments()
): CompiledSource {
    return compile(this, compilationArguments, compilationArguments.parentClassLoader)
}

fun Source.compileWith(
        compiledSource: CompiledSource, compilationArguments: CompilationArguments = CompilationArguments()
): CompiledSource {
    require(compilationArguments.parentClassLoader == null) { "compileWith overrides parentClassLoader compilation argument"}
    return compile(this, compilationArguments, compiledSource.classLoader)
}
private class Unit(val entry: Map.Entry<String, String>) : SimpleJavaFileObject(URI(entry.key), JavaFileObject.Kind.SOURCE) {
    override fun isNameCompatible(simpleName: String?, kind: JavaFileObject.Kind?): Boolean {
        return true
    }
    override fun openReader(ignoreEncodingErrors: Boolean): Reader {
        return StringReader(entry.value)
    }
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence {
        return entry.value
    }
    override fun toString(): String {
        return entry.key
    }
}

class Results : DiagnosticListener<JavaFileObject> {
    val diagnostics = mutableListOf<Diagnostic<out JavaFileObject>>()
    override fun report(diagnostic: Diagnostic<out JavaFileObject>) {
        diagnostics.add(diagnostic)
    }
}

class FileManager(results: Results) : ForwardingJavaFileManager<JavaFileManager>(
        ToolProvider.getSystemJavaCompiler().getStandardFileManager(results, Locale.US, Charset.forName("UTF-8"))
) {
    private class ByteSource(name: String, kind: JavaFileObject.Kind) : SimpleJavaFileObject(
            URI.create("bytearray:///${name.replace('.', '/')}${kind.extension}"),
            JavaFileObject.Kind.CLASS
    ) {
        private val buffer = ByteArrayOutputStream()

        override fun openInputStream(): InputStream {
            return ByteArrayInputStream(buffer.toByteArray())
        }
        override fun openOutputStream(): OutputStream {
            return buffer
        }
    }

    private val classFiles: MutableMap<String, JavaFileObject> = mutableMapOf()
    override fun getJavaFileForOutput(location: JavaFileManager.Location?, className: String, kind: JavaFileObject.Kind?, sibling: FileObject?): JavaFileObject {
        return when {
            location != StandardLocation.CLASS_OUTPUT -> super.getJavaFileForOutput(location, className, kind, sibling)
            kind != JavaFileObject.Kind.CLASS -> throw UnsupportedOperationException()
            else -> {
                val simpleJavaFileObject = ByteSource(className, kind)
                classFiles[className] = simpleJavaFileObject
                return simpleJavaFileObject
            }
        }
    }
    override fun getJavaFileForInput(location: JavaFileManager.Location?, className: String, kind: JavaFileObject.Kind): JavaFileObject? {
        return if (location != StandardLocation.CLASS_OUTPUT) {
            super.getJavaFileForInput(location, className, kind)
        } else {
            classFiles[className]
        }
    }
    override fun list(location: JavaFileManager.Location?, packageName: String, kinds: MutableSet<JavaFileObject.Kind>, recurse: Boolean): MutableIterable<JavaFileObject> {
        return if (!kinds.contains(JavaFileObject.Kind.CLASS)) {
            super.list(location, packageName, kinds, recurse)
        } else {
            getSubresources(
                    if (packageName.isEmpty()) "" else packageName.replace('.', '/') + '/',
                    recurse
            )
        }
    }
    override fun inferBinaryName(location: JavaFileManager.Location?, file: JavaFileObject): String {
        return file.name.substring(0, file.name.lastIndexOf('.')).replace('/', '.')
    }
}

class JeedClassLoader(
        val fileManager: FileManager,
        parentClassLoader: ClassLoader
): ClassLoader(parentClassLoader) {

    private val loadedByteCode: MutableMap<String, ByteArray> = mutableMapOf()
    fun bytecodeForClass(name: String): ByteArray {
        return loadedByteCode.getOrElse(name) {
            findClass(name)
            loadedByteCode[name] ?: error("should have loaded byte code")
        }
    }
    val loadedClasses: List<String>
        get() {
            return loadedByteCode.keys.toList()
        }

    override fun findClass(name: String): Class<*> {
        @Suppress("UNREACHABLE_CODE")
        return try {
            val classFile = fileManager.getJavaFileForInput(
                    StandardLocation.CLASS_OUTPUT,
                    name,
                    JavaFileObject.Kind.CLASS
            )
            val byteArray = classFile!!.openInputStream().readAllBytes()
            loadedByteCode[name] = byteArray
            return defineClass(name, byteArray, 0, byteArray.size)
        } catch (e: Exception) {
            throw ClassNotFoundException(name)
        }
    }
}

class CompilationMessage(
        val kind: String,
        location: SourceLocation,
        message: String
) : SourceError(location, message)
class CompilationError(
        location: SourceLocation,
        message: String
) : SourceError(location, message)

private class Listing(val entry: Map.Entry<String, URL>) : JavaFileObject {
    override fun toUri(): URI {
        return entry.value.toURI()
    }
    override fun getName(): String {
        return entry.key
    }
    override fun openInputStream(): InputStream {
        return entry.value.openStream()
    }
    override fun getKind(): JavaFileObject.Kind {
        return JavaFileObject.Kind.CLASS
    }
    override fun toString(): String {
        return "${entry.key} from ${this.javaClass.simpleName}"
    }

    override fun openOutputStream(): OutputStream { throw UnsupportedOperationException() }
    override fun openReader(ignoreEncodingErrors: Boolean): Reader { throw UnsupportedOperationException() }
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence { throw UnsupportedOperationException() }
    override fun openWriter(): Writer { throw UnsupportedOperationException() }
    override fun getLastModified(): Long { throw UnsupportedOperationException() }
    override fun delete(): Boolean { throw UnsupportedOperationException() }
    override fun isNameCompatible(simpleName: String?, kind: JavaFileObject.Kind?): Boolean { throw UnsupportedOperationException() }
    override fun getNestingKind(): NestingKind { throw UnsupportedOperationException() }
    override fun getAccessLevel(): Modifier { throw UnsupportedOperationException() }
}

val getBootClasspathSubresourcesOf = run {
    val resource = ClassLoader.getSystemClassLoader().getResource("java/lang/Object.class")
    val protocol = resource!!.protocol
    assert(protocol.equals("jrt", true))
    val bootClassPathSubresources = ModuleFinder.ofSystem().findAll()

    fun(name: String, recurse: Boolean): Map<String, URL> {
        val results = mutableMapOf<String, URL>()
        bootClassPathSubresources.forEach { moduleReference ->
            moduleReference.open().list().filter { resourceName ->
                resourceName != "module-info.class"
            }.filter { resourceName ->
                resourceName.startsWith(name) && (recurse || resourceName.lastIndexOf('/') == name.length - 1)
            }.forEach {resourceName ->
                assert(!results.containsKey(resourceName))
                results[resourceName] = URL("${moduleReference.location().get()}/$resourceName")
            }
        }
        return results
    }
}

fun getSubresources(name: String, recurse: Boolean): MutableIterable<JavaFileObject> {
    val results = mutableMapOf<String, URL>()
    ClassLoader.getSystemClassLoader().getResources(name).toList().forEach { url ->
        assert(url.protocol.toLowerCase() in listOf("jar", "file"))
        if (url.protocol.equals("jar", true)) {
            val jarURLConnection = url.openConnection() as JarURLConnection
            jarURLConnection.useCaches = false
            if (jarURLConnection.jarEntry.isDirectory) {
                results[name] = url
            } else {
                results.putAll(jarURLConnection.jarFile.entries().toList().filter { jarEntry ->
                    !jarEntry.isDirectory && jarEntry.name.startsWith(name) && (recurse || jarEntry.name.indexOf('/', name.length) == -1)
                }.map {jarEntry ->
                    jarEntry.name to URL("jar", null, "$url!/${ jarEntry.name })")
                }.toMap())
            }
        } else if (url.protocol.equals("file", true)) {
            val file = File(url.file)
            if (file.isFile) {
                results[name] = url
            } else if (file.isDirectory) {
                val namePrefix = if (name.isNotEmpty() && !name.endsWith('/')) {
                    "$name/"
                } else {
                    name
                }
                results.putAll(getFileResources(file, namePrefix, recurse))
            }
        }
    }

    if (results.isEmpty()) {
        results.putAll(getBootClasspathSubresourcesOf(name, recurse))
    }
    return results.filter {
        it.key.endsWith(".class")
    }.map {
        Listing(it)
    }.toMutableList()
}

fun getFileResources(file: File, name: String, recurse: Boolean): Map<String, URL> {
    val results = mutableMapOf<String, URL>()
    file.listFiles()?.forEach {
        val fileName = "$name${it.name}"
        if (recurse) {
            results.putAll(getFileResources(it, fileName, recurse))
        } else if (file.isFile) {
            results[fileName] = file.toURI().toURL()
        }
    }
    return results
}
