package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

class TestVersion : StringSpec({
    "should have a valid version" {
        val c = defaultChecker
        version shouldNotBe "unspecified"
    }
})
