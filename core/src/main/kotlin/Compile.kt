package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.Charset
import java.time.Instant
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import org.jetbrains.kotlin.codegen.GeneratedClassLoader

private val systemCompiler = ToolProvider.getSystemJavaCompiler() ?: error {
    "systemCompiler not found: you are probably running a JRE, not a JDK"
}
const val DEFAULT_JAVA_VERSION = 10
val systemCompilerName = systemCompiler.sourceVersions.max().toString()
val systemCompilerVersion = systemCompilerName.let {
    @Suppress("TooGenericExceptionCaught") try {
        it.split("_")[1].toInt()
    } catch (e: Exception) {
        DEFAULT_JAVA_VERSION
    }
}

@JsonClass(generateAdapter = true)
data class CompilationArguments(
    val wError: Boolean = DEFAULT_WERROR,
    @Suppress("ConstructorParameterNaming") val Xlint: String = DEFAULT_XLINT,
    val enablePreview: Boolean = DEFAULT_ENABLE_PREVIEW,
    @Transient val parentFileManager: JavaFileManager? = null,
    @Transient val parentClassLoader: ClassLoader? = null,
    val useCache: Boolean = useCompilationCache,
    val waitForCache: Boolean = false
) {
    companion object {
        const val DEFAULT_WERROR = false
        const val DEFAULT_XLINT = "all"
        const val DEFAULT_ENABLE_PREVIEW = true
        const val PREVIEW_STARTED = 11
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CompilationArguments

        if (wError != other.wError) return false
        if (enablePreview != other.enablePreview) return false
        if (Xlint != other.Xlint) return false
        if (useCache != other.useCache) return false
        if (waitForCache != other.waitForCache) return false

        return true
    }
    override fun hashCode(): Int {
        var result = wError.hashCode()
        result = 31 * result + enablePreview.hashCode()
        result = 31 * result + Xlint.hashCode()
        result = 31 * result + useCache.hashCode()
        result = 31 * result + waitForCache.hashCode()
        return result
    }
}

@JsonClass(generateAdapter = true)
class CompilationError(location: SourceLocation?, message: String) : SourceError(location, message)

class CompilationFailed(errors: List<CompilationError>) : JeedError(errors) {
    override fun toString(): String {
        return "compilation errors were encountered: ${errors.joinToString(separator = ",")}"
    }
}

@JsonClass(generateAdapter = true)
class CompilationMessage(@Suppress("unused") val kind: String, location: SourceLocation?, message: String) :
    SourceError(location, message)

class CompiledSource(
    val source: Source,
    val messages: List<CompilationMessage>,
    val compiled: Instant,
    val interval: Interval,
    @Transient val classLoader: JeedClassLoader,
    @Transient val fileManager: JeedFileManager,
    @Suppress("unused") val compilerName: String = systemCompilerName,
    val cached: Boolean = false
)

@Suppress("LongMethod")
@Throws(CompilationFailed::class)
private fun compile(
    source: Source,
    compilationArguments: CompilationArguments = CompilationArguments(),
    parentFileManager: JavaFileManager? = compilationArguments.parentFileManager,
    parentClassLoader: ClassLoader? = compilationArguments.parentClassLoader ?: ClassLoader.getSystemClassLoader()
): CompiledSource {
    require(source.type == Source.FileType.JAVA) { "Java compiler needs Java sources" }

    val started = Instant.now()
    source.tryCache(compilationArguments, started, systemCompilerName)?.let { return it }

    val units = source.sources.entries.map { Unit(it) }
    val results = Results()
    val fileManager = JeedFileManager(
        parentFileManager ?: systemCompiler.getStandardFileManager(
            results, Locale.US, Charset.forName("UTF-8")
        )
    )

    val options = mutableSetOf<String>()
    options.add("-Xlint:${compilationArguments.Xlint}")

    if (compilationArguments.enablePreview && systemCompilerVersion >= CompilationArguments.PREVIEW_STARTED) {
        options.addAll(listOf("--enable-preview", "--release", systemCompilerVersion.toString()))
    }

    systemCompiler.getTask(null, fileManager, results, options.toList(), null, units).call()
    fileManager.close()

    fun getMappedLocation(diagnostic: Diagnostic<out JavaFileObject>): SourceLocation? {
        return diagnostic
            .let { if (it.source == null) null else it }
            ?.let { msg -> SourceLocation(msg.source.name, msg.lineNumber.toInt(), msg.columnNumber.toInt()) }
            ?.let { loc -> source.mapLocation(loc) }
    }

    val errors = results.diagnostics.filter {
        it.kind == Diagnostic.Kind.ERROR || (it.kind == Diagnostic.Kind.WARNING && compilationArguments.wError)
    }.map {
        CompilationError(getMappedLocation(it), it.getMessage(Locale.US))
    }
    if (errors.isNotEmpty()) {
        throw CompilationFailed(errors)
    }

    val messages = results.diagnostics.map {
        CompilationMessage(it.kind.toString(), getMappedLocation(it), it.getMessage(Locale.US))
    }

    return CompiledSource(
        source,
        messages,
        started,
        Interval(started, Instant.now()),
        JeedClassLoader(fileManager, parentClassLoader),
        fileManager
    ).also {
        it.cache(compilationArguments)
    }
}

fun Source.compile(
    compilationArguments: CompilationArguments = CompilationArguments()
): CompiledSource {
    return compile(this, compilationArguments)
}

fun Source.compileWith(
    compiledSource: CompiledSource,
    compilationArguments: CompilationArguments = CompilationArguments()
): CompiledSource {
    require(compilationArguments.parentFileManager == null) {
        "compileWith overrides parentFileManager compilation argument"
    }
    require(compilationArguments.parentClassLoader == null) {
        "compileWith overrides parentClassLoader compilation argument"
    }
    return compile(this, compilationArguments, compiledSource.fileManager, compiledSource.classLoader)
}

private class Unit(val entry: Map.Entry<String, String>) :
    SimpleJavaFileObject(URI(entry.key), JavaFileObject.Kind.SOURCE) {
    override fun isNameCompatible(simpleName: String?, kind: JavaFileObject.Kind?): Boolean {
        return kind != JavaFileObject.Kind.SOURCE || (simpleName != "module-info" && simpleName != "package-info")
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

fun classNameToPathWithClass(className: String): String {
    return className.replace(".", "/") + ".class"
}

fun classNameToPath(className: String): String {
    return className.replace(".", "/")
}

fun pathToClassName(path: String): String {
    return path.removeSuffix(".class").replace("/", ".")
}

class JeedFileManager(parentFileManager: JavaFileManager) :
    ForwardingJavaFileManager<JavaFileManager>(parentFileManager) {
    private val classFiles: MutableMap<String, JavaFileObject> = mutableMapOf()

    val size: Int
        get() = classFiles.values.filterIsInstance<ByteSource>().map { it.buffer.size() }.sum()

    private class ByteSource(path: String) :
        SimpleJavaFileObject(URI.create("bytearray:///$path"), JavaFileObject.Kind.CLASS) {
        init {
            check(path.endsWith(".class")) { "incorrect suffix for ByteSource path: $path" }
        }

        val buffer: ByteArrayOutputStream = ByteArrayOutputStream()
        override fun openInputStream(): InputStream {
            return ByteArrayInputStream(buffer.toByteArray())
        }
        override fun openOutputStream(): OutputStream {
            return buffer
        }
    }

    constructor(
        parentFileManager: JavaFileManager,
        generatedClassLoader: GeneratedClassLoader
    ) : this(parentFileManager) {
        generatedClassLoader.allGeneratedFiles.filter {
            ".${File(it.relativePath).extension}" == JavaFileObject.Kind.CLASS.extension
        }.forEach {
            classFiles[it.relativePath] = ByteSource(it.relativePath).also { simpleJavaFileObject ->
                simpleJavaFileObject.openOutputStream().write(it.asByteArray())
            }
        }
    }

    val bytecodeForPaths: Map<String, ByteArray>
        get() {
            return classFiles.mapValues {
                it.value.openInputStream().readAllBytes()
            }
        }

    override fun getJavaFileForOutput(
        location: JavaFileManager.Location?,
        className: String,
        kind: JavaFileObject.Kind?,
        sibling: FileObject?
    ): JavaFileObject {
        val classPath = classNameToPathWithClass(className)
        return when {
            location != StandardLocation.CLASS_OUTPUT -> super.getJavaFileForOutput(location, className, kind, sibling)
            kind != JavaFileObject.Kind.CLASS -> throw UnsupportedOperationException()
            else -> {
                ByteSource(classPath).also {
                    classFiles[classPath] = it
                }
            }
        }
    }

    override fun getJavaFileForInput(
        location: JavaFileManager.Location?,
        className: String,
        kind: JavaFileObject.Kind
    ): JavaFileObject? {
        return if (location != StandardLocation.CLASS_OUTPUT) {
            super.getJavaFileForInput(location, className, kind)
        } else {
            classFiles[classNameToPathWithClass(className)]
        }
    }

    override fun list(
        location: JavaFileManager.Location?,
        packageName: String,
        kinds: MutableSet<JavaFileObject.Kind>,
        recurse: Boolean
    ): MutableIterable<JavaFileObject> {
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

class JeedClassLoader(private val fileManager: JeedFileManager, parentClassLoader: ClassLoader?) :
    ClassLoader(parentClassLoader), Sandbox.SandboxableClassLoader, Sandbox.EnumerableClassLoader {

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
        @Suppress("UNREACHABLE_CODE", "TooGenericExceptionCaught")
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
