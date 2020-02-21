package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ReflectPermission
import java.security.Permission
import java.util.PropertyPermission

@JsonClass(generateAdapter = true)
class SourceExecutionArguments(
    var klass: String? = null,
    val method: String = DEFAULT_METHOD,
    timeout: Long = DEFAULT_TIMEOUT,
    permissions: Set<Permission> = DEFAULT_EXTRA_PERMISSIONS,
    maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS,
    maxOutputLines: Int = DEFAULT_MAX_OUTPUT_LINES,
    classLoaderConfiguration: Sandbox.ClassLoaderConfiguration = Sandbox.ClassLoaderConfiguration()
) : Sandbox.ExecutionArguments(
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
        val DEFAULT_EXTRA_PERMISSIONS = setOf(
            PropertyPermission("java.specification.version", "read")
        )
    }
}

class ExecutionFailed(
    val classNotFound: ClassMissingException? = null,
    val methodNotFound: MethodNotFoundException? = null,
    @Suppress("unused") val threw: String? = null
) : Exception() {
    class ClassMissingException(@Suppress("unused") val klass: String, message: String?) : Exception(message)
    class MethodNotFoundException(@Suppress("unused") val method: String, message: String?) : Exception(message)

    constructor(classMissing: ClassMissingException) : this(classMissing, null, null)
    constructor(methodNotFound: MethodNotFoundException) : this(null, methodNotFound, null)
    constructor(throwable: Throwable) : this(null, null, throwable.getStackTraceAsString())
}

@Throws(ExecutionFailed::class)
suspend fun CompiledSource.execute(
    executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): Sandbox.TaskResults<out Any?> {
    if (executionArguments.klass == null) {
        executionArguments.klass = when (this.source.type) {
            Source.FileType.JAVA -> "Main"
            Source.FileType.KOTLIN -> "MainKt"
        }
    }

    // Fail fast if the class or method don't exist
    classLoader.findClassMethod(executionArguments.klass!!, executionArguments.method)

    return Sandbox.execute(classLoader, executionArguments) { (classLoader) ->
        try {
            val method = classLoader.findClassMethod(executionArguments.klass!!, executionArguments.method)
            if (method.parameterTypes.isEmpty()) {
                method.invoke(null)
            } else {
                method.invoke(null, null)
            }
        } catch (e: InvocationTargetException) {
            throw(e.cause ?: e)
        }
    }
}

@Throws(ExecutionFailed::class)
@Suppress("ThrowsCount")
fun ClassLoader.findClassMethod(
    klass: String = SourceExecutionArguments.DEFAULT_KLASS,
    name: String = SourceExecutionArguments.DEFAULT_METHOD
): Method {
    try {
        return loadClass(klass).declaredMethods.find { method ->
            @Suppress("ComplexCondition")
            if (name == "main(String[])" &&
                method.name == "main" &&
                Modifier.isStatic(method.modifiers) &&
                Modifier.isPublic(method.modifiers) &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].canonicalName == "java.lang.String[]"
            ) {
                return@find true
            }
            if (!Modifier.isStatic(method.modifiers) ||
                !Modifier.isPublic(method.modifiers) ||
                method.parameterTypes.isNotEmpty()
            ) {
                return@find false
            }
            method.getQualifiedName() == name
        } ?: throw ExecutionFailed.MethodNotFoundException(
            name,
            "Cannot locate public static no-argument method $name in $klass"
        )
    } catch (methodNotFoundException: ExecutionFailed.MethodNotFoundException) {
        throw ExecutionFailed(methodNotFoundException)
    } catch (classNotFoundException: ClassNotFoundException) {
        throw ExecutionFailed(ExecutionFailed.ClassMissingException(klass, classNotFoundException.message))
    }
}
