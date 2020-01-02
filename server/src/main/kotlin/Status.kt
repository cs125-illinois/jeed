package edu.illinois.cs.cs125.jeed.server

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.uchuhimo.konf.Config
import edu.illinois.cs.cs125.jeed.core.systemCompilerName as COMPILER_NAME
import edu.illinois.cs.cs125.jeed.core.version as JEED_VERSION
import java.time.Instant

@JsonClass(generateAdapter = true)
class Status(
    val started: Instant = Instant.now(),
    var lastJob: Instant? = null,
    val versions: Versions = Versions(JEED_VERSION, VERSION, COMPILER_NAME),
    val counts: Counts = Counts(),
    @Suppress("Unused") val auth: Auth = Auth(configuration)
) {
    @JsonClass(generateAdapter = true)
    data class Versions(val jeed: String, val server: String, val compiler: String)
    @JsonClass(generateAdapter = true)
    data class Counts(var submittedJobs: Int = 0, var completedJobs: Int = 0, var savedJobs: Int = 0)
    @JsonClass(generateAdapter = true)
    data class Auth(val none: Boolean = true, val google: Google) {
        @JsonClass(generateAdapter = true)
        data class Google(val hostedDomain: String?, val clientID: String?)
        constructor(config: Config) : this(
            config[edu.illinois.cs.cs125.jeed.server.Auth.none],
            Google(
                config[edu.illinois.cs.cs125.jeed.server.Auth.Google.hostedDomain],
                config[edu.illinois.cs.cs125.jeed.server.Auth.Google.clientID]
            )
        )
    }
    companion object {
        private val statusAdapter: JsonAdapter<Status> = moshi.adapter(Status::class.java)
        fun from(response: String?): Status {
            check(response != null) { "can't deserialize null string" }
            return statusAdapter.fromJson(response) ?: check { "failed to deserialize status" }
        }
    }
}
val currentStatus = Status()
