package edu.illinois.cs.cs125.jeed.core

import java.util.*

val version = run {
    try {
        val versionFile = object : Any() {}::class.java.getResource("/core_version.properties")
        Properties().also { it.load(versionFile.openStream()) }["version"] as String
    } catch (e: Exception) {
        println(e)
        "unspecified"
    }
}
