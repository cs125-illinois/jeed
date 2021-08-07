package edu.illinois.cs.cs125.jeed.core

import com.google.googlejavaformat.java.Formatter

fun String.googleFormat() = Formatter().formatSource(this)

fun Source.googleFormat(): Source {
    check(type == Source.FileType.JAVA) { "Can only google format Java sources" }
    return Source(sources.mapValues { (_, contents) -> contents.googleFormat() })
}
