package edu.illinois.cs.cs125.jeed.server.moshi

import edu.illinois.cs.cs125.jeed.server.Job
import edu.illinois.cs.cs125.jeed.server.Result
import edu.illinois.cs.cs125.jeed.server.Status

@JvmField
val Adapters = setOf(Job.JobAdapter(), Result.ResultAdapter())
