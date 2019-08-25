package edu.illinois.cs.cs125.jeed.core

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ReflectPermission
import java.security.Permission

class SourceExecutionArguments(
        val klass: String = DEFAULT_KLASS,
        val method: String = DEFAULT_METHOD,
        timeout: Long = DEFAULT_TIMEOUT,
        permissions: Set<Permission> = REQUIRED_PERMISSIONS,
        maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS,
        maxOutputLines: Int = DEFAULT_MAX_OUTPUT_LINES,
        classLoaderConfiguration: Sandbox.ClassLoaderConfiguration = Sandbox.ClassLoaderConfiguration()
): Sandbox.ExecutionArguments(
        timeout,
        permissions.union(REQUIRED_PERMISSIONS),
        maxExtraThreads,
        maxOutputLines,
        classLoaderConfiguration
) {
    companion object {
        const val DEFAULT_KLASS = "Main"
        const val DEFAULT_METHOD = "main()"
        val REQUIRED_PERMISSIONS = setOf(
                RuntimePermission("accessDeclaredMembers"),
                ReflectPermission("suppressAccessChecks")
        )
    }
}

class MethodNotFoundException(message: String) : Exception(message)
@Throws(ClassNotFoundException::class, MethodNotFoundException::class)
suspend fun CompiledSource.execute(
      executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): Sandbox.TaskResults<out Any?> {
    // Fail fast if the class or method don't exist
    classLoader.findClassMethod(executionArguments.klass, executionArguments.method)

    return Sandbox.execute(classLoader, executionArguments) { (classLoader) ->
        try {
            classLoader.findClassMethod(executionArguments.klass, executionArguments.method).invoke(null)
        } catch (e: InvocationTargetException) {
            throw(e.cause ?: e)
        }
    }
}

@Throws(ClassNotFoundException::class, MethodNotFoundException::class)
fun ClassLoader.findClassMethod(
        klass: String = SourceExecutionArguments.DEFAULT_KLASS,
        name: String = SourceExecutionArguments.DEFAULT_METHOD
): Method {
    return loadClass(klass).declaredMethods.find { method ->
        if (!Modifier.isStatic(method.modifiers)
                || !Modifier.isPublic(method.modifiers)
                || method.parameterTypes.isNotEmpty()) {
            return@find false
        }
        method.getQualifiedName() == name
    } ?: throw MethodNotFoundException("Cannot locate public static no-argument method $name in $klass")
}
