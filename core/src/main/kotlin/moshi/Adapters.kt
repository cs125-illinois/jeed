package edu.illinois.cs.cs125.jeed.core.moshi

import edu.illinois.cs.cs125.jeed.core.*
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.ToJson
import java.security.Permission
import java.time.Instant

@JvmField
val Adapters = setOf(
        SnippetTransformationErrorAdapter(),
        SnippetTransformationFailedAdapter(),
        CompilationFailedAdapter(),
        CheckstyleFailedAdapter(),
        TemplatingErrorAdapter(),
        TemplatingFailedAdapter(),
        PermissionAdapter(),
        InstantAdapter(),
        SnippetAdapter(),
        ExecutionFailedAdapter(),
        ClassMissingExceptionAdapter(),
        MethodNotFoundExceptionAdapter()
)

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
    fun snippetTransformationErrorFromJson(snippetParseErrorJson: SnippetTransformationErrorJson): SnippetTransformationError {
        return SnippetTransformationError(snippetParseErrorJson.line, snippetParseErrorJson.column, snippetParseErrorJson.message)
    }

    @ToJson
    fun snippetTransformationErrorToJson(snippetTransformationError: SnippetTransformationError): SnippetTransformationErrorJson {
        return SnippetTransformationErrorJson(snippetTransformationError.location.line, snippetTransformationError.location.column, snippetTransformationError.message)
    }
}

@JsonClass(generateAdapter = true)
data class SnippetTransformationFailedJson(val errors: List<SnippetTransformationError>)
class SnippetTransformationFailedAdapter {
    @FromJson
    fun snippetParsingFailedFromJson(snippetParsingFailedJson: SnippetTransformationFailedJson): SnippetTransformationFailed {
        return SnippetTransformationFailed(snippetParsingFailedJson.errors)
    }

    @Suppress("UNCHECKED_CAST")
    @ToJson
    fun snippetParsingFailedToJson(snippetTransformationFailed: SnippetTransformationFailed): SnippetTransformationFailedJson {
        return SnippetTransformationFailedJson(snippetTransformationFailed.errors as List<SnippetTransformationError>)
    }
}

@JsonClass(generateAdapter = true)
data class TemplatingErrorJson(val name: String, val line: Int, val column: Int, val message: String)
class TemplatingErrorAdapter {
    @FromJson
    fun templatingErrorFromJson(templatingErrorJson: TemplatingErrorJson): TemplatingError {
        return TemplatingError(templatingErrorJson.name, templatingErrorJson.line, templatingErrorJson.column, templatingErrorJson.message)
    }

    @ToJson
    fun templatingErrorToJson(templatingError: TemplatingError): TemplatingErrorJson {
        return TemplatingErrorJson(templatingError.location.source, templatingError.location.line, templatingError.location.column, templatingError.message)
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
data class ExecutionFailedJson(
        val classNotFound: ExecutionFailed.ClassMissingException?,
        val methodNotFound: ExecutionFailed.MethodNotFoundException?,
        val threw: String?
)
class ExecutionFailedAdapter {
    @Throws(Exception::class)
    @Suppress("UNUSED_PARAMETER")
    @FromJson
    fun executionFailedFromJson(unused: ExecutionFailedJson): ExecutionFailed {
        throw Exception("Can't convert JSON to ExecutionFailed")
    }

    @ToJson
    fun executionFailedToJson(executionFailed: ExecutionFailed): ExecutionFailedJson {
        return ExecutionFailedJson(executionFailed.classNotFound, executionFailed.methodNotFound, executionFailed.threw)
    }
}

@JsonClass(generateAdapter = true)
data class ClassMissingExceptionJson(
        val klass: String, val message: String?
)
class ClassMissingExceptionAdapter {
    @Throws(Exception::class)
    @Suppress("UNUSED_PARAMETER")
    @FromJson
    fun classMissingExceptionFromJson(unused: ClassMissingExceptionJson): ExecutionFailed.ClassMissingException {
        throw Exception("Can't convert JSON to ClassMissingException")
    }

    @ToJson
    fun classMissingExceptionToJson(classMissingException: ExecutionFailed.ClassMissingException): ClassMissingExceptionJson {
        return ClassMissingExceptionJson(classMissingException.klass, classMissingException.message)
    }
}

@JsonClass(generateAdapter = true)
data class MethodNotFoundExceptionJson(
        val method: String, val message: String?
)
class MethodNotFoundExceptionAdapter {
    @Throws(Exception::class)
    @Suppress("UNUSED_PARAMETER")
    @FromJson
    fun methodNotFoundExceptionFromJson(unused: MethodNotFoundExceptionJson): ExecutionFailed.MethodNotFoundException {
        throw Exception("Can't convert JSON to MethodNotFoundException")
    }

    @ToJson
    fun methodNotFoundExceptionToJson(methodNotFoundException: ExecutionFailed.MethodNotFoundException): MethodNotFoundExceptionJson {
        return MethodNotFoundExceptionJson(methodNotFoundException.method, methodNotFoundException.message)
    }
}