package edu.illinois.cs.cs125.jeed.server.moshi

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import edu.illinois.cs.cs125.jeed.core.TemplatedSource
import edu.illinois.cs.cs125.jeed.server.FlatSource
import edu.illinois.cs.cs125.jeed.server.Job
import edu.illinois.cs.cs125.jeed.server.Result
import edu.illinois.cs.cs125.jeed.server.toFlatSources

@JvmField
val Adapters = setOf(
        Job.JobAdapter(),
        Result.ResultAdapter(),
        TemplatedSourceAdapter()
)

data class TemplatedSourceJson(val originalSources: List<FlatSource>)
class TemplatedSourceAdapter {
    @Throws(Exception::class)
    @Suppress("UNUSED_PARAMETER")
    @FromJson
    fun templatedSourceFromJson(templatedSourceJson: TemplatedSourceJson): TemplatedSource {
        throw Exception("Can't convert JSON to TemplatedSource")
    }
    @ToJson
    fun templatedSourceToJson(templatedSource: TemplatedSource): TemplatedSourceJson {
        return TemplatedSourceJson(templatedSource.originalSources.toFlatSources())
    }
}
