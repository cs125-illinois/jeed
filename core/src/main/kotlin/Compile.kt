package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.ToJson
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

@JsonClass(generateAdapter = true)
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
class CompilationFailed(errors: List<CompilationError>) : JeedError(errors) {
    @JsonClass(generateAdapter = true)
    class CompilationError(location: SourceLocation, message: String) : SourceError(location, message)

    override fun toString(): String {
        return "compilation errors were encountered: ${errors.joinToString(separator = ",")}"
    }
}
class CompiledSource(
        val source: Source,
        val messages: List<CompilationMessage>,
        @Transient val classLoader: JeedClassLoader,
        @Transient val fileManager: JeedFileManager
) {
    @JsonClass(generateAdapter = true)
    class CompilationMessage(val kind: String, location: SourceLocation, message: String) : SourceError(location, message)
}
@Throws(CompilationFailed::class)
private fun compile(
        source: Source,
        compilationArguments: CompilationArguments = CompilationArguments(),
        parentFileManager: JavaFileManager? = compilationArguments.parentFileManager,
        parentClassLoader: ClassLoader? = compilationArguments.parentClassLoader ?: ClassLoader.getSystemClassLoader()
): CompiledSource {
    val units = source.sources.entries.map { Unit(it) }
    val results = Results()
    val fileManager = JeedFileManager(parentFileManager ?: ToolProvider.getSystemJavaCompiler().getStandardFileManager(results, Locale.US, Charset.forName("UTF-8")))

    val options = mutableSetOf<String>()
    options.add("-Xlint:${compilationArguments.Xlint}")
    systemCompiler.getTask(null, fileManager, results, options.toList(), null, units).call()
    fileManager.close()

    val errors = results.diagnostics.filter {
        it.kind == Diagnostic.Kind.ERROR || (it.kind == Diagnostic.Kind.WARNING && compilationArguments.wError)
    }.map {
        val originalLocation = SourceLocation(it.source.name, it.lineNumber.toInt(), it.columnNumber.toInt())
        val remappedLocation = source.mapLocation(originalLocation)
        CompilationFailed.CompilationError(remappedLocation, it.getMessage(Locale.US))
    }
    if (errors.isNotEmpty()) {
        throw CompilationFailed(errors)
    }

    val messages = results.diagnostics.map {
        val originalLocation = SourceLocation(it.source.name, it.lineNumber.toInt(), it.columnNumber.toInt())
        val remappedLocation = source.mapLocation(originalLocation)
        CompiledSource.CompilationMessage(it.kind.toString(), remappedLocation, it.getMessage(Locale.US))
    }
    val classLoader = AccessController.doPrivileged(PrivilegedAction<JeedClassLoader> {
        JeedClassLoader(fileManager, parentClassLoader)
    })

    return CompiledSource(source, messages, classLoader, fileManager)
}

fun Source.compile(
        compilationArguments: CompilationArguments = CompilationArguments()
): CompiledSource {
    return compile(this, compilationArguments)
}
fun Source.compileWith(
        compiledSource: CompiledSource, compilationArguments: CompilationArguments = CompilationArguments()
): CompiledSource {
    require(compilationArguments.parentFileManager == null) { "compileWith overrides parentFileManager compilation argument"}
    require(compilationArguments.parentClassLoader == null) { "compileWith overrides parentClassLoader compilation argument"}
    return compile(this, compilationArguments, compiledSource.fileManager, compiledSource.classLoader)
}

private class Unit(val entry: Map.Entry<String, String>) : SimpleJavaFileObject(URI(entry.key), JavaFileObject.Kind.SOURCE) {
    override fun isNameCompatible(simpleName: String?, kind: JavaFileObject.Kind?): Boolean { return true }
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence { return entry.value }
    override fun toString(): String { return entry.key }
}

private class Results : DiagnosticListener<JavaFileObject> {
    val diagnostics = mutableListOf<Diagnostic<out JavaFileObject>>()
    override fun report(diagnostic: Diagnostic<out JavaFileObject>) { diagnostics.add(diagnostic) }
}

fun classNameToPath(className: String): String { return className.replace(".", "/") }
fun pathToClassName(path: String): String { return path.replace("/", ".") }

class JeedFileManager(parentFileManager: JavaFileManager) : ForwardingJavaFileManager<JavaFileManager>(parentFileManager) {
    private val classFiles: MutableMap<String, JavaFileObject> = mutableMapOf()

    private class ByteSource(path: String, kind: JavaFileObject.Kind)
        : SimpleJavaFileObject(URI.create("bytearray:///$path${kind.extension}"), JavaFileObject.Kind.CLASS) {
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream()
        override fun openInputStream(): InputStream { return ByteArrayInputStream(buffer.toByteArray()) }
        override fun openOutputStream(): OutputStream { return buffer }
    }

    val bytecodeForPaths: Map<String, ByteArray>
        get() {
            return classFiles.mapValues {
                it.value.openInputStream().readAllBytes()
            }
        }


    override fun getJavaFileForOutput(location: JavaFileManager.Location?, className: String, kind: JavaFileObject.Kind?, sibling: FileObject?): JavaFileObject {
        val classPath = classNameToPath(className)
        return when {
            location != StandardLocation.CLASS_OUTPUT -> super.getJavaFileForOutput(location, className, kind, sibling)
            kind != JavaFileObject.Kind.CLASS -> throw UnsupportedOperationException()
            else -> {
                val simpleJavaFileObject = ByteSource(classPath, kind)
                classFiles[classPath] = simpleJavaFileObject
                simpleJavaFileObject
            }
        }
    }
    override fun getJavaFileForInput(location: JavaFileManager.Location?, className: String, kind: JavaFileObject.Kind): JavaFileObject? {
        return if (location != StandardLocation.CLASS_OUTPUT) {
            super.getJavaFileForInput(location, className, kind)
        } else {
            classFiles[classNameToPath(className)]
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
        return if (file is ByteSource) {
            file.name.substring(0, file.name.lastIndexOf('.')).replace('/', '.')
        } else {
            super.inferBinaryName(location, file)
        }
    }
}

class JeedClassLoader(val fileManager: JeedFileManager, parentClassLoader: ClassLoader?)
    : ClassLoader(parentClassLoader), Sandbox.SandboxableClassLoader, Sandbox.EnumerableClassLoader {

    override val bytecodeForClasses = fileManager.bytecodeForPaths.mapKeys { pathToClassName(it.key) }.toMap()
    fun bytecodeForClass(name: String): ByteArray {
        require(bytecodeForClasses.containsKey(name)) { "class loader does not contain class $name" }
        return bytecodeForClasses[name] ?: error("")
    }

    override val definedClasses: Set<String> get() = bytecodeForClasses.keys.toSet()
    override var providedClasses: MutableSet<String> = mutableSetOf()
    override var loadedClasses: MutableSet<String> = mutableSetOf()

    override fun findClass(name: String): Class<*> {
        @Suppress("UNREACHABLE_CODE")
        return try {
            val classFile = fileManager.getJavaFileForInput(
                    StandardLocation.CLASS_OUTPUT,
                    name,
                    JavaFileObject.Kind.CLASS
            ) ?: throw ClassNotFoundException()
            val byteArray = classFile.openInputStream().readAllBytes()
            loadedClasses.add(name)
            providedClasses.add(name)
            return defineClass(name, byteArray, 0, byteArray.size)
        } catch (e: Exception) {
            throw ClassNotFoundException(name)
        }
    }

    override fun loadClass(name: String): Class<*> {
        val klass = super.loadClass(name)
        loadedClasses.add(name)
        return klass
    }
}

data class CompilationFailedJson(val errors: List<CompilationFailed.CompilationError>)
class CompilationFailedAdapter {
    @FromJson fun compilationFailedFromJson(compilationFailedJson: CompilationFailedJson): CompilationFailed {
        return CompilationFailed(compilationFailedJson.errors)
    }
    @Suppress("UNCHECKED_CAST")
    @ToJson fun compilationFailedToJson(compilationFailed: CompilationFailed): CompilationFailedJson {
        return CompilationFailedJson(compilationFailed.errors as List<CompilationFailed.CompilationError>)
    }
}
data class CompiledSourceJson(val messages: List<CompiledSource.CompilationMessage>)
class CompiledSourceAdapter {
    @Throws(Exception::class)
    @Suppress("UNUSED_PARAMETER")
    @FromJson fun compiledSourceFromJson(unused: CompiledSourceJson): CompiledSource {
        throw Exception("Can't convert JSON to CompiledSourceAdapter")
    }
    @ToJson fun compiledSourceToJson(compiledSource: CompiledSource): CompiledSourceJson {
        return CompiledSourceJson(compiledSource.messages)
    }
}

