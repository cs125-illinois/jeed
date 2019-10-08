package edu.illinois.cs.cs125.jeed.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.Charset
import java.security.AccessController
import java.security.PrivilegedAction
import java.time.Instant
import java.util.*
import javax.tools.*

private val systemCompiler = ToolProvider.getSystemJavaCompiler() ?: error("systemCompiler not found: you are probably running a JRE, not a JDK")
val systemCompilerName = systemCompiler.sourceVersions.max().toString()

data class CompilationArguments(
        val wError: Boolean = DEFAULT_WERROR,
        val Xlint: String = DEFAULT_XLINT,
        val previewLevel: Int? = DEFAULT_PREVIEW_LEVEL,
        @Transient val parentFileManager: JavaFileManager? = null,
        @Transient val parentClassLoader: ClassLoader? = null
) {
    companion object {
        const val DEFAULT_WERROR = false
        const val DEFAULT_XLINT = "all"
        const val DEFAULT_PREVIEW_LEVEL = 13
    }
}
class CompilationFailed(errors: List<CompilationError>) : JeedError(errors) {
    class CompilationError(location: SourceLocation, message: String) : SourceError(location, message)

    override fun toString(): String {
        return "compilation errors were encountered: ${errors.joinToString(separator = ",")}"
    }
}
class CompiledSource(
        val source: Source,
        val messages: List<CompilationMessage>,
        val interval: Interval,
        @Transient val classLoader: JeedClassLoader,
        @Transient val fileManager: JeedFileManager,
        @Suppress("unused") val compilerName: String = systemCompilerName
) {
    class CompilationMessage(@Suppress("unused") val kind: String, location: SourceLocation, message: String) : SourceError(location, message)
}
@Throws(CompilationFailed::class)
private fun compile(
        source: Source,
        compilationArguments: CompilationArguments = CompilationArguments(),
        parentFileManager: JavaFileManager? = compilationArguments.parentFileManager,
        parentClassLoader: ClassLoader? = compilationArguments.parentClassLoader ?: ClassLoader.getSystemClassLoader()
): CompiledSource {
    val started = Instant.now()

    val units = source.sources.entries.map { Unit(it) }
    val results = Results()
    val fileManager = JeedFileManager(parentFileManager ?: ToolProvider.getSystemJavaCompiler().getStandardFileManager(results, Locale.US, Charset.forName("UTF-8")))

    val options = mutableSetOf<String>()
    options.add("-Xlint:${compilationArguments.Xlint}")

    if (compilationArguments.previewLevel != null) {
        options.addAll(listOf("--enable-preview", "--release", compilationArguments.previewLevel.toString()))
    }

    systemCompiler.getTask(null, fileManager, results, options.toList(), null, units).call()
    fileManager.close()

    val errors = results.diagnostics.filter {
        it.kind == Diagnostic.Kind.ERROR || (it.kind == Diagnostic.Kind.WARNING && compilationArguments.wError)
    }.map {
        println(it)
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

    return CompiledSource(source, messages, Interval(started, Instant.now()), classLoader, fileManager)
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

class JeedClassLoader(private val fileManager: JeedFileManager, parentClassLoader: ClassLoader?)
    : ClassLoader(parentClassLoader), Sandbox.SandboxableClassLoader, Sandbox.EnumerableClassLoader {

    override val bytecodeForClasses = fileManager.bytecodeForPaths.mapKeys { pathToClassName(it.key) }.toMap()
    override val classLoader: ClassLoader = this

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
