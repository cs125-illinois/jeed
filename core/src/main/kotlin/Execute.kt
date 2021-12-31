package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ReflectPermission
import java.security.Permission
import java.util.PropertyPermission

@JsonClass(generateAdapter = true)
@Suppress("LongParameterList")
class SourceExecutionArguments(
    var klass: String? = null,
    var method: String? = null,
    timeout: Long = DEFAULT_TIMEOUT,
    permissions: Set<Permission> = setOf(),
    maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS,
    maxOutputLines: Int = DEFAULT_MAX_OUTPUT_LINES,
    classLoaderConfiguration: Sandbox.ClassLoaderConfiguration = Sandbox.ClassLoaderConfiguration(),
    val dryRun: Boolean = false,
    waitForShutdown: Boolean = DEFAULT_WAIT_FOR_SHUTDOWN,
    @Transient
    var methodToRun: Method? = null,
    val plugins: List<SandboxPlugin<*>> = listOf()
) : Sandbox.ExecutionArguments(
    timeout,
    permissions.union(REQUIRED_PERMISSIONS),
    maxExtraThreads,
    maxOutputLines,
    classLoaderConfiguration,
    waitForShutdown
) {
    companion object {
        const val DEFAULT_KLASS = "Main"
        const val DEFAULT_METHOD = "main()"
        val REQUIRED_PERMISSIONS = setOf(
            // Why not?
            PropertyPermission("java.version", "read"),
            // Required by newer versions of Kotlin
            PropertyPermission("java.specification.version", "read"),
            PropertyPermission("kotlinx.coroutines.*", "read"),
            RuntimePermission("accessDeclaredMembers"),
            ReflectPermission("suppressAccessChecks"),
            // Required for Date to work
            RuntimePermission("localeServiceProvider"),
            // Not sure why this is required by Date, but it seems to be
            // ClassLoader enumeration is probably not unsafe...
            RuntimePermission("getClassLoader"),
        )
    }
}

class ExecutionFailed(
    val classNotFound: ClassMissingException? = null,
    val methodNotFound: MethodNotFoundException? = null
) : Exception() {
    class ClassMissingException(@Suppress("unused") val klass: String, message: String?) : Exception(message)
    class MethodNotFoundException(@Suppress("unused") val method: String, message: String?) : Exception(message)

    constructor(classMissing: ClassMissingException) : this(classMissing, null)
    constructor(methodNotFound: MethodNotFoundException) : this(null, methodNotFound)
}

fun CompiledSource.updateExecutionArguments(executionArguments: SourceExecutionArguments): SourceExecutionArguments {
    val defaultKlass = if (this.source is Snippet) {
        this.source.entryClassName
    } else {
        when (this.source.type) {
            Source.FileType.JAVA -> "Main"
            Source.FileType.KOTLIN -> "MainKt"
        }
    }

    // Coroutines need some extra time and threads to run.
    if (this.source.type == Source.FileType.KOTLIN && this.usesCoroutines()) {
        executionArguments.timeout = executionArguments.timeout.coerceAtLeast(KOTLIN_COROUTINE_MIN_TIMEOUT)
        executionArguments.maxExtraThreads =
            executionArguments.maxExtraThreads.coerceAtLeast(KOTLIN_COROUTINE_MIN_EXTRA_THREADS)
    }

    // Fail fast if the class or method don't exist
    executionArguments.methodToRun = classLoader.findClassMethod(
        executionArguments.klass,
        executionArguments.method,
        defaultKlass,
        SourceExecutionArguments.DEFAULT_METHOD
    )
    executionArguments.klass = executionArguments.klass ?: executionArguments.methodToRun!!.declaringClass.simpleName
    executionArguments.method = executionArguments.method ?: executionArguments.methodToRun!!.getQualifiedName()
    return executionArguments
}

@Throws(ExecutionFailed::class)
@Suppress("ReturnCount")
suspend fun CompiledSource.execute(
    executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): Sandbox.TaskResults<out Any?> {
    val actualArguments = updateExecutionArguments(executionArguments)
    return Sandbox.execute(classLoader, actualArguments, actualArguments.plugins) sandbox@{ (classLoader) ->
        if (actualArguments.dryRun) {
            return@sandbox null
        }
        classLoader as Sandbox.SandboxedClassLoader
        @Suppress("SpreadOperator")
        try {
            val method = classLoader
                .loadClass(executionArguments.methodToRun!!.declaringClass.name)
                .getMethod(executionArguments.methodToRun!!.name, *executionArguments.methodToRun!!.parameterTypes)
            return@sandbox if (method.parameterTypes.isEmpty()) {
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
@Suppress("ThrowsCount", "ComplexMethod")
fun ClassLoader.findClassMethod(
    klass: String? = null,
    name: String? = null,
    defaultKlass: String = SourceExecutionArguments.DEFAULT_KLASS,
    defaultMethod: String = SourceExecutionArguments.DEFAULT_METHOD
): Method {
    this as Sandbox.EnumerableClassLoader
    val klassToLoad = if (klass == null && definedClasses.size == 1) {
        definedClasses.first()
    } else {
        klass ?: defaultKlass
    }
    try {
        val loadedKlass = loadClass(klassToLoad)
        val staticNoArgMethods = loadedKlass.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty()
        }
        return if (name == null && staticNoArgMethods.size == 1) {
            staticNoArgMethods.first()
        } else {
            val nameToFind = name ?: defaultMethod
            loadedKlass.declaredMethods.filter {
                Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) &&
                    (
                        it.parameterTypes.isEmpty() ||
                            (it.parameterTypes.size == 1 && it.parameterTypes[0].canonicalName == "java.lang.String[]")
                        )
            }.find { method ->
                @Suppress("ComplexCondition")
                return@find if (method.getQualifiedName() == nameToFind) {
                    true
                } else {
                    method.name == "main" && (nameToFind == "main()" || nameToFind == "main(String[])")
                }
            } ?: throw ExecutionFailed.MethodNotFoundException(
                nameToFind,
                "Cannot locate public static no-argument method $name in $klassToLoad"
            )
        }
    } catch (methodNotFoundException: ExecutionFailed.MethodNotFoundException) {
        throw ExecutionFailed(methodNotFoundException)
    } catch (classNotFoundException: ClassNotFoundException) {
        throw ExecutionFailed(ExecutionFailed.ClassMissingException(klassToLoad, classNotFoundException.message))
    }
}
