package edu.illinois.cs.cs125.jeed.server

import com.squareup.moshi.Moshi
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters
import edu.illinois.cs.cs125.jeed.server.moshi.Adapters as Adapters

val moshi = Moshi.Builder().apply {
    JeedAdapters.forEach { this.add(it) }
    Adapters.forEach { this.add(it) }
}.build()
