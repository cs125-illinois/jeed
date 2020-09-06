package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec

class TestWarm : StringSpec({
    "warmup function should not fail" {
        warm()
    }
})
