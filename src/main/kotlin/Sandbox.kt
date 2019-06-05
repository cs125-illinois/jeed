package edu.illinois.cs.cs125.jeed

import mu.KotlinLogging
import java.io.FilePermission
import java.security.AccessControlContext
import java.security.Permission
import java.security.Permissions
import java.security.ProtectionDomain
import java.util.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

data class PermissionRequest(
        val permission: Permission,
        val granted: Boolean
)

class Sandbox {
    companion object {
        private val confinedClassLoaders: MutableMap<ClassLoader, AccessControlContext> =
                Collections.synchronizedMap(WeakHashMap<ClassLoader, AccessControlContext>())

        init {
            val previousSecurityManager = System.getSecurityManager()
            System.setSecurityManager(object : SecurityManager() {

                private var inReadCheck = false
                override fun checkRead(file: String) {
                    if (inReadCheck) {
                        return
                    }
                    try {
                        inReadCheck = true
                        if (confinedClassLoaders.keys.isEmpty() ||
                                !classContext.toList().subList(1, classContext.size).any {
                                    klass -> confinedClassLoaders.containsKey(klass.classLoader)
                                }) {
                            return
                        }
                        if (!file.endsWith(".class")) {
                            if (logging) {
                                loggedRequests.add(PermissionRequest(FilePermission(file, "read"), false))
                            }
                            throw SecurityException()
                        }
                    } finally {
                        inReadCheck = false
                    }
                }

                private var inPermissionCheck = false
                override fun checkPermission(permission: Permission) {
                    if (inPermissionCheck) {
                        return
                    }
                    try {
                        inPermissionCheck = true
                        previousSecurityManager?.checkPermission(permission)
                        if (confinedClassLoaders.keys.isEmpty()) {
                            return
                        }
                        classContext.toList().subList(1, classContext.size).reversed().forEach { klass ->
                            val accessControlContext = confinedClassLoaders[klass.classLoader] ?: return@forEach
                            accessControlContext.checkPermission(permission)
                        }
                        if (logging) {
                            loggedRequests.add(PermissionRequest(permission, true))
                        }
                    } catch (e: SecurityException) {
                        if (logging) {
                            loggedRequests.add(PermissionRequest(permission, false))
                        }
                        throw e
                    } finally {
                        inPermissionCheck = false
                    }
                }
            })
        }

        fun confine(classLoader: ClassLoader, permissions: Permissions) {
            confinedClassLoaders[classLoader] = AccessControlContext(arrayOf(ProtectionDomain(null, permissions)))
        }

        private var logging: Boolean = false
        private var loggedRequests: MutableList<PermissionRequest> = mutableListOf()

        fun log() {
            check(!logging)
            logging = true
            loggedRequests = mutableListOf()
        }
        fun retrieve(): List<PermissionRequest> {
            check(logging)
            logging = false
            return loggedRequests
        }
    }
}
