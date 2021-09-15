package edu.illinois.cs.cs125.jeed.leaktest

import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.kompile

fun main() {
    for (i in 0..(1024 * 1024)) {
        Source.fromKotlin(
            """
fun main() {
  println("Hello, $i")
}""".trim()
        ).kompile().also {
            println(i)
        }
    }
}
