package edu.illinois.cs.cs125.jeed.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import java.io.FilePermission
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.ReflectPermission
import java.security.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.*
import kotlin.random.Random

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

open class ExecutionArguments(
        val timeout: Long = DEFAULT_TIMEOUT,
        val permissions: List<Permission> = DEFAULT_PERMISSIONS,
        val maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS
) {
    companion object {
        const val DEFAULT_TIMEOUT = 100L
        val DEFAULT_PERMISSIONS = listOf(
                RuntimePermission("accessDeclaredMembers"),
                ReflectPermission("suppressAccessChecks")
        )
        const val DEFAULT_MAX_EXTRA_THREADS = 0
    }
}

class SourceExecutionArguments(
        val klass: String = DEFAULT_KLASS,
        val method: String = DEFAULT_METHOD,
        timeout: Long = DEFAULT_TIMEOUT,
        permissions: List<Permission> = DEFAULT_PERMISSIONS,
        maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS
): ExecutionArguments(timeout, permissions, maxExtraThreads) {
    companion object {
        const val DEFAULT_KLASS = "Main"
        const val DEFAULT_METHOD = "main()"
    }
}

class ExecutionResult<T>(
        private val taskResult: TaskResult<T>,
        val outputLines: List<OutputLine> = listOf(),
        val permissionRequests: List<PermissionRequest> = listOf(),
        val totalInterval: Interval,
        val executionInterval: Interval,
        val threadShutdownRetries: Int
) {
    data class TaskResult<T>(val returned: T, val threw: Throwable? = null, val timeout: Boolean = false)
    data class OutputLine (val console: Console, val line: String, val timestamp: Instant, val thread: Long) {
        enum class Console(val fd: Int) { STDOUT(1), STDERR(2) }
    }
    data class PermissionRequest(val permission: Permission, val granted: Boolean)

    val returned: Any?
        get() { return taskResult.returned }
    val threw: Throwable?
        get() { return taskResult.threw }
    val timeout: Boolean
        get() { return taskResult.timeout }
    val completed: Boolean
        get() { return threw == null && !timeout }
    val permissionDenied: Boolean
        get() { return permissionRequests.any { !it.granted } }
    val stdoutLines: List<OutputLine>
        get() { return outputLines.filter { it.console == OutputLine.Console.STDOUT } }
    val stderrLines: List<OutputLine>
        get() { return outputLines.filter { it.console == OutputLine.Console.STDERR } }
    val stdout: String
        get() { return stdoutLines.joinToString(separator = "\n") { it.line } }
    val stderr: String
        get() { return stderrLines.joinToString(separator = "\n") { it.line } }
    val output: String
        get() { return outputLines.sortedBy { it.timestamp }.joinToString(separator = "\n") { it.line } }
    val totalDuration: Duration
        get() { return Duration.between(totalInterval.start, totalInterval.end) }
}


class MethodNotFoundException(message: String) : Exception(message)

@Throws(ClassNotFoundException::class, MethodNotFoundException:: class)
suspend fun CompiledSource.execute(
        executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): ExecutionResult<out Any?> {

    val method = classLoader.loadClass(executionArguments.klass).declaredMethods.find { method ->
    if (!Modifier.isStatic(method.modifiers)
            || !Modifier.isPublic(method.modifiers)
            || method.parameterTypes.isNotEmpty()) {
        return@find false
    }
    method.getQualifiedName() == executionArguments.method
    } ?: throw MethodNotFoundException(
            "Cannot locate public static no-argument method ${executionArguments.method} in ${executionArguments.klass}"
    )

    return JeedExecutor.execute(FutureTask<Any> { method.invoke(null) }, executionArguments)
}

suspend fun <T> FutureTask<T>.execute(executionArguments: ExecutionArguments): ExecutionResult<out T?> {
    return JeedExecutor.execute(this, executionArguments)
}

class JeedExecutor<T>(
        val executionArguments: ExecutionArguments,
        val futureTask: FutureTask<T>
) : Callable<Any> {
    private data class ExecutorResult<T>(val returned: ExecutionResult<out T?>?, val threw: Throwable?)

    private val resultChannel = Channel<ExecutorResult<T>>()

    override fun call() {
        try {
            val started = Instant.now()

            val threadGroup = ThreadGroup("execute")
            threadGroup.maxPriority = Thread.MIN_PRIORITY
            val thread = Thread(threadGroup, futureTask)

            val key = Sandbox.confine(threadGroup, executionArguments.permissions, executionArguments.maxExtraThreads)

            val executionStarted = Instant.now()
            val taskResult = try {
                thread.start()
                ExecutionResult.TaskResult(futureTask.get(executionArguments.timeout, TimeUnit.MILLISECONDS))
            } catch (e: TimeoutException) {
                futureTask.cancel(true)
                @Suppress("DEPRECATION")
                thread.stop()
                ExecutionResult.TaskResult(null, null, true)
            } catch (e: Throwable) {
                when (e.cause) {
                    is InvocationTargetException -> ExecutionResult.TaskResult(null, e.cause?.cause)
                    else -> ExecutionResult.TaskResult(null, e)
                }
            }
            val executionEnded = Instant.now()

            // Kill off any remaining threads.
            Sandbox.shutdown(key, threadGroup)

            if (threadGroup.activeGroupCount() > 0) {
                val threadGroups = Array<ThreadGroup?>(threadGroup.activeGroupCount()) { null }
                threadGroup.enumerate(threadGroups, true)
                assert(threadGroups.toList().filterNotNull().map { it.activeCount() }.sum() == 0)
            }

            val threadShutdownRetries = if (threadGroup.activeCount() == 0) {
                0
            } else {
                (0..MAX_THREAD_SHUTDOWN_RETRIES).find {
                    if (threadGroup.activeCount() == 0) {
                        return@find true
                    }
                    val threadGroupThreads = Array<Thread?>(threadGroup.activeCount()) { null }
                    threadGroup.enumerate(threadGroupThreads, true)
                    val runningThreads = threadGroupThreads.toList().filterNotNull()
                    if (runningThreads.isEmpty()) {
                        return@find true
                    }
                    for (runningThread in runningThreads) {
                        if (!runningThread.isInterrupted) {
                            runningThread.interrupt()
                        }
                        @Suppress("DEPRECATION") runningThread.stop()
                    }
                    // The delay here may need some tuning on certain platforms. Too fast and the threads we are trying
                    // to kill don't have time to get stuck. Too slow and it takes forever.
                    Thread.sleep(threadShutdownDelay)
                    return@find false
                } ?: error("couldn't shut down thread group after $MAX_THREAD_SHUTDOWN_RETRIES retries")
            }
            threadGroup.destroy()
            assert(threadGroup.isDestroyed)

            val sandboxResults = Sandbox.release(key, threadGroup)

            val executionResult = ExecutionResult(
                    taskResult,
                    sandboxResults.lines,
                    sandboxResults.loggedRequests,
                    Interval(started, Instant.now()),
                    Interval(executionStarted, executionEnded),
                    threadShutdownRetries
            )
            runBlocking { resultChannel.send(ExecutorResult(executionResult, null)) }
        } catch (e: Throwable) {
            runBlocking { resultChannel.send(ExecutorResult(null, e)) }
        } finally {
            resultChannel.close()
        }
    }

    companion object {
        const val MAX_THREAD_SHUTDOWN_RETRIES = 10240
        const val DEFAULT_THREAD_SHUTDOWN_DELAY = 10L
        var threadShutdownDelay = DEFAULT_THREAD_SHUTDOWN_DELAY
        var threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
                ?: error("thread pool should be available")
        val blacklistedPermissions = listOf(
                // Suggestions from here: https://github.com/pro-grade/pro-grade/issues/31.
                RuntimePermission("createClassLoader"),
                RuntimePermission("accessClassInPackage.sun"),
                RuntimePermission("setSecurityManager"),
                // Required for Java Streams to work...
                // ReflectPermission("suppressAccessChecks")
                SecurityPermission("setPolicy"),
                SecurityPermission("setProperty.package.access"),
                // Other additions from here: https://docs.oracle.com/javase/7/docs/technotes/guides/security/permissions.html
                SecurityPermission("createAccessControlContext"),
                SecurityPermission("getDomainCombiner"),
                RuntimePermission("createSecurityManager"),
                RuntimePermission("exitVM"),
                RuntimePermission("shutdownHooks"),
                RuntimePermission("setIO"),
                RuntimePermission("queuePrintJob"),
                RuntimePermission("setDefaultUncaughtExceptionHandler"),
                // These are particularly important to prevent untrusted code from escaping the sandbox which is based on thread groups
                RuntimePermission("modifyThread"),
                RuntimePermission("modifyThreadGroup")
        )

        suspend fun <T> execute(futureTask: FutureTask<T>, executionArguments: ExecutionArguments): ExecutionResult<out T?> {
            return coroutineScope {
                val sourceExecutor = JeedExecutor(executionArguments, futureTask)
                threadPool.submit(sourceExecutor)
                val result = sourceExecutor.resultChannel.receive()
                result.returned ?: throw result.threw ?: error("incorrect executor result")
            }
        }

        private object Sandbox : SecurityManager() {
            private val systemSecurityManager: SecurityManager? = System.getSecurityManager()

            private var originalStdout = System.out
            private var originalStderr = System.err
            private val ourStdout = PrintStream(object : OutputStream() {
                override fun write(int: Int) { write(int, ExecutionResult.OutputLine.Console.STDOUT) }
            })
            private val ourStderr = PrintStream(object : OutputStream() {
                override fun write(int: Int) { write(int, ExecutionResult.OutputLine.Console.STDERR) }
            })

            private data class ConfinedThreadGroup(
                    val key: Long,
                    val accessControlContext: AccessControlContext,
                    val maxExtraThreadCount: Int,
                    var shuttingDown: Boolean = false,
                    val currentLines: MutableMap<ExecutionResult.OutputLine.Console, CurrentLine> = mutableMapOf(),
                    val results: ConfinedThreadGroupResults = ConfinedThreadGroupResults()
            ) {
                data class CurrentLine(
                        var started: Instant = Instant.now(),
                        val line: StringBuilder = StringBuilder(),
                        val startedThread: Long = Thread.currentThread().id
                )
            }
            data class ConfinedThreadGroupResults(
                    val lines: MutableList<ExecutionResult.OutputLine> = mutableListOf(),
                    val loggedRequests: MutableList<ExecutionResult.PermissionRequest> = mutableListOf()
            )

            private val confinedThreadGroups: MutableMap<ThreadGroup, ConfinedThreadGroup> = mutableMapOf()

            fun confine(threadGroup: ThreadGroup, permissionList: List<Permission>, maxExtraThreadCount: Int = 0): Long {
                require(!confinedThreadGroups.containsKey(threadGroup)) { "thread group is already confined" }
                require(permissionList.intersect(blacklistedPermissions).isEmpty()) { "attempt to allow unsafe permissions" }

                val permissions = Permissions()
                permissionList.forEach { permissions.add(it) }

                val key = Random.nextLong()
                confinedThreadGroups[threadGroup] = ConfinedThreadGroup(
                        key,
                        AccessControlContext(arrayOf(ProtectionDomain(null, permissions))),
                        maxExtraThreadCount
                )

                if (confinedThreadGroups.keys.size == 1) {
                    System.setSecurityManager(this)
                    originalStdout = System.out
                    originalStderr = System.err
                    System.setOut(ourStdout)
                    System.setErr(ourStderr)
                }

                return key
            }
            fun shutdown(key: Long, threadGroup: ThreadGroup) {
                val confinedThreadGroup = confinedThreadGroups[threadGroup] ?: error("thread group is not confined")
                require(key == confinedThreadGroup.key) { "invalid key" }

                confinedThreadGroup.shuttingDown = true
            }
            fun release(key: Long, threadGroup: ThreadGroup): ConfinedThreadGroupResults {
                val confinedThreadGroup = confinedThreadGroups.remove(threadGroup) ?: error("thread group is not confined")
                require(key == confinedThreadGroup.key) { "invalid key" }

                for (console in ExecutionResult.OutputLine.Console.values()) {
                    val currentLine = confinedThreadGroup.currentLines[console] ?: continue
                    if (currentLine.line.isNotEmpty()) {
                        confinedThreadGroup.results.lines.add(
                                ExecutionResult.OutputLine(
                                        console,
                                        currentLine.line.toString(),
                                        currentLine.started,
                                        currentLine.startedThread
                                )
                        )
                    }
                }

                if (confinedThreadGroups.keys.isEmpty()) {
                    System.setSecurityManager(systemSecurityManager)
                    System.setOut(originalStdout)
                    System.setErr(originalStderr)
                }

                return confinedThreadGroup.results
            }

            override fun checkRead(file: String) {
                val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup]
                        ?: return systemSecurityManager?.checkRead(file) ?: return
                if (confinedThreadGroup.shuttingDown) {
                    sleepForever()
                }

                if (!file.endsWith(".class")) {
                    confinedThreadGroup.results.loggedRequests.add(
                            ExecutionResult.PermissionRequest(FilePermission(file, "read"), false)
                    )
                    throw SecurityException()
                }
            }

            override fun checkAccess(thread: Thread) {
                val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup]
                        ?: return systemSecurityManager?.checkAccess(thread) ?: return
                if (confinedThreadGroup.shuttingDown) {
                    sleepForever()
                }

                if (thread.threadGroup != Thread.currentThread().threadGroup) {
                    throw SecurityException()
                } else {
                    return super.checkAccess(thread)
                }
            }

            override fun checkAccess(threadGroup: ThreadGroup) {
                val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup]
                        ?: return systemSecurityManager?.checkAccess(threadGroup) ?: return
                if (confinedThreadGroup.shuttingDown) {
                    sleepForever()
                }

                if (threadGroup != Thread.currentThread().threadGroup) {
                    throw SecurityException()
                } else {
                    return super.checkAccess(threadGroup)
                }
            }

            override fun getThreadGroup(): ThreadGroup {
                val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup]
                        ?: return super.getThreadGroup()
                if (confinedThreadGroup.shuttingDown) {
                    sleepForever()
                }

                if (Thread.currentThread().threadGroup.activeCount() >= confinedThreadGroup.maxExtraThreadCount + 1) {
                    throw SecurityException()
                } else {
                    return super.getThreadGroup()
                }
            }

            override fun checkPermission(permission: Permission) {
                val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup]
                        ?: return systemSecurityManager?.checkPermission(permission) ?: return
                if (confinedThreadGroup.shuttingDown) {
                    sleepForever()
                }

                try {
                    /*
                    if (permission is ReflectPermission && permission.name == "suppressAccessChecks") {
                        val callerClassLoader = classContext[3]?.classLoader
                        originalStdout.println(callerClassLoader)
                        if (callerClassLoader != null && callerClassLoader != ClassLoader.getSystemClassLoader()) {
                            throw SecurityException()
                        }
                    }
                    */
                    systemSecurityManager?.checkPermission(permission)
                    confinedThreadGroup.accessControlContext.checkPermission(permission)
                    confinedThreadGroup.results.loggedRequests.add(
                            ExecutionResult.PermissionRequest(permission, true)
                    )
                } catch (e: SecurityException) {
                    confinedThreadGroup.results.loggedRequests.add(
                            ExecutionResult.PermissionRequest(permission, false)
                    )
                    throw e
                }
            }

            fun write(int: Int, console: ExecutionResult.OutputLine.Console) {
                val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup]
                        ?: return defaultWrite(int, console)
                if (confinedThreadGroup.shuttingDown) {
                    sleepForever()
                }

                val currentLine =
                        confinedThreadGroup.currentLines.getOrPut(console, { Companion.Sandbox.ConfinedThreadGroup.CurrentLine() })
                when (val char = int.toChar()) {
                    '\n' -> {
                        confinedThreadGroup.results.lines.add(
                                ExecutionResult.OutputLine(console, currentLine.line.toString(), currentLine.started, currentLine.startedThread)
                        )
                        confinedThreadGroup.currentLines.remove(console)
                    }
                    else -> {
                        currentLine.line.append(char)
                    }
                }

                return
            }

            fun defaultWrite(int: Int, console: ExecutionResult.OutputLine.Console) {
                when (console) {
                    ExecutionResult.OutputLine.Console.STDOUT -> originalStdout.write(int)
                    ExecutionResult.OutputLine.Console.STDERR -> originalStderr.write(int)
                }
            }

            private fun sleepForever() {
                while (true) {
                    Thread.sleep(Long.MAX_VALUE)
                }
            }
        }
    }
}
