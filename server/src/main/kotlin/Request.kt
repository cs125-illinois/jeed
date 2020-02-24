package edu.illinois.cs.cs125.jeed.server

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.mongodb.client.MongoCollection
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.ComplexityFailed
import edu.illinois.cs.cs125.jeed.core.ExecutionFailed
import edu.illinois.cs.cs125.jeed.core.Interval
import edu.illinois.cs.cs125.jeed.core.SnippetTransformationFailed
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jeed.core.checkstyle
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.complexity
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.jeed.core.fromTemplates
import edu.illinois.cs.cs125.jeed.core.kompile
import edu.illinois.cs.cs125.jeed.core.moshi.CompiledSourceResult
import edu.illinois.cs.cs125.jeed.core.moshi.ExecutionFailedResult
import edu.illinois.cs.cs125.jeed.core.moshi.PermissionAdapter
import edu.illinois.cs.cs125.jeed.core.moshi.SourceTaskResults
import edu.illinois.cs.cs125.jeed.core.moshi.TemplatedSourceResult
import edu.illinois.cs.cs125.jeed.core.server.FlatComplexityResults
import edu.illinois.cs.cs125.jeed.core.server.Task
import edu.illinois.cs.cs125.jeed.core.server.TaskArguments
import java.time.Instant
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.apache.http.auth.AuthenticationException
import org.bson.BsonDocument
import org.bson.BsonString

class Request(
    val source: Map<String, String>?,
    val templates: Map<String, String>?,
    val snippet: String?,
    passedTasks: Set<Task>,
    arguments: TaskArguments?,
    @Suppress("MemberVisibilityCanBePrivate") val authToken: String?,
    val label: String,
    val waitForSave: Boolean = false,
    val requireSave: Boolean = true
) {
    val tasks: Set<Task>
    val arguments = arguments ?: TaskArguments()

    var email: String? = null

    init {
        val tasksToRun = passedTasks.toMutableSet()

        require(!(source != null && snippet != null)) { "can't create task with both sources and snippet" }
        require(source != null || snippet != null) { "must provide either sources or a snippet" }

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
            require(tasksToRun.contains(Task.compile) || tasksToRun.contains(Task.kompile)) {
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
    }

    fun check(): Request {
        @Suppress("MaxLineLength")
        if (Task.execute in tasks) {
            require(arguments.execution.timeout <= configuration[Limits.Execution.timeout]) {
                    "job timeout of ${arguments.execution.timeout} too long (> ${configuration[Limits.Execution.timeout]})"
            }
            require(arguments.execution.maxExtraThreads <= configuration[Limits.Execution.maxExtraThreads]) {
                    "job maxExtraThreads of ${arguments.execution.maxExtraThreads} is too large (> ${configuration[Limits.Execution.maxExtraThreads]}"
            }
            require(arguments.execution.maxOutputLines <= configuration[Limits.Execution.maxOutputLines]) {
                "job maxOutputLines of ${arguments.execution.maxOutputLines} is too large (> ${configuration[Limits.Execution.maxOutputLines]}"
            }
            val allowedPermissions =
                configuration[Limits.Execution.permissions].map { PermissionAdapter().permissionFromJson(it) }
                    .toSet()
            require(allowedPermissions.containsAll(arguments.execution.permissions)) {
                "job is requesting unavailable permissions: ${arguments.execution.permissions}"
            }

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
        return this
    }

    @Suppress("ComplexMethod", "NestedBlockDepth")
    fun authenticate() {
        @Suppress("EmptyCatchBlock", "TooGenericExceptionCaught")
        try {
            if (googleTokenVerifier != null && authToken != null) {
                googleTokenVerifier?.verify(authToken)?.let {
                    if (configuration[Auth.Google.hostedDomain] != null) {
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

    @Suppress("ComplexMethod", "LongMethod")
    suspend fun run(): Response {
        currentStatus.counts.submitted++

        val started = Instant.now()

        val response = Response(this)
        @Suppress("TooGenericExceptionCaught")
        try {
            val actualSource = if (source != null) {
                if (templates == null) {
                    Source(source)
                } else {
                    Source.fromTemplates(source, templates).also {
                        response.completedTasks.add(Task.template)
                        response.completed.template = TemplatedSourceResult(it)
                    }
                }
            } else {
                arguments.snippet.fileType = if (tasks.contains(Task.kompile)) {
                    Source.FileType.KOTLIN
                } else if (tasks.contains(Task.compile)) {
                    Source.FileType.JAVA
                } else {
                    arguments.snippet.fileType
                }
                Source.fromSnippet(snippet ?: assert { "should have a snippet" }, arguments.snippet).also {
                    response.completedTasks.add(Task.snippet)
                    response.completed.snippet = it
                }
            }

            val compiledSource = when {
                tasks.contains(Task.compile) -> {
                    actualSource.compile(arguments.compilation).also {
                        response.completed.compilation = CompiledSourceResult(it)
                        response.completedTasks.add(Task.compile)
                    }
                }
                tasks.contains(Task.kompile) -> {
                    actualSource.kompile(arguments.kompilation).also {
                        response.completed.kompilation = CompiledSourceResult(it)
                        response.completedTasks.add(Task.kompile)
                    }
                }
                else -> {
                    null
                }
            }

            if (tasks.contains(Task.checkstyle)) {
                check(actualSource.type == Source.FileType.JAVA) { "can't run checkstyle on non-Java sources" }
                response.completed.checkstyle = actualSource.checkstyle(arguments.checkstyle)
                response.completedTasks.add(Task.checkstyle)
            }

            if (tasks.contains(Task.complexity)) {
                check(actualSource.type == Source.FileType.JAVA) { "can't run complexity on non-Java sources" }
                response.completed.complexity = FlatComplexityResults(actualSource.complexity())
                response.completedTasks.add(Task.complexity)
            }

            if (tasks.contains(Task.execute)) {
                check(compiledSource != null) { "should have compiled source before executing" }
                val executionResult = compiledSource.execute(arguments.execution)
                if (executionResult.threw != null) {
                    response.failed.execution = ExecutionFailedResult(ExecutionFailed(executionResult.threw!!))
                    response.failedTasks.add(Task.execute)
                } else {
                    response.completed.execution = SourceTaskResults(executionResult, arguments.execution)
                    response.completedTasks.add(Task.execute)
                }
            }
        } catch (templatingFailed: TemplatingFailed) {
            response.failed.template = templatingFailed
            response.failedTasks.add(Task.template)
        } catch (snippetFailed: SnippetTransformationFailed) {
            response.failed.snippet = snippetFailed
            response.failedTasks.add(Task.snippet)
        } catch (compilationFailed: CompilationFailed) {
            if (tasks.contains(Task.compile)) {
                response.failed.compilation = compilationFailed
                response.failedTasks.add(Task.compile)
            } else if (tasks.contains(Task.kompile)) {
                response.failed.kompilation = compilationFailed
                response.failedTasks.add(Task.kompile)
            }
        } catch (checkstyleFailed: CheckstyleFailed) {
            response.failed.checkstyle = checkstyleFailed
            response.failedTasks.add(Task.checkstyle)
        } catch (complexityFailed: ComplexityFailed) {
            response.failed.complexity = complexityFailed
            response.failedTasks.add(Task.complexity)
        } catch (executionFailed: ExecutionFailed) {
            response.failed.execution = ExecutionFailedResult(executionFailed)
            response.failedTasks.add(Task.execute)
        } finally {
            currentStatus.counts.completed++
            response.interval = Interval(started, Instant.now())
        }
        if (mongoCollection != null) {
            val resultSave = GlobalScope.async {
                @Suppress("TooGenericExceptionCaught")
                try {
                    mongoCollection?.insertOne(BsonDocument.parse(response.json).also {
                        it.append("receivedSemester", BsonString(configuration[TopLevel.semester]))
                    })
                    currentStatus.counts.saved++
                } catch (e: Exception) {
                    logger.error("Saving job failed: $e")
                    if (requireSave) {
                        throw(e)
                    } else {
                        null
                    }
                }
            }
            if (waitForSave || requireSave) {
                resultSave.await()
            }
        }
        return response
    }

    companion object {
        var mongoCollection: MongoCollection<BsonDocument>? = null
        var googleTokenVerifier: GoogleIdTokenVerifier? = null
    }
}
