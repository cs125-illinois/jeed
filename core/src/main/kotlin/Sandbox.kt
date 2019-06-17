package edu.illinois.cs.cs125.jeed.core

import mu.KotlinLogging
import java.io.FilePermission
import java.security.*
import java.util.*
import kotlin.random.Random

// import java.util.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

data class PermissionRequest(val permission: Permission, val granted: Boolean)
private val systemSecurityManager: SecurityManager? = System.getSecurityManager()

val blacklistedPermissions = listOf(
        // Suggestions from here: https://github.com/pro-grade/pro-grade/issues/31.
        RuntimePermission("createClassLoader"),
        RuntimePermission("accessClassInPackage.sun"),
        RuntimePermission("setSecurityManager"),
        // Required for Java Streams to work...
        // ReflectPermission("suppressAccessChecks")
        SecurityPermission("setPolicy"),
        SecurityPermission("setProperty.package.access"),
        // Other additions from here: https://docs.oracle.com/javase/7/docs/technotes/guides/security/permissions.html
        SecurityPermission("createAccessControlContext"),
        SecurityPermission("getDomainCombiner"),
        RuntimePermission("createSecurityManager"),
        RuntimePermission("exitVM"),
        RuntimePermission("shutdownHooks"),
        RuntimePermission("setIO"),
        // These are particularly important to prevent untrusted code from escaping the sandbox which is based on thread groups
        RuntimePermission("modifyThread"),
        RuntimePermission("modifyThreadGroup")
)

class SandboxConfigurationException(message: String) : Exception(message)

object Sandbox : SecurityManager() {
    private data class ConfinedThreadGroup(
            val key: Long,
            val accessControlContext: AccessControlContext,
            val maxExtraThreadCount: Int,
            val loggedRequests: MutableList<PermissionRequest> = mutableListOf(),
            var shuttingDown: Boolean = false
    )
    private val confinedThreadGroups: MutableMap<ThreadGroup, ConfinedThreadGroup> =
            Collections.synchronizedMap(WeakHashMap<ThreadGroup, ConfinedThreadGroup>())

    fun sleepForever() {
        while (true) { Thread.sleep(Long.MAX_VALUE) }
    }
    private var inReadCheck = false
    override fun checkRead(file: String) {
        if (inReadCheck) {
            return
        }
        try {
            inReadCheck = true
            val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup] ?: return
            if (confinedThreadGroup.shuttingDown) {
                sleepForever()
            }
            if (!file.endsWith(".class")) {
                confinedThreadGroup.loggedRequests.add(PermissionRequest(FilePermission(file, "read"), false))
                throw SecurityException()
            }
        } finally {
            inReadCheck = false
        }
    }
    override fun getThreadGroup(): ThreadGroup {
        val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup] ?: return super.getThreadGroup()
        if (confinedThreadGroup.shuttingDown) {
            sleepForever()
        }
        if (Thread.currentThread().threadGroup.activeCount() >= confinedThreadGroup.maxExtraThreadCount + 1) {
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
        val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup] ?: return
        try {
            inPermissionCheck = true
            if (confinedThreadGroup.shuttingDown) {
                sleepForever()
            }
            systemSecurityManager?.checkPermission(permission)
            confinedThreadGroup.accessControlContext.checkPermission(permission)
            confinedThreadGroup.loggedRequests.add(PermissionRequest(permission, true))
        } catch (e: SecurityException) {
            confinedThreadGroup.loggedRequests.add(PermissionRequest(permission, false))
            throw e
        } finally {
            inPermissionCheck = false
        }
    }

    @Synchronized
    fun confine(threadGroup: ThreadGroup, permissionList: List<Permission>, maxExtraThreadCount: Int = 0): Long {
        check(!confinedThreadGroups.containsKey(threadGroup)) { "thread group is already confined" }
        permissionList.intersect(blacklistedPermissions).isEmpty() || throw SandboxConfigurationException("attempt to allow unsafe permissions")

        val permissions = Permissions()
        permissionList.forEach { permissions.add(it) }

        val key = Random.nextLong()
        confinedThreadGroups[threadGroup] = ConfinedThreadGroup(
                key,
                AccessControlContext(arrayOf(ProtectionDomain(null, permissions))),
                maxExtraThreadCount
        )
        if (confinedThreadGroups.keys.size == 1) {
            System.setSecurityManager(this)
        }
        return key
    }
    @Synchronized
    fun shutdown(key: Long, threadGroup: ThreadGroup) {
        val confinedThreadGroup = confinedThreadGroups[threadGroup] ?: error("thread group is not confined")
        check(key == confinedThreadGroup.key) { "invalid key" }
        confinedThreadGroup.shuttingDown = true
    }
    @Synchronized
    fun release(key: Long, threadGroup: ThreadGroup): List<PermissionRequest> {
        val confinedThreadGroup = confinedThreadGroups.remove(threadGroup) ?: error("thread group is not confined")
        check(key == confinedThreadGroup.key) { "invalid key" }
        if (confinedThreadGroups.keys.isEmpty()) {
            System.setSecurityManager(systemSecurityManager)
        }
        return confinedThreadGroup.loggedRequests
    }
}
