package edu.illinois.cs.cs125.jeed.server

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.mongodb.client.MongoCollection
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import edu.illinois.cs.cs125.jeed.core.*
import edu.illinois.cs.cs125.jeed.core.moshi.PermissionAdapter
import edu.illinois.cs.cs125.jeed.server.moshi.Adapters
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters
import kotlinx.coroutines.*
import org.apache.http.auth.AuthenticationException
import org.bson.BsonDocument
import java.lang.IllegalArgumentException
import java.time.Instant

@Suppress("EnumEntryName")
enum class Task {
    template,
    snippet,
    compile,
    kompile,
    checkstyle,
    execute
}
class TaskArguments(
        val snippet: SnippetArguments = SnippetArguments(),
        val compilation: CompilationArguments = CompilationArguments(),
        val kompilation: KompilationArguments = KompilationArguments(),
        val checkstyle: CheckstyleArguments = CheckstyleArguments(),
        val execution: SourceExecutionArguments = SourceExecutionArguments()
)

class Job(
        val source: Map<String, String>?,
        val templates: Map<String, String>?,
        val snippet: String?,
        passedTasks: Set<Task>,
        arguments: TaskArguments?,
        @Suppress("MemberVisibilityCanBePrivate") val authToken: String?,
        val label: String,
        val waitForSave: Boolean = false
) {
    val tasks: Set<Task>
    val arguments = arguments ?: TaskArguments()

    var email: String? = null

    init {
        val tasksToRun = passedTasks.toMutableSet()

        require(!(source != null && snippet != null)) { "can't create task with both sources and snippet" }
        if (templates != null) {
            require(source != null) { "can't use both templates and snippet mode" }
        }
        if (source != null) {
            val fileTypes = Source.filenamesToFileTypes(source.keys)
            require(fileTypes.size == 1) { "can't compile mixed Java and Kotlin sources" }
            if (tasksToRun.contains(Task.checkstyle)) {
                require(fileTypes[0] == Source.FileType.JAVA) { "can't run checkstyle on Kotlin sources" }
            }
        }

        if (tasksToRun.contains(Task.execute)) {
            require (tasksToRun.contains(Task.compile) || tasksToRun.contains(Task.kompile)) {
                "must compile code before execution"
            }
        }
        require(!(tasksToRun.containsAll(setOf(Task.compile, Task.kompile)))) {
            "can't compile code as both Java and Kotlin"
        }
        if (snippet != null) {
            tasksToRun.add(Task.snippet)
        }
        if (templates != null) {
            tasksToRun.add(Task.template)
        }
        tasks = tasksToRun.toSet()

        if (Task.execute in tasks) {
            if (arguments?.execution?.timeout != null) {
                require(arguments.execution.timeout <= configuration[Limits.Execution.timeout]) {
                    "job timeout of ${arguments.execution.timeout} too long (> ${configuration[Limits.Execution.timeout]})"
                }
            }
            if (arguments?.execution?.maxExtraThreads != null) {
                require(arguments.execution.maxExtraThreads <= configuration[Limits.Execution.maxExtraThreads]) {
                    "job maxExtraThreads of ${arguments.execution.maxExtraThreads} is too large (> ${configuration[Limits.Execution.maxExtraThreads]}"
                }
            }
            if (arguments?.execution?.maxOutputLines != null) {
                require(arguments.execution.maxOutputLines <= configuration[Limits.Execution.maxOutputLines]) {
                    "job maxOutputLines of ${arguments.execution.maxOutputLines} is too large (> ${configuration[Limits.Execution.maxOutputLines]}"
                }
            }
            if (arguments?.execution?.permissions != null) {
                val allowedPermissions = configuration[Limits.Execution.permissions].map { PermissionAdapter().permissionFromJson(it) }.toSet()
                require(allowedPermissions.containsAll(arguments.execution.permissions)) {
                    "job is requesting unavailable permissions"
                }
            }
            if (arguments?.execution?.classLoaderConfiguration != null) {
                val blacklistedClasses = configuration[Limits.Execution.ClassLoaderConfiguration.blacklistedClasses]
                require(arguments.execution.classLoaderConfiguration.blacklistedClasses.containsAll(blacklistedClasses)) {
                    "job is trying to remove blacklisted classes"
                }
                val whitelistedClasses = configuration[Limits.Execution.ClassLoaderConfiguration.whitelistedClasses]
                require(arguments.execution.classLoaderConfiguration.whitelistedClasses.containsAll(whitelistedClasses)) {
                    "job is trying to add whitelisted classes"
                }
                val unsafeExceptions = configuration[Limits.Execution.ClassLoaderConfiguration.unsafeExceptions]
                require(arguments.execution.classLoaderConfiguration.unsafeExceptions.containsAll(unsafeExceptions)) {
                    "job is trying to remove unsafe exceptions"
                }
            }
        }
    }

    fun authenticate() {
        try {
            if (googleTokenVerifier != null && authToken != null) {
                googleTokenVerifier?.verify(authToken)?.let {
                    if (configuration[Auth.Google.hostedDomain] != "") {
                        require(it.payload.hostedDomain == configuration[Auth.Google.hostedDomain])
                    }
                    email = it.payload.email
                }
            }
        } catch (e: IllegalArgumentException) {
        } catch (e: Exception) {
            logger.warn(e.toString())
        }

        if (email == null && !configuration[Auth.none]) {
            val message = if (authToken == null) {
                "authentication required by authentication token missing"
            } else {
                "authentication failure"
            }
            throw AuthenticationException(message)
        }
    }

    suspend fun run(): Result {
        currentStatus.counts.submittedJobs++

        val started = Instant.now()

        val result = Result(this)
        try {
            val actualSource = if (source != null) {
                if (templates == null) {
                    Source(source)
                } else {
                    Source.fromTemplates(source, templates).also { result.completed.template = it }
                }
            } else {
                result.completed.snippet = Source.transformSnippet(snippet ?: assert { "should have a snippet" }, arguments.snippet)
                result.completed.snippet
            } ?: check { "should have a source" }

            if (tasks.contains(Task.compile)) {
                result.completed.compilation = actualSource.compile(arguments.compilation)
            } else {
                result.completed.kompilation = actualSource.kompile(arguments.kompilation)
            }

            if (tasks.contains(Task.checkstyle)) {
                check(actualSource.type == Source.FileType.JAVA) { "can't run checkstyle on non-Java sources" }
                result.completed.checkstyle = actualSource.checkstyle(arguments.checkstyle)
            }

            if (tasks.contains(Task.execute)) {
                if (tasks.contains(Task.compile)) {
                    result.completed.execution = result.completed.compilation?.execute(arguments.execution)
                } else if (tasks.contains(Task.kompile)) {
                    result.completed.execution = result.completed.kompilation?.execute(arguments.execution)
                }
                if (result.completed.execution?.threw != null) {
                    result.failed.execution = ExecutionFailed(result.completed.execution!!.threw!!)
                }
            }
        } catch (templatingFailed: TemplatingFailed) {
            result.failed.template = templatingFailed
        } catch (snippetFailed: SnippetTransformationFailed) {
            result.failed.snippet = snippetFailed
        } catch (compilationFailed: CompilationFailed) {
            if (tasks.contains(Task.compile)) {
                result.failed.compilation = compilationFailed
            } else if (tasks.contains(Task.kompile)) {
                result.failed.kompilation = compilationFailed
            }
        } catch (checkstyleFailed: CheckstyleFailed) {
            result.failed.checkstyle = checkstyleFailed
        } catch (executionFailed: ExecutionFailed) {
            result.failed.execution = executionFailed
        } finally {
            currentStatus.counts.completedJobs++
            result.interval = Interval(started, Instant.now())
            if (mongoCollection != null) {
                val resultSave = GlobalScope.async {
                    try {
                        mongoCollection?.insertOne(BsonDocument.parse(result.json))
                        currentStatus.counts.savedJobs++
                    } catch (e: Exception) {
                        logger.error("Saving job failed: $e")
                    }
                }
                if (waitForSave) {
                    resultSave.await()
                }
            }
            return result
        }
    }

    companion object {
        var mongoCollection: MongoCollection<BsonDocument>? = null
        var googleTokenVerifier: GoogleIdTokenVerifier? = null
    }
}

class Result(val job: Job) {
    val email = job.email
    val status = currentStatus
    val completed: CompletedTasks = CompletedTasks()
    val failed: FailedTasks = FailedTasks()
    lateinit var interval: Interval

    val json: String
        get() = resultAdapter.toJson(this)

    companion object {
        val resultAdapter: JsonAdapter<Result> = Moshi.Builder().let { builder ->
            Adapters.forEach { builder.add(it) }
            JeedAdapters.forEach { builder.add(it) }
            builder.add(KotlinJsonAdapterFactory())
            builder.build().adapter(Result::class.java)
        }
    }
}
class CompletedTasks(
        var snippet: Snippet? = null,
        var template: TemplatedSource? = null,
        var compilation: CompiledSource? = null,
        var kompilation: CompiledSource? = null,
        var checkstyle: CheckstyleResults? = null,
        var execution: Sandbox.TaskResults<out Any?>? = null
)
class FailedTasks(
        var template: TemplatingFailed? = null,
        var snippet: SnippetTransformationFailed? = null,
        var compilation: CompilationFailed? = null,
        var kompilation: CompilationFailed? = null,
        var checkstyle: CheckstyleFailed? = null,
        var execution: ExecutionFailed? = null
)

data class FlatSource(val path: String, val contents: String)
fun List<FlatSource>.toSource(): Map<String, String> {
    require(this.map { it.path }.distinct().size == this.size) { "duplicate paths in source list" }
    return this.map { it.path to it.contents }.toMap()
}
fun Map<String, String>.toFlatSources(): List<FlatSource> {
    return this.map { FlatSource(it.key, it.value) }
}
