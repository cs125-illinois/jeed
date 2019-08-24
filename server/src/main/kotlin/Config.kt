package edu.illinois.cs.cs125.jeed.server

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import com.uchuhimo.konf.source.yaml
import edu.illinois.cs.cs125.jeed.core.Sandbox
import java.io.File

object Limits : ConfigSpec() {
    object Execution : ConfigSpec() {
        val timeout by optional(Sandbox.ExecutionArguments.DEFAULT_TIMEOUT)
        val maxExtraThreads by optional(Sandbox.ExecutionArguments.DEFAULT_MAX_EXTRA_THREADS)
    }
}
val config = Config { addSpec(Limits) }.let {
    if (File("config.yaml").exists()) {
        it.from.yaml.file("config.yaml")
    } else {
        it
    }
}
