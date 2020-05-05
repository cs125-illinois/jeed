@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import io.github.classgraph.ClassGraph
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCliJavaFileManagerImpl
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinToJVMBytecodeCompiler
import org.jetbrains.kotlin.cli.jvm.compiler.MockExternalAnnotationsManager
import org.jetbrains.kotlin.cli.jvm.compiler.MockInferredAnnotationsManager
import org.jetbrains.kotlin.cli.jvm.configureExplicitContentRoots
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesDynamicCompoundIndex
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndex
import org.jetbrains.kotlin.cli.jvm.index.SingleJavaFileRootsIndex
import org.jetbrains.kotlin.codegen.GeneratedClassLoader
import org.jetbrains.kotlin.com.intellij.codeInsight.ExternalAnnotationsManager
import org.jetbrains.kotlin.com.intellij.codeInsight.InferredAnnotationsManager
import org.jetbrains.kotlin.com.intellij.core.CoreJavaFileManager
import org.jetbrains.kotlin.com.intellij.openapi.components.ServiceManager
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiFileFactoryImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.JavaFileManager
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

private val classpath = ClassGraph().classpathFiles.joinToString(separator = File.pathSeparator)

private const val KOTLIN_EMPTY_LOCATION = "/"

@JsonClass(generateAdapter = true)
@Suppress("MatchingDeclarationName")
data class KompilationArguments(
    @Transient val parentClassLoader: ClassLoader = ClassLoader.getSystemClassLoader(),
    val verbose: Boolean = DEFAULT_VERBOSE,
    val allWarningsAsErrors: Boolean = DEFAULT_ALLWARNINGSASERRORS,
    val useCache: Boolean = useCompilationCache,
    val waitForCache: Boolean = false,
    @Transient val parentFileManager: javax.tools.JavaFileManager? = null
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
        val sourceLocation = location
            ?.let { if (it.path == KOTLIN_EMPTY_LOCATION) null else it.path }
            ?.let { source.mapLocation(SourceLocation(it, location.line, location.column)) }
        messages.add(CompilationMessage(severity.presentableName, sourceLocation, message))
    }
}

class SimpleVirtualFile(private val name: String, private val children: Array<VirtualFile> = arrayOf()) :
    VirtualFile() {
    override fun refresh(p0: Boolean, p1: Boolean, p2: Runnable?) {
        println("refresh")
        TODO("Not yet implemented")
    }

    override fun getLength(): Long {
        println("getLength")
        TODO("Not yet implemented")
    }

    override fun getFileSystem(): VirtualFileSystem {
        println("getFileSystem")
        TODO("Not yet implemented")
    }

    override fun getPath(): String {
        println("getPath")
        TODO("Not yet implemented")
    }

    override fun isDirectory() = children.isEmpty()

    override fun getTimeStamp(): Long {
        TODO("Not yet implemented")
    }

    override fun getName() = name

    override fun contentsToByteArray(): ByteArray {
        println("Wow")
        TODO("Not yet implemented")
    }

    override fun isValid() = true

    override fun getInputStream(): InputStream {
        println("getInputStream")
        TODO("Not yet implemented")
    }

    override fun getParent(): VirtualFile {
        TODO("Not yet implemented")
    }

    override fun getChildren(): Array<VirtualFile> = children

    override fun isWritable(): Boolean {
        println("isWritable")
        TODO("Not yet implemented")
    }

    override fun getOutputStream(p0: Any?, p1: Long, p2: Long): OutputStream {
        println("getOutputStream")
        TODO("Not yet implemented")
    }
}

@Suppress("LongMethod", "ReturnCount")
@Throws(CompilationFailed::class)
private fun kompile(
    kompilationArguments: KompilationArguments,
    source: Source,
    parentFileManager: javax.tools.JavaFileManager? = kompilationArguments.parentFileManager,
    parentClassLoader: ClassLoader? = kompilationArguments.parentClassLoader
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

    val appEnvironment =
        KotlinCoreEnvironment.getOrCreateApplicationEnvironmentForProduction(rootDisposable, configuration)

    val ourIndex = object : JvmDependenciesIndex {
        val index = JvmDependenciesDynamicCompoundIndex()

        override val indexedRoots: Sequence<JavaRoot>
            get() {
                println("Here")
                return index.indexedRoots
            }

        override fun <T : Any> findClass(
            classId: ClassId,
            acceptedRootTypes: Set<JavaRoot.RootType>,
            findClassGivenDirectory: (VirtualFile, JavaRoot.RootType) -> T?
        ): T? {
            if (classId.toString() == "/Test") {
                println("Here")
                return findClassGivenDirectory(
                    SimpleVirtualFile("", arrayOf(SimpleVirtualFile("Test.class"))),
                    JavaRoot.RootType.BINARY
                )
            }
            return index.findClass(classId, acceptedRootTypes, findClassGivenDirectory)
        }

        override fun traverseDirectoriesInPackage(
            packageFqName: FqName,
            acceptedRootTypes: Set<JavaRoot.RootType>,
            continueSearch: (VirtualFile, JavaRoot.RootType) -> Boolean
        ) {
            println(
                continueSearch(
                    SimpleVirtualFile("", arrayOf(SimpleVirtualFile("Test.class"))),
                    JavaRoot.RootType.BINARY
                )
            )
            return index.traverseDirectoriesInPackage(packageFqName, acceptedRootTypes, continueSearch)
        }
    }

    val projectEnvironment = object : KotlinCoreProjectEnvironment(rootDisposable, appEnvironment) {
        override fun preregisterServices() {
            @Suppress("DEPRECATION")
            KotlinCoreEnvironment.registerProjectExtensionPoints(Extensions.getArea(project))
        }

        override fun registerJavaPsiFacade() {
            with(project) {
                if (kompilationArguments.parentFileManager != null) {
                    val coreJavaFileManager = KotlinCliJavaFileManagerImpl(PsiManager.getInstance(project))
                    coreJavaFileManager.initialize(
                        ourIndex,
                        listOf(),
                        SingleJavaFileRootsIndex(listOf()),
                        false
                    )

                    (ServiceManager.getService(
                        this,
                        JavaFileManager::class.java
                    ) as KotlinCliJavaFileManagerImpl).initialize(
                        ourIndex,
                        listOf(),
                        SingleJavaFileRootsIndex(listOf()),
                        false
                    )
                    registerService(
                        CoreJavaFileManager::class.java,
                        coreJavaFileManager as CoreJavaFileManager
                    )
                } else {
                    registerService(
                        CoreJavaFileManager::class.java,
                        ServiceManager.getService(this, JavaFileManager::class.java) as CoreJavaFileManager
                    )
                }

                KotlinCoreEnvironment.registerKotlinLightClassSupport(project)

                registerService(ExternalAnnotationsManager::class.java, MockExternalAnnotationsManager())
                registerService(InferredAnnotationsManager::class.java, MockInferredAnnotationsManager())
            }

            super.registerJavaPsiFacade()
        }
    }

    val environment = KotlinCoreEnvironment.createForProduction(
        projectEnvironment, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES
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
        parentFileManager ?: standardFileManager,
        GeneratedClassLoader(state.factory, kompilationArguments.parentClassLoader)
    )
    val classLoader = JeedClassLoader(fileManager, parentClassLoader)

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
