package edu.illinois.cs.cs125.jeed.server

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import com.uchuhimo.konf.source.base.toHierarchicalMap
import com.uchuhimo.konf.source.yaml
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.moshi.PermissionAdapter
import java.io.File

object Auth : ConfigSpec() {
    val none by optional(true)
    object Google : ConfigSpec() {
        val hostedDomain by optional("")
    }
}
object Limits : ConfigSpec() {
    object Execution : ConfigSpec() {
        val timeout by optional(Sandbox.ExecutionArguments.DEFAULT_TIMEOUT)
        val permissions by optional(SourceExecutionArguments.REQUIRED_PERMISSIONS.toList().map { PermissionAdapter().permissionToJson(it) })
        val maxExtraThreads by optional(Sandbox.ExecutionArguments.DEFAULT_MAX_EXTRA_THREADS)
        object ClassLoaderConfiguration : ConfigSpec() {
            val whitelistedClasses by optional(Sandbox.ClassLoaderConfiguration.DEFAULT_WHITELISTED_CLASSES)
            val blacklistedClasses by optional(Sandbox.ClassLoaderConfiguration.DEFAULT_BLACKLISTED_CLASSES)
            val unsafeExceptions by optional(Sandbox.ClassLoaderConfiguration.DEFAULT_UNSAFE_EXCEPTIONS)
        }
    }
}

val configuration = Config {
    addSpec(Auth)
    addSpec(Limits)
}.let {
    if (File("config.yaml").exists() && File("config.yaml").length() > 0) {
        it.from.yaml.file("config.yaml")
    } else {
        it
    }
}
