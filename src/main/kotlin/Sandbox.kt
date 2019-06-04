package edu.illinois.cs.cs125.jeed

import mu.KotlinLogging
import java.security.AccessControlContext
import java.security.Permission
import java.security.Permissions
import java.security.ProtectionDomain
import java.util.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

class Sandbox {
    companion object {
        private val confinedClassLoaders: MutableMap<ClassLoader, AccessControlContext> =
                Collections.synchronizedMap(WeakHashMap<ClassLoader, AccessControlContext>())
        init {
            val previousSecurityManager = System.getSecurityManager()
            System.setSecurityManager(object : SecurityManager() {
                override fun checkPermission(permission: Permission) {
                    previousSecurityManager?.checkPermission(permission)
                    if (confinedClassLoaders.keys.isEmpty()) {
                        return
                    }
                    classContext.toList().subList(1, classContext.size).forEach {
                        if (it == javaClass) {
                            return
                        }
                        val accessControlContext = confinedClassLoaders.get(it.classLoader) ?: return
                        accessControlContext.checkPermission(permission)
                    }
                }
            })
        }

        fun confine(classLoader: ClassLoader, permissions: Permissions) {
            confinedClassLoaders.put(
                    classLoader,
                    AccessControlContext(arrayOf(ProtectionDomain(null, permissions))))
        }
    }
}
