package edu.illinois.cs.cs125.jeed.server

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.version as JEED_VERSION
import edu.illinois.cs.cs125.jeed.core.systemCompilerName as COMPILER_NAME
import java.time.Instant

@JsonClass(generateAdapter = true)
class Status(
        val started: Instant = Instant.now(),
        var lastJob: Instant? = null,
        val versions: Versions = Versions(JEED_VERSION, VERSION, COMPILER_NAME),
        val counts: Counts = Counts()
) {
    @JsonClass(generateAdapter = true)
    data class Versions(val jeed: String, val server: String, val compiler: String)
    @JsonClass(generateAdapter = true)
    data class Counts(var submittedJobs: Int = 0, var completedJobs: Int = 0, var savedJobs: Int = 0)
}
val currentStatus = Status()
