package edu.illinois.cs.cs125.jeed.core

import mu.KotlinLogging
import java.io.*
import java.net.URI
import java.nio.charset.Charset
import java.security.AccessController
import java.security.PrivilegedAction
import java.util.*
import javax.tools.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

private val systemCompiler = ToolProvider.getSystemJavaCompiler() ?: error("systemCompiler not found: you are probably running a JRE, not a JDK")

data class CompilationArguments(
        val wError: Boolean = DEFAULT_WERROR,
        val Xlint: String = DEFAULT_XLINT,
        @Transient val parentFileManager: JavaFileManager? = null,
        @Transient val parentClassLoader: ClassLoader? = null
) {
    companion object {
        const val DEFAULT_WERROR = false
        const val DEFAULT_XLINT = "all"
    }
}
class CompilationFailed(errors: List<CompilationError>) : JeepError(errors)
class CompiledSource(val source: Source, val messages: List<CompilationMessage>, val classLoader: JeedClassLoader, val fileManager: JeedFileManager)

@Throws(CompilationFailed::class)
private fun doCompile(
        source: Source,
        compilationArguments: CompilationArguments = CompilationArguments(),
        parentFileManager: JavaFileManager? = compilationArguments.parentFileManager,
        parentClassLoader: ClassLoader? = compilationArguments.parentClassLoader ?: ClassLoader.getSystemClassLoader()
): CompiledSource {
    val units = source.sources.entries.map { Unit(it) }
    val results = Results()
    val fileManager = JeedFileManager(parentFileManager
            ?: ToolProvider.getSystemJavaCompiler().getStandardFileManager(results, Locale.US, Charset.forName("UTF-8"))
    )

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
        JeedClassLoader(fileManager, parentClassLoader)
    })

    return CompiledSource(source, messages, classLoader, fileManager)
}

fun Source.compile(
        compilationArguments: CompilationArguments = CompilationArguments()
): CompiledSource {
    return doCompile(this, compilationArguments)
}
fun Source.compileWith(
        compiledSource: CompiledSource, compilationArguments: CompilationArguments = CompilationArguments()
): CompiledSource {
    require(compilationArguments.parentFileManager == null) { "compileWith overrides parentFileManager compilation argument"}
    require(compilationArguments.parentClassLoader == null) { "compileWith overrides parentClassLoader compilation argument"}
    return doCompile(this, compilationArguments, compiledSource.fileManager, compiledSource.classLoader)
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

class JeedFileManager(parentFileManager: JavaFileManager) : ForwardingJavaFileManager<JavaFileManager>(parentFileManager) {
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

    val classFiles: MutableMap<String, JavaFileObject> = mutableMapOf()
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
        val parentList = super.list(location, packageName, kinds, recurse)
        return if (!kinds.contains(JavaFileObject.Kind.CLASS)) {
            parentList
        } else {
            val correctPackageName = if (packageName.isNotEmpty()) {
                packageName.replace(".", "/") + "/"
            } else {
                packageName
            }
            val myList = classFiles.filter { (name, _) ->
                if (!name.startsWith(correctPackageName)) {
                    false
                } else {
                    val nameSuffix = name.removePrefix(correctPackageName)
                    recurse || nameSuffix.split("/").size == 1
                }
            }.values
            parentList.plus(myList).toMutableList()
        }
    }
    override fun inferBinaryName(location: JavaFileManager.Location?, file: JavaFileObject): String {
        return file.name.substring(0, file.name.lastIndexOf('.')).replace('/', '.')
    }
}

class JeedClassLoader(
        val jeedFileManager: JeedFileManager,
        parentClassLoader: ClassLoader?
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
            val classFile = jeedFileManager.getJavaFileForInput(
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

    override fun loadClass(name: String): Class<*> {
        if (name.startsWith("java.lang.reflect")) {
            throw ClassNotFoundException(name)
        }
        return super.loadClass(name)
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
