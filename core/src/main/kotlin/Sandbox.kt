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
        private data class ConfinedClassLoader(
                val key: Long,
                val accessControlContext: AccessControlContext,
                val maxExtraThreadCount: Int,
                val loggedRequests: MutableList<PermissionRequest> = mutableListOf()
        )
        private val confinedClassLoaders: MutableMap<ClassLoader, ConfinedClassLoader> =
                Collections.synchronizedMap(WeakHashMap<ClassLoader, ConfinedClassLoader>())

        private fun getConfinedClassLoader(classContext: Array<out Class<*>>): ConfinedClassLoader? {
            if (confinedClassLoaders.keys.isEmpty()) {
                return null
            }
            val filteredConfinedClassLoaders = classContext.toList().subList(1, classContext.size).reversed().filter { klass ->
                confinedClassLoaders.containsKey(klass.classLoader)
            }.map {klass ->
                confinedClassLoaders[klass.classLoader]
            }.distinct()
            if (filteredConfinedClassLoaders.isEmpty()) {
                return null
            }
            assert(filteredConfinedClassLoaders.size == 1)
            return filteredConfinedClassLoaders[0]
        }

        val systemSecurityManager: SecurityManager? = System.getSecurityManager()
        val ourSecurityManager = object : SecurityManager() {

            private var inReadCheck = false
            override fun checkRead(file: String) {
                if (inReadCheck) {
                    return
                }
                try {
                    inReadCheck = true
                    val confinedClassLoader = getConfinedClassLoader(classContext) ?: return
                    if (!file.endsWith(".class")) {
                        confinedClassLoader.loggedRequests.add(PermissionRequest(FilePermission(file, "read"), false))
                        throw SecurityException()
                    }
                } finally {
                    inReadCheck = false
                }
            }

            override fun getThreadGroup(): ThreadGroup {
                val confinedClassLoader = getConfinedClassLoader(classContext) ?: return super.getThreadGroup()
                if (Thread.currentThread().threadGroup.activeCount() + 1 > confinedClassLoader.maxExtraThreadCount + 1) {
                    throw SecurityException()
                } else {
                    return super.getThreadGroup()
                }
            }

            private var inPermissionCheck = false
            override fun checkPermission(permission: Permission) {
                if (inPermissionCheck) {
                    return
                }
                var confinedClassLoader: ConfinedClassLoader? = null
                try {
                    inPermissionCheck = true
                    systemSecurityManager?.checkPermission(permission)
                    confinedClassLoader = getConfinedClassLoader(classContext) ?: return
                    confinedClassLoader.accessControlContext.checkPermission(permission)
                    confinedClassLoader.loggedRequests.add(PermissionRequest(permission, true))
                } catch (e: SecurityException) {
                    confinedClassLoader?.loggedRequests?.add(PermissionRequest(permission, false))
                    throw e
                } finally {
                    inPermissionCheck = false
                }
            }
        }


        @Synchronized
        fun confine(classLoader: ClassLoader, permissions: Permissions, maxThreadCount: Int = 1): Long {
            check(!confinedClassLoaders.containsKey(classLoader))
            val key = Random.nextLong()
            confinedClassLoaders[classLoader] = ConfinedClassLoader(
                    key,
                    AccessControlContext(arrayOf(ProtectionDomain(null, permissions))),
                    maxThreadCount
            )
            if (confinedClassLoaders.keys.size == 1) {
                System.setSecurityManager(ourSecurityManager)
            }
            return key
        }

        @Synchronized
        fun release(key: Long?, classLoader: ClassLoader): List<PermissionRequest> {
            val confinedClassLoader = confinedClassLoaders.remove(classLoader) ?: error("couldn't lookup class loader")
            check(key != null && key == confinedClassLoader.key)
            if (confinedClassLoaders.keys.isEmpty()) {
                System.setSecurityManager(systemSecurityManager)
            }
            return confinedClassLoader.loggedRequests
        }
    }
}
