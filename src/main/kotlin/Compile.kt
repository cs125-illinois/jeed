package edu.illinois.cs.cs125.jeed

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

data class CompilationArguments(
        val wError: Boolean = false,
        val Xlint: String = "all"
)

private val compiler = ToolProvider.getSystemJavaCompiler()
        ?: throw Exception("compiler not found: you are probably running a JRE, not a JDK")
private val globalClassLoader = ClassLoader.getSystemClassLoader()

@Suppress("UNUSED")
class CompiledSource(val source: Source, val messages: List<CompilationMessage>, val classLoader: ClassLoader)

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
        return if (!(kinds.contains(JavaFileObject.Kind.CLASS))) {
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

    fun getClassLoader(): ClassLoader {
        return object : ClassLoader(globalClassLoader) {
            override fun findClass(name: String): Class<*> {
                @Suppress("UNREACHABLE_CODE")
                return try {
                    val classFile: JavaFileObject? = getJavaFileForInput(
                            StandardLocation.CLASS_OUTPUT,
                            name,
                            JavaFileObject.Kind.CLASS
                    )
                    val byteArray = classFile!!.openInputStream().readAllBytes()
                    return defineClass(name, byteArray, 0, byteArray.size)
                } catch (e: Exception) {
                    throw ClassNotFoundException(name)
                }
            }
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
class CompilationFailed(errors: List<CompilationError>) : JeepError(errors)

fun Source.compile(
        compilationArguments: CompilationArguments = CompilationArguments()
): CompiledSource {
    val units = sources.entries.map { Unit(it) }
    val results = Results()
    val fileManager = FileManager(results)

    val options = mutableSetOf<String>()
    options.add("-Xlint:${compilationArguments.Xlint}")

    compiler.getTask(null, fileManager, results, options.toList(), null, units).call()

    fileManager.close()

    val errors = results.diagnostics.filter {
        it.kind == Diagnostic.Kind.ERROR || (it.kind == Diagnostic.Kind.WARNING && compilationArguments.wError)
    }.map {
        val originalLocation = SourceLocation(it.source.name, it.lineNumber, it.columnNumber)
        val remappedLocation = this.mapLocation(originalLocation)
        CompilationError(remappedLocation, it.getMessage(Locale.US))
    }
    if (errors.isNotEmpty()) {
        throw CompilationFailed(errors)
    }

    val messages = results.diagnostics.map {
        val originalLocation = SourceLocation(it.source.name, it.lineNumber, it.columnNumber)
        val remappedLocation = this.mapLocation(originalLocation)
        CompilationMessage(it.kind.toString(), remappedLocation, it.getMessage(Locale.US))
    }
    val classLoader = AccessController.doPrivileged(PrivilegedAction<ClassLoader> {
        fileManager.getClassLoader()
    })

    return CompiledSource(this, messages, classLoader)
}
