package edu.illinois.cs.cs125.jeed.core

import mu.KotlinLogging
import java.io.FilePermission
import java.lang.reflect.ReflectPermission
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
        ReflectPermission("suppressAccessChecks"),
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

class SandboxConfigurationError(message: String) : Exception(message)

object Sandbox : SecurityManager() {
    private data class ConfinedThreadGroup(
            val key: Long,
            val accessControlContext: AccessControlContext,
            val maxExtraThreadCount: Int,
            val loggedRequests: MutableList<PermissionRequest> = mutableListOf()
    )
    private val confinedThreadGroups: MutableMap<ThreadGroup, ConfinedThreadGroup> =
            Collections.synchronizedMap(WeakHashMap<ThreadGroup, ConfinedThreadGroup>())

    private var inReadCheck = false
    override fun checkRead(file: String) {
        if (inReadCheck) {
            return
        }
        try {
            inReadCheck = true
            val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup] ?: return
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
        if (Thread.currentThread().threadGroup.activeCount() + 1 > confinedThreadGroup.maxExtraThreadCount + 1) {
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
    fun confine(threadGroup: ThreadGroup, permissions: Permissions, maxThreadCount: Int = 1): Long {
        check(!confinedThreadGroups.containsKey(threadGroup)) { "thread group is already confined" }
        permissions.elements().toList().intersect(blacklistedPermissions).isEmpty() || throw SandboxConfigurationError("attempt to allow unsafe permissions")
        val key = Random.nextLong()
        confinedThreadGroups[threadGroup] = ConfinedThreadGroup(
                key,
                AccessControlContext(arrayOf(ProtectionDomain(null, permissions))),
                maxThreadCount
        )
        if (confinedThreadGroups.keys.size == 1) {
            System.setSecurityManager(this)
        }
        return key
    }

    @Synchronized
    fun release(key: Long?, threadGroup: ThreadGroup): List<PermissionRequest> {
        val confinedThreadGroup = confinedThreadGroups.remove(threadGroup) ?: error("thread group is not confined")
        check(key != null && key == confinedThreadGroup.key) { "invalid key" }
        if (confinedThreadGroups.keys.isEmpty()) {
            System.setSecurityManager(systemSecurityManager)
        }
        return confinedThreadGroup.loggedRequests
    }
}
