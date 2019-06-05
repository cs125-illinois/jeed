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

        private var inCheck = false
        init {
            val previousSecurityManager = System.getSecurityManager()
            System.setSecurityManager(object : SecurityManager() {
                override fun checkPermission(permission: Permission) {
                    if (inCheck) {
                        return
                    }
                    try {
                        inCheck = true
                        previousSecurityManager?.checkPermission(permission)
                        if (confinedClassLoaders.keys.isEmpty()) {
                            return
                        }
                        classContext.toList().subList(1, classContext.size).forEach {
                            if (it == javaClass) {
                                return
                            }
                            val accessControlContext = confinedClassLoaders[it.classLoader] ?: return@forEach
                            accessControlContext.checkPermission(permission)
                        }
                        if (logging) {
                            loggedRequests.add(PermissionRequest(permission, true))
                        }
                    } catch (e: Exception) {
                        if (logging) {
                            loggedRequests.add(PermissionRequest(permission, false))
                        }
                        throw e
                    } finally {
                        inCheck = false
                    }
                }
            })
        }

        fun confine(classLoader: ClassLoader, permissions: Permissions) {
            confinedClassLoaders[classLoader] = AccessControlContext(arrayOf(ProtectionDomain(null, permissions)))
        }

        data class PermissionRequest(
                val permission: Permission,
                val granted: Boolean
        )
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
