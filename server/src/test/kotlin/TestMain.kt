package edu.illinois.cs.cs125.jeed.server

import io.kotlintest.specs.StringSpec

class TestMain : StringSpec({
    "f: warmup function should not fail" {
        warm()
    }
})