package edu.illinois.cs.cs125.jeed.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import java.io.FilePermission
import java.io.OutputStream
import java.io.PrintStream
import java.security.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

object Sandbox {
    open class ExecutionArguments<T>(
            val timeout: Long = DEFAULT_TIMEOUT,
            val permissions: Set<Permission> = setOf(),
            val maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS
    ) {
        companion object {
            const val DEFAULT_TIMEOUT = 100L
            const val DEFAULT_MAX_EXTRA_THREADS = 0
        }
    }

    class TaskResults<T>(
            val returned: T?,
            val threw: Throwable?,
            val timeout: Boolean,
            val outputLines: MutableList<OutputLine> = mutableListOf(),
            val permissionRequests: MutableList<PermissionRequest> = mutableListOf(),
            val interval: Interval,
            val executionInterval: Interval
    ) {
        data class OutputLine (val console: Console, val line: String, val timestamp: Instant, val thread: Long) {
            enum class Console(val fd: Int) { STDOUT(1), STDERR(2) }
        }
        data class PermissionRequest(val permission: Permission, val granted: Boolean)

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
            get() { return Duration.between(interval.start, interval.end) }
    }

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
    suspend fun <T> execute(
            callable: Callable<T>,
            classLoader: ByteCodeProvidingClassLoader,
            executionArguments: ExecutionArguments<T>
    ): TaskResults<out T?> {
        require(executionArguments.permissions.intersect(blacklistedPermissions).isEmpty()) { "attempt to allow unsafe permissions" }

        return coroutineScope {
            val resultsChannel = Channel<ExecutorResult<T>>()
            val task = FutureTask<T>(callable)
            val executor = Executor(task, classLoader, executionArguments, resultsChannel)
            threadPool.submit(executor)
            val result = executor.resultChannel.receive()
            result.taskResults ?: throw result.executionException
        }
    }

    private const val MAX_THREAD_SHUTDOWN_RETRIES = 10240
    private const val THREADGROUP_SHUTDOWN_DELAY = 10L


    private val threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) ?: error("thread pool should be available")
    private data class ExecutorResult<T>(val taskResults: TaskResults<T>?, val executionException: Throwable)
    private class Executor<T>(
            val task: FutureTask<T>,
            val classLoader: ByteCodeProvidingClassLoader,
            val executionArguments: ExecutionArguments<T>,
            val resultChannel: Channel<ExecutorResult<T>>
    ): Callable<Any> {
        private data class TaskResult<T>(val returned: T, val threw: Throwable? = null, val timeout: Boolean = false)

        override fun call() {
            try {
                val started = Instant.now()

                val threadGroup = ThreadGroup("Sandbox")
                threadGroup.maxPriority = Thread.MIN_PRIORITY
                val thread = Thread(threadGroup, task)
                thread.contextClassLoader = classLoader as ClassLoader

                confine(threadGroup, classLoader, executionArguments)
                val executionStarted = Instant.now()
                val taskResult = try {
                    thread.start()
                    TaskResult(task.get(executionArguments.timeout, TimeUnit.MILLISECONDS))
                } catch (e: TimeoutException) {
                    task.cancel(true)
                    @Suppress("DEPRECATION")
                    thread.stop()
                    TaskResult(null, null, true)
                } catch (e: Throwable) {
                    TaskResult(null, e.cause)
                }
                val executionEnded = Instant.now()
                val sandboxResults = release(threadGroup)

                val executionResult = TaskResults<T>(
                        taskResult.returned, taskResult.threw, taskResult.timeout,
                        sandboxResults.outputLines,
                        sandboxResults.permissionRequests,
                        Interval(started, Instant.now()),
                        Interval(executionStarted, executionEnded)
                )
                runBlocking { resultChannel.send(ExecutorResult(executionResult, Exception())) }
            } catch (e: Throwable) {
                runBlocking { resultChannel.send(ExecutorResult(null, e)) }
            } finally {
                resultChannel.close()
            }
        }
    }

    private class ConfinedTask (
            val classLoader: ByteCodeProvidingClassLoader,
            executionArguments: ExecutionArguments<*>
    ) {
        val accessControlContext: AccessControlContext
        init {
            val permissions = Permissions()
            executionArguments.permissions.forEach { permissions.add(it) }
            accessControlContext = AccessControlContext(arrayOf(ProtectionDomain(null, permissions)))
        }
        val maxExtraThreads: Int = executionArguments.maxExtraThreads

        var shuttingDown: Boolean = false
        val currentLines: MutableMap<TaskResults.OutputLine.Console, CurrentLine> = mutableMapOf()
        val outputLines: MutableList<TaskResults.OutputLine> = mutableListOf()
        val permissionRequests: MutableList<TaskResults.PermissionRequest> = mutableListOf()

        data class CurrentLine(
                var started: Instant = Instant.now(),
                val line: StringBuilder = StringBuilder(),
                val startedThread: Long = Thread.currentThread().id
        )
        lateinit var interval: Interval
        lateinit var executionInterval: Interval

        fun addPermissionRequest(permission: Permission, granted: Boolean) {
            permissionRequests.add(TaskResults.PermissionRequest(permission, granted))
            if (!granted) {
                throw SecurityException()
            }
        }
    }

    private val confinedTasks: MutableMap<ThreadGroup, ConfinedTask> = mutableMapOf()
    private fun confinedTaskByThreadGroup(trapIfShuttingDown: Boolean = true): ConfinedTask? {
        val confinedTask = confinedTasks[Thread.currentThread().threadGroup] ?: return null
        if (confinedTask.shuttingDown && trapIfShuttingDown) { while (true) { Thread.sleep(Long.MAX_VALUE) } }
        return confinedTask
    }

    private fun confine(
            threadGroup: ThreadGroup,
            classLoader: ByteCodeProvidingClassLoader,
            executionArguments: ExecutionArguments<*>
    ) {
        require(!confinedTasks.containsKey(threadGroup)) { "thread group is already confined" }
        confinedTasks[threadGroup] = ConfinedTask(classLoader, executionArguments)
    }
    private fun release(threadGroup: ThreadGroup): ConfinedTask {
        require(confinedTasks.containsKey(threadGroup)) { "thread group is not confined" }
        val confinedTask = confinedTasks[threadGroup] ?: error("thread group is not confined")

        confinedTask.shuttingDown = true

        if (threadGroup.activeGroupCount() > 0) {
            val threadGroups = Array<ThreadGroup?>(threadGroup.activeGroupCount()) { null }
            threadGroup.enumerate(threadGroups, true)
            assert(threadGroups.toList().filterNotNull().map { it.activeCount() }.sum() == 0)
        }

        (0..MAX_THREAD_SHUTDOWN_RETRIES).find {
            return@find if (threadGroup.activeCount() == 0) {
                true
            } else {
                @Suppress("DEPRECATION") threadGroup.stop()
                Thread.sleep(THREADGROUP_SHUTDOWN_DELAY)
                false
            }
        }

        threadGroup.destroy()
        assert(threadGroup.isDestroyed)

        for (console in TaskResults.OutputLine.Console.values()) {
            val currentLine = confinedTask.currentLines[console] ?: continue
            if (currentLine.line.isNotEmpty()) {
                confinedTask.outputLines.add(
                        TaskResults.OutputLine(
                                console,
                                currentLine.line.toString(),
                                currentLine.started,
                                currentLine.startedThread
                        )
                )
            }
        }

        confinedTasks.remove(threadGroup)
        return confinedTask
    }

    val systemSecurityManager: SecurityManager? = System.getSecurityManager()

    private object SandboxSecurityManager : SecurityManager() {
        private fun confinedTaskByClassLoader(trapIfShuttingDown: Boolean = true): ConfinedTask? {
            val confinedTask = confinedTaskByThreadGroup(trapIfShuttingDown) ?: return null
            val classIsConfined = classContext.toList().subList(1, classContext.size).reversed().any { klass ->
                klass.classLoader == confinedTask.classLoader
            }
            return if (classIsConfined) confinedTask else null
        }
        override fun checkRead(file: String) {
            val confinedTask = confinedTaskByClassLoader()
                    ?: return systemSecurityManager?.checkRead(file) ?: return
            if (!file.endsWith(".class")) {
                confinedTask.addPermissionRequest(FilePermission(file, "read"), false)
            } else {
                systemSecurityManager?.checkRead(file)
            }
        }
        override fun checkAccess(thread: Thread) {
            val confinedTask = confinedTaskByThreadGroup()
                    ?: return systemSecurityManager?.checkAccess(thread) ?: return
            if (thread.threadGroup != Thread.currentThread().threadGroup) {
                confinedTask.addPermissionRequest(RuntimePermission("changeThreadGroup"), false)
            } else {
                systemSecurityManager?.checkAccess(thread)
            }
        }
        override fun checkAccess(threadGroup: ThreadGroup) {
            val confinedTask = confinedTaskByThreadGroup()
                    ?: return systemSecurityManager?.checkAccess(threadGroup) ?: return
            if (threadGroup != Thread.currentThread().threadGroup) {
                confinedTask.addPermissionRequest(RuntimePermission("changeThreadGroup"), false)
            } else {
                systemSecurityManager?.checkAccess(threadGroup)
            }
        }
        override fun getThreadGroup(): ThreadGroup {
            val threadGroup = Thread.currentThread().threadGroup
            val confinedTask = confinedTaskByThreadGroup()
                    ?: return systemSecurityManager?.threadGroup ?: return threadGroup
            if (Thread.currentThread().threadGroup.activeCount() >= confinedTask.maxExtraThreads + 1) {
                confinedTask.permissionRequests.add(TaskResults.PermissionRequest(RuntimePermission("exceedThreadLimit"), false))
                throw SecurityException()
            } else {
                return systemSecurityManager?.threadGroup ?: threadGroup
            }
        }
        override fun checkPermission(permission: Permission) {
            val confinedTask = confinedTaskByClassLoader()
                    ?: return systemSecurityManager?.checkPermission(permission) ?: return
            try {
                confinedTask.accessControlContext.checkPermission(permission)
                confinedTask.permissionRequests.add(TaskResults.PermissionRequest(permission, true))
            } catch (e: SecurityException) {
                confinedTask.permissionRequests.add(TaskResults.PermissionRequest(permission, false))
                throw e
            }
            systemSecurityManager?.checkPermission(permission)
        }
    }
    init {
        System.setSecurityManager(SandboxSecurityManager)
    }

    private fun redirectedWrite(int: Int, console: TaskResults.OutputLine.Console) {
        val confinedTask = confinedTaskByThreadGroup() ?: return defaultWrite(int, console)

        val currentLine = confinedTask.currentLines.getOrPut(console, { ConfinedTask.CurrentLine() })
        when (val char = int.toChar()) {
            '\n' -> {
                confinedTask.outputLines.add(
                        TaskResults.OutputLine(
                                console,
                                currentLine.line.toString(),
                                currentLine.started,
                                currentLine.startedThread
                        )
                )
                confinedTask.currentLines.remove(console)
            }
            else -> {
                currentLine.line.append(char)
            }
        }
    }
    fun defaultWrite(int: Int, console: TaskResults.OutputLine.Console) {
        when (console) {
            TaskResults.OutputLine.Console.STDOUT -> originalStdout.write(int)
            TaskResults.OutputLine.Console.STDERR -> originalStderr.write(int)
        }
    }

    private var originalStdout = System.out
    private var originalStderr = System.err
    init {
        System.setOut(PrintStream(object : OutputStream() {
            override fun write(int: Int) { redirectedWrite(int, TaskResults.OutputLine.Console.STDOUT) }
        }))
        System.setErr(PrintStream(object : OutputStream() {
            override fun write(int: Int) { redirectedWrite(int, TaskResults.OutputLine.Console.STDERR) }
        }))
    }
}
