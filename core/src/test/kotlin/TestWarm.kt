package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.specs.StringSpec

class TestWarm : StringSpec({
    "warmup function should not fail" {
        warm()
    }
})
