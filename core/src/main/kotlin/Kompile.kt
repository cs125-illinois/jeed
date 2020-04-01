@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import io.github.classgraph.ClassGraph
import java.nio.charset.Charset
import java.time.Instant
import java.util.Locale
import javax.tools.ToolProvider
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler
import org.jetbrains.kotlin.cli.jvm.configureExplicitContentRoots
import org.jetbrains.kotlin.codegen.GeneratedClassLoader
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiFileFactoryImpl
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.psi.KtFile

private val classpath = ClassGraph().classpathFiles.joinToString(separator = File.pathSeparator)

private const val KOTLIN_EMPTY_LOCATION = "/"

@JsonClass(generateAdapter = true)
@Suppress("MatchingDeclarationName")
data class KompilationArguments(
    @Transient val parentClassLoader: ClassLoader = ClassLoader.getSystemClassLoader(),
    val verbose: Boolean = DEFAULT_VERBOSE,
    val allWarningsAsErrors: Boolean = DEFAULT_ALLWARNINGSASERRORS,
    val useCache: Boolean = useCompilationCache,
    val waitForCache: Boolean = false
) {
    val arguments: K2JVMCompilerArguments = K2JVMCompilerArguments()

    init {
        arguments.classpath = classpath

        arguments.verbose = verbose
        arguments.allWarningsAsErrors = allWarningsAsErrors

        arguments.noStdlib = true
    }
    companion object {
        const val DEFAULT_VERBOSE = false
        const val DEFAULT_ALLWARNINGSASERRORS = false
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KompilationArguments

        if (verbose != other.verbose) return false
        if (allWarningsAsErrors != other.allWarningsAsErrors) return false
        if (useCache != other.useCache) return false

        return true
    }
    override fun hashCode(): Int {
        var result = verbose.hashCode()
        result = 31 * result + allWarningsAsErrors.hashCode()
        result = 31 * result + useCache.hashCode()
        return result
    }
}

private class JeedMessageCollector(val source: Source, val allWarningsAsErrors: Boolean) : MessageCollector {
    private val messages: MutableList<CompilationMessage> = mutableListOf()

    override fun clear() {
        messages.clear()
    }

    val errors: List<CompilationError>
        get() = messages.filter {
            it.kind == CompilerMessageSeverity.ERROR.presentableName ||
                allWarningsAsErrors && (it.kind == CompilerMessageSeverity.WARNING.presentableName ||
                it.kind == CompilerMessageSeverity.STRONG_WARNING.presentableName)
        }.map {
            CompilationError(it.location, it.message)
        }

    val warnings: List<CompilationMessage>
        get() = messages.filter {
            it.kind != CompilerMessageSeverity.ERROR.presentableName
        }.map {
            CompilationMessage("warning", it.location, it.message)
        }

    override fun hasErrors(): Boolean {
        return errors.isNotEmpty()
    }

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
        if (severity == CompilerMessageSeverity.LOGGING || severity == CompilerMessageSeverity.INFO) {
            return
        }
        require(location != null) { "location should not be null (severity $severity): $message" }
        val actualLocation = if (location.path == KOTLIN_EMPTY_LOCATION) {
            ""
        } else {
            location.path
        }
        val originalLocation = SourceLocation(actualLocation, location.line, location.column)
        messages.add(CompilationMessage(severity.presentableName, source.mapLocation(originalLocation), message))
    }
}

private val standardFileManager = run {
    val results = Results()
    ToolProvider.getSystemJavaCompiler().getStandardFileManager(results, Locale.US, Charset.forName("UTF-8")).also {
        check(results.diagnostics.isEmpty()) {
            "fileManager generated errors ${results.diagnostics}"
        }
    }
}

@Suppress("LongMethod")
@Throws(CompilationFailed::class)
private fun kompile(
    kompilationArguments: KompilationArguments,
    source: Source
): CompiledSource {
    require(source.type == Source.FileType.KOTLIN) { "Kotlin compiler needs Kotlin sources" }

    val started = Instant.now()
    source.tryCache(kompilationArguments, started, systemCompilerName)?.let { return it }

    val rootDisposable = Disposer.newDisposable()
    val messageCollector = JeedMessageCollector(source, kompilationArguments.arguments.allWarningsAsErrors)
    val configuration = CompilerConfiguration().apply {
        put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
        put(CommonConfigurationKeys.MODULE_NAME, JvmProtoBufUtil.DEFAULT_MODULE_NAME)
        configureExplicitContentRoots(kompilationArguments.arguments)
    }

    setIdeaIoUseFallback()
    val environment = KotlinCoreEnvironment.createForProduction(
        rootDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES
    )

    val psiFileFactory = PsiFileFactory.getInstance(environment.project) as PsiFileFactoryImpl
    val psiFiles = source.sources.map { (name, contents) ->
        val virtualFile = LightVirtualFile(name, KotlinLanguage.INSTANCE, contents)
        psiFileFactory.trySetupPsiForFile(virtualFile, KotlinLanguage.INSTANCE, true, false) as KtFile?
            ?: error("couldn't parse source to psiFile")
    }.toMutableList()

    environment::class.java.getDeclaredField("sourceFiles").also { field ->
        field.isAccessible = true
        field.set(environment, psiFiles)
    }

    val state = KotlinToJVMBytecodeCompiler.analyzeAndGenerate(environment)
    if (messageCollector.errors.isNotEmpty()) {
        throw CompilationFailed(messageCollector.errors)
    }
    check(state != null) { "compilation should have succeeded" }

    val fileManager = JeedFileManager(
        standardFileManager,
        GeneratedClassLoader(state.factory, kompilationArguments.parentClassLoader)
    )
    val classLoader = JeedClassLoader(fileManager, kompilationArguments.parentClassLoader)

    return CompiledSource(
        source, messageCollector.warnings, started, Interval(started, Instant.now()), classLoader, fileManager
    ).also {
        it.cache(kompilationArguments)
    }
}

fun Source.kompile(kompilationArguments: KompilationArguments = KompilationArguments()): CompiledSource {
    return kompile(kompilationArguments, this)
}

private val KOTLIN_COROUTINE_IMPORTS = setOf("kotlinx.coroutines", "kotlin.coroutines")
const val KOTLIN_COROUTINE_MIN_TIMEOUT = 400L
const val KOTLIN_COROUTINE_MIN_EXTRA_THREADS = 4

fun CompiledSource.usesCoroutines(): Boolean {
    return this.source.parseTree.any { (_, parseResults) ->
        val (parseTree, _) = parseResults
        parseTree as? KotlinParser.KotlinFileContext ?: check { "Parse tree is not from a Kotlin file" }
        parseTree.preamble().importList().importHeader().any { importName ->
            KOTLIN_COROUTINE_IMPORTS.any { importName.identifier().text.startsWith(it) }
        }
    }
}
