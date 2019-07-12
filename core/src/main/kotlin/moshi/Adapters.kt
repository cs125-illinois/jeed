package edu.illinois.cs.cs125.jeed.core.moshi

import edu.illinois.cs.cs125.jeed.core.*
import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.security.Permission
import java.time.Instant

@JvmField
val Adapters = setOf(
        SnippetTransformationErrorAdapter(),
        SnippetTransformationFailedAdapter(),
        CompilationFailedAdapter(),
        CompiledSourceAdapter(),
        TemplatingErrorAdapter(),
        TemplatingFailedAdapter(),
        PermissionAdapter(),
        ThrowableAdapter(),
        InstantAdapter(),
        TaskResultsAdapter()
)

data class CompilationFailedJson(val errors: List<CompilationFailed.CompilationError>)
class CompilationFailedAdapter {
    @FromJson
    fun compilationFailedFromJson(compilationFailedJson: CompilationFailedJson): CompilationFailed {
        return CompilationFailed(compilationFailedJson.errors)
    }
    @Suppress("UNCHECKED_CAST")
    @ToJson
    fun compilationFailedToJson(compilationFailed: CompilationFailed): CompilationFailedJson {
        return CompilationFailedJson(compilationFailed.errors as List<CompilationFailed.CompilationError>)
    }
}
data class CompiledSourceJson(
        val messages: List<CompiledSource.CompilationMessage>,
        val interval: Interval
)
class CompiledSourceAdapter {
    @Throws(Exception::class)
    @Suppress("UNUSED_PARAMETER")
    @FromJson
    fun compiledSourceFromJson(unused: CompiledSourceJson): CompiledSource {
        throw Exception("Can't convert JSON to CompiledSourceAdapter")
    }
    @ToJson
    fun compiledSourceToJson(compiledSource: CompiledSource): CompiledSourceJson {
        return CompiledSourceJson(compiledSource.messages, compiledSource.interval)
    }
}
data class SnippetTransformationErrorJson(val line: Int, val column: Int, val message: String)
class SnippetTransformationErrorAdapter {
    @FromJson fun snippetTransformationErrorFromJson(snippetParseErrorJson: SnippetTransformationErrorJson): SnippetTransformationError {
        return SnippetTransformationError(snippetParseErrorJson.line, snippetParseErrorJson.column, snippetParseErrorJson.message)
    }
    @ToJson fun snippetTransformationErrorToJson(snippetTransformationError: SnippetTransformationError): SnippetTransformationErrorJson {
        return SnippetTransformationErrorJson(snippetTransformationError.location.line, snippetTransformationError.location.column, snippetTransformationError.message)
    }
}
data class SnippetTransformationFailedJson(val errors: List<SnippetTransformationError>)
class SnippetTransformationFailedAdapter {
    @FromJson fun snippetParsingFailedFromJson(snippetParsingFailedJson: SnippetTransformationFailedJson): SnippetTransformationFailed {
        return SnippetTransformationFailed(snippetParsingFailedJson.errors)
    }
    @Suppress("UNCHECKED_CAST")
    @ToJson fun snippetParsingFailedToJson(snippetTransformationFailed: SnippetTransformationFailed): SnippetTransformationFailedJson {
        return SnippetTransformationFailedJson(snippetTransformationFailed.errors as List<SnippetTransformationError>)
    }
}
data class TemplatingErrorJson(val name: String, val line: Int, val column: Int, val message: String)
class TemplatingErrorAdapter {
    @FromJson fun templatingErrorFromJson(templatingErrorJson: TemplatingErrorJson): TemplatingError {
        return TemplatingError(templatingErrorJson.name, templatingErrorJson.line, templatingErrorJson.column, templatingErrorJson.message)
    }
    @ToJson fun templatingErrorToJson(templatingError: TemplatingError): TemplatingErrorJson {
        return TemplatingErrorJson(templatingError.location.source, templatingError.location.line, templatingError.location.column, templatingError.message)
    }
}
data class TemplatingFailedJson(val errors: List<TemplatingError>)
class TemplatingFailedAdapter {
    @FromJson fun templatingFailedFromJson(templatingFailedJson: TemplatingFailedJson): TemplatingFailed {
        return TemplatingFailed(templatingFailedJson.errors)
    }
    @Suppress("UNCHECKED_CAST")
    @ToJson fun templatingFailedToJson(templatingFailed: TemplatingFailed): TemplatingFailedJson {
        return TemplatingFailedJson(templatingFailed.errors as List<TemplatingError>)
    }
}

data class PermissionJson(val type: String, val name: String, val actions: String?)
class PermissionAdapter {
    @FromJson fun permissionFromJson(permissionJson: PermissionJson): Permission {
        val klass = Class.forName("java.security.${permissionJson.type}")
        val constructor = klass.getConstructor(String::class.java, String::class.java)
        return constructor.newInstance(permissionJson.name, permissionJson.actions) as Permission
    }
    @ToJson fun permissionToJson(permission: Permission): PermissionJson {
        return PermissionJson(permission.javaClass.name.split(".").last(), permission.name, permission.actions)
    }
}
data class ThrowableJson(val klass: String, val message: String?)
class ThrowableAdapter {
    @Throws(Exception::class)
    @Suppress("UNUSED_PARAMETER")
    @FromJson
    fun throwableFromJson(unused: ThrowableJson): Throwable {
        throw Exception("Can't convert JSON to Throwable")
    }
    @ToJson fun throwableToJson(throwable: Throwable): ThrowableJson {
        return ThrowableJson(throwable::class.java.typeName, throwable.message)
    }
}
class InstantAdapter {
    @FromJson fun instantFromJson(timestamp: String): Instant {
        return Instant.parse(timestamp)
    }
    @ToJson fun instantToJson(instant: Instant): String {
        return instant.toString()
    }
}
data class TaskResultsJson(
        val returned: String?,
        val threw: Throwable?,
        val timeout: Boolean,
        val outputLines: MutableList<Sandbox.TaskResults.OutputLine> = mutableListOf(),
        val permissionRequests: MutableList<Sandbox.TaskResults.PermissionRequest> = mutableListOf(),
        val interval: Interval,
        val executionInterval: Interval
)
class TaskResultsAdapter {
    @Throws(Exception::class)
    @Suppress("UNUSED_PARAMETER")
    @FromJson
    fun taskResultsFromJson(unused: TaskResultsJson): Sandbox.TaskResults<*> {
        throw Exception("Can't convert JSON to TaskResults")
    }
    @ToJson
    fun taskResultsToJson(taskResults: Sandbox.TaskResults<*>): TaskResultsJson {
        return TaskResultsJson(
                taskResults.returned.toString(),
                taskResults.threw,
                taskResults.timeout,
                taskResults.outputLines,
                taskResults.permissionRequests,
                taskResults.interval,
                taskResults.executionInterval
        )
    }
}
