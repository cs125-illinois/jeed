package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldNotBe

class TestVersion : StringSpec({
    "should have a valid version" {
        version shouldNotBe "unspecified"
    }
})
