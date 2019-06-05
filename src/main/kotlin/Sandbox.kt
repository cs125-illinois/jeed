package edu.illinois.cs.cs125.jeed

import mu.KotlinLogging
import java.io.FilePermission
import java.security.AccessControlContext
import java.security.Permission
import java.security.Permissions
import java.security.ProtectionDomain
import java.util.*
import kotlin.random.Random

// import java.util.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

data class PermissionRequest(
        val permission: Permission,
        val granted: Boolean
)
fun MutableList<PermissionRequest>.addIf(currentKey: Long?, request: PermissionRequest) {
    if (currentKey != null) {
        this.add(request)
    }
}

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
                            loggedRequests.addIf(currentKey, PermissionRequest(FilePermission(file, "read"), false))
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
                        loggedRequests.addIf(currentKey, PermissionRequest(permission, true))
                    } catch (e: SecurityException) {
                        loggedRequests.addIf(currentKey, PermissionRequest(permission, false))
                        throw e
                    } finally {
                        inPermissionCheck = false
                    }
                }
            })
        }

        private var currentKey: Long? = null
        private var loggedRequests: MutableList<PermissionRequest> = mutableListOf()

        @Synchronized
        fun confine(classLoader: ClassLoader, permissions: Permissions): Long {
            check(currentKey == null)
            currentKey = Random.nextLong()
            loggedRequests = mutableListOf()
            confinedClassLoaders[classLoader] = AccessControlContext(arrayOf(ProtectionDomain(null, permissions)))
            return currentKey ?: error("currentKey changed before we exited")
        }

        @Synchronized
        fun release(key: Long?, classLoader: ClassLoader): List<PermissionRequest> {
            check(currentKey != null && key == currentKey)
            currentKey = null
            check(confinedClassLoaders.containsKey(classLoader))
            confinedClassLoaders.remove(classLoader)
            return loggedRequests
        }
    }
}
