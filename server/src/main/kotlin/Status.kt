package edu.illinois.cs.cs125.jeed.server

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.uchuhimo.konf.Config
import edu.illinois.cs.cs125.jeed.core.compilationCache
import edu.illinois.cs.cs125.jeed.core.compilationCacheSizeMB
import edu.illinois.cs.cs125.jeed.core.systemCompilerName as COMPILER_NAME
import edu.illinois.cs.cs125.jeed.core.useCompilationCache
import edu.illinois.cs.cs125.jeed.core.version as JEED_VERSION
import java.net.InetAddress
import java.time.Instant

@JsonClass(generateAdapter = true)
@Suppress("Unused", "MemberVisibilityCanBePrivate")
class Status(
    val started: Instant = Instant.now(),
    val hostname: String = InetAddress.getLocalHost().hostName,
    var lastRequest: Instant? = null,
    val versions: Versions = Versions(JEED_VERSION, VERSION, COMPILER_NAME),
    val counts: Counts = Counts(),
    val auth: Auth = Auth(configuration),
    val cache: Cache = Cache()
) {
    @JsonClass(generateAdapter = true)
    data class Versions(val jeed: String, val server: String, val compiler: String)
    @JsonClass(generateAdapter = true)
    data class Counts(var submitted: Int = 0, var completed: Int = 0, var saved: Int = 0)
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
    @JsonClass(generateAdapter = true)
    data class Cache(
        val inUse: Boolean = useCompilationCache,
        val sizeInMB: Long = compilationCacheSizeMB,
        var hitRate: Double = 0.0,
        var evictionCount: Long = 0,
        var averageLoadPenalty: Double = 0.0
    )
    fun update(): Status {
        compilationCache.stats().also {
            cache.hitRate = it.hitRate()
            cache.evictionCount = it.evictionCount()
            cache.averageLoadPenalty = it.averageLoadPenalty()
        }
        return this
    }
    companion object {
        private val statusAdapter: JsonAdapter<Status> = moshi.adapter(Status::class.java)
        fun from(response: String?): Status {
            check(response != null) { "can't deserialize null string" }
            return statusAdapter.fromJson(response) ?: check { "failed to deserialize status" }
        }
    }
}
