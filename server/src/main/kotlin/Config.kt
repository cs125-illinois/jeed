package edu.illinois.cs.cs125.jeed.server

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import com.uchuhimo.konf.source.yaml
import edu.illinois.cs.cs125.jeed.core.ContainerExecutionArguments
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.moshi.PermissionAdapter
import edu.illinois.cs.cs125.jeed.core.server.PluginArguments
import java.io.File

const val DEFAULT_PORT = 8888
const val DEFAULT_SENTINEL_DELAY = 5L

object TopLevel : ConfigSpec("") {
    val port by optional(DEFAULT_PORT)
    val sentinelDelay by optional(DEFAULT_SENTINEL_DELAY)
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
        val maxIOBytes by optional(Sandbox.ExecutionArguments.DEFAULT_MAX_IO_BYTES)

        object ClassLoaderConfiguration : ConfigSpec() {
            val whitelistedClasses by optional(Sandbox.ClassLoaderConfiguration.DEFAULT_WHITELISTED_CLASSES)
            val blacklistedClasses by optional(Sandbox.ClassLoaderConfiguration.DEFAULT_BLACKLISTED_CLASSES)
            val unsafeExceptions by optional(Sandbox.ClassLoaderConfiguration.DEFAULT_UNSAFE_EXCEPTIONS)
            val blacklistedMethods by optional(Sandbox.ClassLoaderConfiguration.DEFAULT_BLACKLISTED_METHODS)
        }
    }

    object Cexecution : ConfigSpec() {
        val timeout by optional(ContainerExecutionArguments.DEFAULT_TIMEOUT)
    }

    object Disassembly : ConfigSpec() {
        private const val DEFAULT_DISASSEMBLY_MAX_BYTES = 50 * 1024
        val maxBytes by optional(DEFAULT_DISASSEMBLY_MAX_BYTES)
    }

    object Plugins : ConfigSpec() {
        val lineCountLimit by optional(PluginArguments.DEFAULT_LINE_COUNT_LIMIT)
        val memoryTotalLimit by optional(PluginArguments.DEFAULT_MEMORY_TOTAL_LIMIT)
        val memoryAllocationLimit by optional(PluginArguments.DEFAULT_MEMORY_ALLOCATION_LIMIT)
    }
}

val configuration = Config {
    addSpec(TopLevel)
    addSpec(Limits)
}.let {
    if (File("config.yaml").exists() && File("config.yaml").length() > 0) {
        it.from.yaml.file("config.yaml")
    } else {
        it
    }
}.from.env()
