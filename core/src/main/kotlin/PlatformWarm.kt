package edu.illinois.cs.cs125.jeed.core

import java.lang.invoke.LambdaMetafactory
import java.lang.invoke.StringConcatFactory
import java.lang.runtime.ObjectMethods
import java.util.concurrent.ForkJoinWorkerThread

/*
 * Static members in libraries are a challenge for confined task isolation. Third-party libraries can be reloaded,
 * but JDK (platform) classes cannot. It is important that classes with JVM-wide state are initialized outside the
 * sandbox; if they are initialized by a confined task, they may hold references to that task or be interrupted
 * in the middle of their initialization, thereby ruining the class for the entire JVM. This method, called before
 * any confined tasks are started, initializes important shared platform classes.
 */
internal fun warmPlatform() {
    // Records are supported by a bootstrap method on ObjectMethods
    ensureInitialized(ObjectMethods::class.java)

    // Lambdas by LambdaMetafactory (very likely already initialized by the JVM, but it doesn't hurt to make sure)
    ensureInitialized(LambdaMetafactory::class.java)

    // Some string concatenation by StringConcatFactory (again likely already initialized)
    ensureInitialized(StringConcatFactory::class.java)

    // Parallel streams in the sandbox by InnocuousForkJoinWorkerThread
    ensureInitialized(ForkJoinWorkerThread::class.java)
    Class.forName("java.util.concurrent.ForkJoinWorkerThread\$InnocuousForkJoinWorkerThread")
}

private fun ensureInitialized(klass: Class<*>) {
    Class.forName(klass.name)
}
