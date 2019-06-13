package edu.illinois.cs.cs125.jeed.core

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

class Sandbox {
    companion object {
        private val confinedClassLoaders: MutableMap<ClassLoader, AccessControlContext> =
                Collections.synchronizedMap(WeakHashMap<ClassLoader, AccessControlContext>())

        val systemSecurityManager: SecurityManager? = System.getSecurityManager()
        val ourSecurityManager = object : SecurityManager() {
            private var inReadCheck = false
            override fun checkRead(file: String) {
                if (inReadCheck) {
                    return
                }
                try {
                    inReadCheck = true
                    if (confinedClassLoaders.keys.isEmpty()) {
                        return
                    }
                    val filteredClassLoaders = classContext.toList().subList(1, classContext.size).reversed().filter { klass ->
                        confinedClassLoaders.containsKey(klass.classLoader)
                    }.map {klass ->
                        klass.classLoader
                    }.distinct()
                    if (filteredClassLoaders.isEmpty()) {
                        return
                    }
                    val confinedClassLoader = filteredClassLoaders[0]
                    if (!file.endsWith(".class")) {
                        loggedRequests[confinedClassLoader]!!.add(PermissionRequest(FilePermission(file, "read"), false))
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
                var confinedClassLoader: ClassLoader? = null
                try {
                    inPermissionCheck = true
                    systemSecurityManager?.checkPermission(permission)
                    if (confinedClassLoaders.keys.isEmpty()) {
                        return
                    }
                    val filteredClassLoaders = classContext.toList().subList(1, classContext.size).reversed().filter { klass ->
                        confinedClassLoaders.containsKey(klass.classLoader)
                    }.map {klass ->
                        klass.classLoader
                    }.distinct()
                    if (filteredClassLoaders.isEmpty()) {
                        return
                    }
                    assert(filteredClassLoaders.size == 1)
                    confinedClassLoader = filteredClassLoaders[0]
                    confinedClassLoaders[confinedClassLoader]!!.checkPermission(permission)
                    loggedRequests[confinedClassLoader]!!.add(PermissionRequest(permission, true))
                } catch (e: SecurityException) {
                    loggedRequests[confinedClassLoader]!!.add(PermissionRequest(permission, false))
                    throw e
                } finally {
                    inPermissionCheck = false
                }
            }
        }

        private val classLoaderKeys: MutableMap<ClassLoader, Long> = mutableMapOf()
        private var loggedRequests: MutableMap<ClassLoader, MutableList<PermissionRequest>> = mutableMapOf()

        @Synchronized
        fun confine(classLoader: ClassLoader, permissions: Permissions): Long {
            check(!classLoaderKeys.contains(classLoader))
            classLoaderKeys[classLoader] = Random.nextLong()
            loggedRequests[classLoader] = mutableListOf()
            confinedClassLoaders[classLoader] = AccessControlContext(arrayOf(ProtectionDomain(null, permissions)))
            System.setSecurityManager(ourSecurityManager)
            return classLoaderKeys[classLoader] ?: error("currentKey changed before we exited")
        }

        @Synchronized
        fun release(key: Long?, classLoader: ClassLoader): List<PermissionRequest> {
            check(key != null && classLoaderKeys[classLoader] == key)
            classLoaderKeys.remove(classLoader)
            check(confinedClassLoaders.containsKey(classLoader))
            confinedClassLoaders.remove(classLoader)
            System.setSecurityManager(systemSecurityManager)
            return loggedRequests[classLoader] ?: error("should have logged requests for this class loader")
        }
    }
}
