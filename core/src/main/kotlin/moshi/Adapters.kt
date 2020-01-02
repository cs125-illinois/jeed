package edu.illinois.cs.cs125.jeed.core.moshi

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.ToJson
import edu.illinois.cs.cs125.jeed.core.CheckstyleError
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CompilationError
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.CompilationMessage
import edu.illinois.cs.cs125.jeed.core.CompiledSource
import edu.illinois.cs.cs125.jeed.core.ExecutionFailed
import edu.illinois.cs.cs125.jeed.core.Interval
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Snippet
import edu.illinois.cs.cs125.jeed.core.SnippetTransformationError
import edu.illinois.cs.cs125.jeed.core.SnippetTransformationFailed
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.SourceRange
import edu.illinois.cs.cs125.jeed.core.TemplatedSource
import edu.illinois.cs.cs125.jeed.core.TemplatingError
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jeed.core.check
import java.security.Permission
import java.time.Instant

@JvmField
val Adapters = setOf(
    InstantAdapter(),
    PermissionAdapter(),
    SnippetAdapter(),
    SnippetTransformationErrorAdapter(),
    SnippetTransformationFailedAdapter(),
    CompilationFailedAdapter(),
    CheckstyleFailedAdapter(),
    TemplatingErrorAdapter(),
    TemplatingFailedAdapter()
)

class InstantAdapter {
    @FromJson
    fun instantFromJson(timestamp: String): Instant {
        return Instant.parse(timestamp)
    }

    @ToJson
    fun instantToJson(instant: Instant): String {
        return instant.toString()
    }
}

@JsonClass(generateAdapter = true)
data class PermissionJson(val klass: String, val name: String, val actions: String?)

class PermissionAdapter {
    @FromJson
    fun permissionFromJson(permissionJson: PermissionJson): Permission {
        val klass = Class.forName(permissionJson.klass)
        val constructor = klass.getConstructor(String::class.java, String::class.java)
        return constructor.newInstance(permissionJson.name, permissionJson.actions) as Permission
    }

    @ToJson
    fun permissionToJson(permission: Permission): PermissionJson {
        return PermissionJson(permission.javaClass.name, permission.name, permission.actions)
    }
}

@JsonClass(generateAdapter = true)
data class CheckstyleFailedJson(val errors: List<CheckstyleError>)

class CheckstyleFailedAdapter {
    @FromJson
    fun checkstyleFailedFromJson(checkstyleFailedJson: CheckstyleFailedJson): CheckstyleFailed {
        return CheckstyleFailed(checkstyleFailedJson.errors)
    }

    @Suppress("UNCHECKED_CAST")
    @ToJson
    fun checkstyleFailedToJson(checkstyleFailed: CheckstyleFailed): CheckstyleFailedJson {
        return CheckstyleFailedJson(checkstyleFailed.errors as List<CheckstyleError>)
    }
}

@JsonClass(generateAdapter = true)
data class CompilationFailedJson(val errors: List<CompilationError>)

class CompilationFailedAdapter {
    @FromJson
    fun compilationFailedFromJson(compilationFailedJson: CompilationFailedJson): CompilationFailed {
        return CompilationFailed(compilationFailedJson.errors)
    }

    @Suppress("UNCHECKED_CAST")
    @ToJson
    fun compilationFailedToJson(compilationFailed: CompilationFailed): CompilationFailedJson {
        return CompilationFailedJson(compilationFailed.errors as List<CompilationError>)
    }
}

@JsonClass(generateAdapter = true)
data class SnippetTransformationErrorJson(val line: Int, val column: Int, val message: String)

class SnippetTransformationErrorAdapter {
    @FromJson
    fun snippetTransformationErrorFromJson(
        snippetParseErrorJson: SnippetTransformationErrorJson
    ): SnippetTransformationError {
        return SnippetTransformationError(
            snippetParseErrorJson.line, snippetParseErrorJson.column, snippetParseErrorJson.message
        )
    }

    @ToJson
    fun snippetTransformationErrorToJson(
        snippetTransformationError: SnippetTransformationError
    ): SnippetTransformationErrorJson {
        return SnippetTransformationErrorJson(
            snippetTransformationError.location.line,
            snippetTransformationError.location.column,
            snippetTransformationError.message
        )
    }
}

@JsonClass(generateAdapter = true)
data class SnippetTransformationFailedJson(val errors: List<SnippetTransformationError>)

class SnippetTransformationFailedAdapter {
    @FromJson
    fun snippetParsingFailedFromJson(
        snippetParsingFailedJson: SnippetTransformationFailedJson
    ): SnippetTransformationFailed {
        return SnippetTransformationFailed(snippetParsingFailedJson.errors)
    }

    @Suppress("UNCHECKED_CAST")
    @ToJson
    fun snippetParsingFailedToJson(
        snippetTransformationFailed: SnippetTransformationFailed
    ): SnippetTransformationFailedJson {
        return SnippetTransformationFailedJson(snippetTransformationFailed.errors as List<SnippetTransformationError>)
    }
}

@JsonClass(generateAdapter = true)
data class TemplatingErrorJson(val name: String, val line: Int, val column: Int, val message: String)

class TemplatingErrorAdapter {
    @FromJson
    fun templatingErrorFromJson(templatingErrorJson: TemplatingErrorJson): TemplatingError {
        return TemplatingError(
            templatingErrorJson.name, templatingErrorJson.line, templatingErrorJson.column, templatingErrorJson.message
        )
    }

    @ToJson
    fun templatingErrorToJson(templatingError: TemplatingError): TemplatingErrorJson {
        return TemplatingErrorJson(
            templatingError.location.source,
            templatingError.location.line,
            templatingError.location.column,
            templatingError.message
        )
    }
}

@JsonClass(generateAdapter = true)
data class TemplatingFailedJson(val errors: List<TemplatingError>)

class TemplatingFailedAdapter {
    @FromJson
    fun templatingFailedFromJson(templatingFailedJson: TemplatingFailedJson): TemplatingFailed {
        return TemplatingFailed(templatingFailedJson.errors)
    }

    @Suppress("UNCHECKED_CAST")
    @ToJson
    fun templatingFailedToJson(templatingFailed: TemplatingFailed): TemplatingFailedJson {
        return TemplatingFailedJson(templatingFailed.errors as List<TemplatingError>)
    }
}

@JsonClass(generateAdapter = true)
data class SnippetJson(
    val sources: Map<String, String>,
    val originalSource: String,
    val rewrittenSource: String,
    val snippetRange: SourceRange,
    val wrappedClassName: String,
    val looseCodeMethodName: String
)

class SnippetAdapter {
    @FromJson
    fun snippetFromJson(snippetJson: SnippetJson): Snippet {
        return Snippet(
            snippetJson.sources,
            snippetJson.originalSource,
            snippetJson.rewrittenSource,
            snippetJson.snippetRange,
            snippetJson.wrappedClassName,
            snippetJson.looseCodeMethodName
        )
    }

    @ToJson
    fun snippetToJson(snippet: Snippet): SnippetJson {
        return SnippetJson(
            snippet.sources,
            snippet.originalSource,
            snippet.rewrittenSource,
            snippet.snippetRange,
            snippet.wrappedClassName,
            snippet.looseCodeMethodName
        )
    }
}

@JsonClass(generateAdapter = true)
data class ExecutionFailedResult(
    val classNotFound: String?,
    val methodNotFound: String?,
    val threw: String?
) {
    constructor(executionFailed: ExecutionFailed) : this(
        if (executionFailed.classNotFound != null) {
            executionFailed.classNotFound.klass
        } else {
            null
        }, if (executionFailed.methodNotFound != null) {
            executionFailed.methodNotFound.method
        } else {
            null
        }, executionFailed.threw
    )
}

@Suppress("unused")
@JsonClass(generateAdapter = true)
class CompiledSourceResult(
    val messages: List<CompilationMessage>,
    val interval: Interval,
    val compilerName: String
) {
    constructor(compiledSource: CompiledSource) : this(
        compiledSource.messages, compiledSource.interval, compiledSource.compilerName
    )
}

@Suppress("unused")
@JsonClass(generateAdapter = true)
class TemplatedSourceResult(
    val sources: Map<String, String>,
    val originalSources: Map<String, String>
) {
    constructor(templatedSource: TemplatedSource) : this(templatedSource.sources, templatedSource.originalSources)
}

@Suppress("unused")
@JsonClass(generateAdapter = true)
class ThrownException(
    val klass: String,
    val message: String?
) {
    constructor(throwable: Throwable) : this(throwable::class.java.typeName, throwable.message)
}

@Suppress("unused")
@JsonClass(generateAdapter = true)
class TaskResults(
    val returned: String?,
    val threw: ThrownException?,
    val timeout: Boolean,
    val outputLines: List<Sandbox.TaskResults.OutputLine> = listOf(),
    val permissionRequests: List<Sandbox.TaskResults.PermissionRequest> = listOf(),
    val interval: Interval,
    val executionInterval: Interval,
    val truncatedLines: Int
) {
    constructor(taskResults: Sandbox.TaskResults<*>) : this(
        taskResults.returned.toString(),
        if (taskResults.threw != null) {
            ThrownException(taskResults.threw)
        } else {
            null
        },
        taskResults.timeout,
        taskResults.outputLines.toList(),
        taskResults.permissionRequests.toList(),
        taskResults.interval,
        taskResults.executionInterval,
        taskResults.truncatedLines
    )
}

@Suppress("unused")
@JsonClass(generateAdapter = true)
class SourceTaskResults(
    val klass: String,
    val method: String,
    val returned: String?,
    val threw: ThrownException?,
    val timeout: Boolean,
    val outputLines: List<Sandbox.TaskResults.OutputLine> = listOf(),
    val permissionRequests: List<Sandbox.TaskResults.PermissionRequest> = listOf(),
    val interval: Interval,
    val executionInterval: Interval,
    val truncatedLines: Int
) {
    constructor(taskResults: Sandbox.TaskResults<*>, sourceExecutionArguments: SourceExecutionArguments) : this(
        sourceExecutionArguments.klass ?: check { "should have a klass name" },
        sourceExecutionArguments.method,
        taskResults.returned.toString(),
        if (taskResults.threw != null) {
            ThrownException(taskResults.threw)
        } else {
            null
        },
        taskResults.timeout,
        taskResults.outputLines.toList(),
        taskResults.permissionRequests.toList(),
        taskResults.interval,
        taskResults.executionInterval,
        taskResults.truncatedLines
    )
}
