package edu.illinois.cs.cs125.jeed.server.moshi

import edu.illinois.cs.cs125.jeed.server.Job
import edu.illinois.cs.cs125.jeed.server.Result

@JvmField
val Adapters = setOf(Job.JobAdapter(), Result.ResultAdapter())
