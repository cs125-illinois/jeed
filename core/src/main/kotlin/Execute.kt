package edu.illinois.cs.cs125.jeed.core

import java.lang.reflect.Modifier
import java.security.Permission
import java.util.concurrent.Callable

class SourceExecutionArguments(
        val klass: String = DEFAULT_KLASS,
        val method: String = DEFAULT_METHOD,
        timeout: Long = DEFAULT_TIMEOUT,
        permissions: List<Permission> = DEFAULT_PERMISSIONS,
        maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS
): Sandbox.ExecutionArguments<Any>(timeout, permissions, maxExtraThreads) {
    companion object {
        const val DEFAULT_KLASS = "Main"
        const val DEFAULT_METHOD = "main()"
    }
}

class MethodNotFoundException(message: String) : Exception(message)
@Throws(ClassNotFoundException::class, MethodNotFoundException::class)
suspend fun CompiledSource.execute(
      executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): Sandbox.TaskResults<out Any?> {
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

    return Sandbox.execute(Callable { method.invoke(null) }, classLoader, executionArguments)
}
