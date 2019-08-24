package edu.illinois.cs.cs125.jeed.server

import com.squareup.moshi.JsonClass
import com.uchuhimo.konf.source.base.toHierarchicalMap
import edu.illinois.cs.cs125.jeed.core.version
import java.time.Instant

@JsonClass(generateAdapter = true)
class Status(
        val started: Instant = Instant.now(),
        val versions: Versions = Versions(version),
        val counts: Counts = Counts(),
        val config: Map<String, Any> = configuration.toHierarchicalMap()
) {
    data class Versions(val jeed: String)
    data class Counts(var submittedJobs: Int = 0, var completedJobs: Int = 0)
}
val currentStatus = Status()
