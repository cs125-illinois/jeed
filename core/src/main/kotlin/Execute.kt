package edu.illinois.cs.cs125.jeed.core

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
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

@Throws(ClassNotFoundException::class, MethodNotFoundException::class)
fun ClassLoader.findClassMethod(klass: String, name: String): Method {
    return loadClass(klass).declaredMethods.find { method ->
        if (!Modifier.isStatic(method.modifiers)
                || !Modifier.isPublic(method.modifiers)
                || method.parameterTypes.isNotEmpty()) {
            return@find false
        }
        method.getQualifiedName() == name
    } ?: throw MethodNotFoundException(
            "Cannot locate public static no-argument method ${name} in ${klass}"
    )
}

class MethodNotFoundException(message: String) : Exception(message)
@Throws(ClassNotFoundException::class, MethodNotFoundException::class)
suspend fun CompiledSource.execute(
      executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): Sandbox.TaskResults<out Any?> {
    // Fail fast if the class or method don't exist
    classLoader.findClassMethod(executionArguments.klass, executionArguments.method)

    return Sandbox.execute(Callable {
        try {
            Thread.currentThread()
                    .contextClassLoader
                    .findClassMethod(executionArguments.klass, executionArguments.method)
                    .invoke(null)
        } catch (e: InvocationTargetException) {
            throw(e.cause ?: e)
        }
    }, classLoader, executionArguments)
}
