package edu.illinois.cs.cs125.jeed.core.server

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.CheckstyleArguments
import edu.illinois.cs.cs125.jeed.core.CompilationArguments
import edu.illinois.cs.cs125.jeed.core.ContainerExecutionArguments
import edu.illinois.cs.cs125.jeed.core.KompilationArguments
import edu.illinois.cs.cs125.jeed.core.KtLintArguments
import edu.illinois.cs.cs125.jeed.core.MutationsArguments
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.SnippetArguments
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import java.security.Permission

@Suppress("EnumEntryName", "EnumNaming")
enum class Task {
    template,
    snippet,
    compile,
    kompile,
    checkstyle,
    ktlint,
    complexity,
    execute,
    cexecute,
    features,
    mutations,
    disassemble
}

@JsonClass(generateAdapter = true)
@Suppress("LongParameterList")
class TaskArguments(
    val snippet: SnippetArguments = SnippetArguments(),
    val compilation: CompilationArguments = CompilationArguments(),
    val kompilation: KompilationArguments = KompilationArguments(),
    val checkstyle: CheckstyleArguments = CheckstyleArguments(),
    val ktlint: KtLintArguments = KtLintArguments(),
    // val complexity: currently accepts no arguments
    val execution: ServerSourceExecutionArguments = ServerSourceExecutionArguments(),
    val cexecution: ContainerExecutionArguments = ContainerExecutionArguments(),
    // val features: currently accepts no arguments
    val mutations: MutationsArguments = MutationsArguments(),
    // val disassemble: currently accepts no arguments,
    val plugins: PluginArguments = PluginArguments()
)

@JsonClass(generateAdapter = true)
data class ServerSourceExecutionArguments(
    val klass: String? = null,
    val method: String? = null,
    var timeout: Long? = null,
    var permissions: Set<Permission>? = null,
    var maxExtraThreads: Int? = null,
    var maxOutputLines: Int? = null,
    var maxIOBytes: Int? = null,
    var classLoaderConfiguration: ServerClassLoaderConfiguration? = null
) {
    fun setDefaults(
        defaultTimeout: Long,
        defaultPermissions: Set<Permission>,
        defaultMaxExtraThreads: Int,
        defaultMaxOutputLines: Int,
        defaultMaxIOBytes: Int,
        defaultWhitelistedClasses: Set<String>,
        defaultBlacklistedClasses: Set<String>,
        defaultUnsafeExceptions: Set<String>,
        defaultBlacklistedMethods: Set<Sandbox.MethodFilter>
    ) {
        timeout = timeout ?: defaultTimeout
        permissions = permissions ?: defaultPermissions
        maxExtraThreads = maxExtraThreads ?: defaultMaxExtraThreads
        maxOutputLines = maxOutputLines ?: defaultMaxOutputLines
        maxIOBytes = maxIOBytes ?: defaultMaxIOBytes
        classLoaderConfiguration = classLoaderConfiguration ?: ServerClassLoaderConfiguration()
        classLoaderConfiguration!!.setDefaults(
            defaultWhitelistedClasses,
            defaultBlacklistedClasses,
            defaultUnsafeExceptions,
            defaultBlacklistedMethods
        )
    }

    fun toSourceExecutionArguments(): SourceExecutionArguments =
        SourceExecutionArguments(
            klass,
            method,
            timeout!!,
            permissions!!,
            maxExtraThreads!!,
            maxOutputLines!!,
            maxIOBytes!!,
            classLoaderConfiguration!!.toClassloaderConfiguration()
        )
}

@JsonClass(generateAdapter = true)
data class ServerClassLoaderConfiguration(
    var whitelistedClasses: Set<String>? = null,
    var blacklistedClasses: Set<String>? = null,
    var unsafeExceptions: Set<String>? = null,
    var blacklistedMethods: Set<Sandbox.MethodFilter>? = null
) {
    fun setDefaults(
        defaultWhitelistedClasses: Set<String>,
        defaultBlacklistedClasses: Set<String>,
        defaultUnsafeExceptions: Set<String>,
        defaultBlacklistedMethods: Set<Sandbox.MethodFilter>
    ) {
        whitelistedClasses = whitelistedClasses ?: defaultWhitelistedClasses
        blacklistedClasses = blacklistedClasses ?: defaultBlacklistedClasses
        unsafeExceptions = unsafeExceptions ?: defaultUnsafeExceptions
        blacklistedMethods = blacklistedMethods ?: defaultBlacklistedMethods
    }

    fun toClassloaderConfiguration() = Sandbox.ClassLoaderConfiguration(
        whitelistedClasses!!,
        blacklistedClasses!!,
        unsafeExceptions = unsafeExceptions!!,
        blacklistedMethods = blacklistedMethods!!
    )
}

@JsonClass(generateAdapter = true)
data class PluginArguments(
    var lineCountLimit: Long? = null,
    var memoryTotalLimit: Long? = null,
    var memoryAllocationLimit: Long? = null
) {
    fun setDefaults(
        defaultLineCountLimit: Long,
        defaultMemoryTotalLimit: Long,
        defaultMemoryAllocationLimit: Long
    ) {
        lineCountLimit = lineCountLimit ?: defaultLineCountLimit
        memoryTotalLimit = memoryTotalLimit ?: defaultMemoryTotalLimit
        memoryAllocationLimit = memoryAllocationLimit ?: defaultMemoryAllocationLimit
    }

    companion object {
        const val DEFAULT_LINE_COUNT_LIMIT = 4 * 1024 * 1024L
        const val DEFAULT_MEMORY_TOTAL_LIMIT = 32 * 1024 * 1024L
        const val DEFAULT_MEMORY_ALLOCATION_LIMIT = 512 * 1024L
    }
}
