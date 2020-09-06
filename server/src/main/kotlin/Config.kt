package edu.illinois.cs.cs125.jeed.server

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import com.uchuhimo.konf.source.yaml
import edu.illinois.cs.cs125.jeed.core.ContainerExecutionArguments
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.moshi.PermissionAdapter
import java.io.File

const val DEFAULT_HTTP = "http://0.0.0.0:8888"
const val NAME = "jeed"
const val DEFAULT_SENTINEL_DELAY = 5L

object TopLevel : ConfigSpec("") {
    val http by optional(DEFAULT_HTTP)
    val hosts by optional<List<String>>(listOf())
    val semester by optional<String?>(null)
    val mongodb by optional<String?>(null)
    val sentinelDelay by optional(DEFAULT_SENTINEL_DELAY)

    object Mongo : ConfigSpec() {
        val collection by optional(NAME)
    }
}

object Auth : ConfigSpec() {
    val none by optional(true)

    object Google : ConfigSpec() {
        val hostedDomain by optional<String?>(null)
        val clientIDs by optional<List<String>>(listOf())
    }
}

object Limits : ConfigSpec() {

    object Execution : ConfigSpec() {
        val timeout by optional(Sandbox.ExecutionArguments.DEFAULT_TIMEOUT)
        val permissions by optional(
            SourceExecutionArguments.REQUIRED_PERMISSIONS.toList().map {
                PermissionAdapter().permissionToJson(it)
            }
        )
        val maxExtraThreads by optional(Sandbox.ExecutionArguments.DEFAULT_MAX_EXTRA_THREADS)
        val maxOutputLines by optional(Sandbox.ExecutionArguments.DEFAULT_MAX_OUTPUT_LINES)

        object ClassLoaderConfiguration : ConfigSpec() {
            val whitelistedClasses by optional(Sandbox.ClassLoaderConfiguration.DEFAULT_WHITELISTED_CLASSES)
            val blacklistedClasses by optional(Sandbox.ClassLoaderConfiguration.DEFAULT_BLACKLISTED_CLASSES)
            val unsafeExceptions by optional(Sandbox.ClassLoaderConfiguration.DEFAULT_UNSAFE_EXCEPTIONS)
        }
    }

    object Cexecution : ConfigSpec() {
        val timeout by optional(ContainerExecutionArguments.DEFAULT_TIMEOUT)
    }
}

val configuration = Config {
    addSpec(TopLevel)
    addSpec(Auth)
    addSpec(Limits)
}.let {
    if (File("config.yaml").exists() && File("config.yaml").length() > 0) {
        it.from.yaml.file("config.yaml")
    } else {
        it
    }
}.from.env()
