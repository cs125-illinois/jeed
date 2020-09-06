package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.haveOutput
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe

class TestStartStop : StringSpec({
    "should start and stop properly" {
        Sandbox.start()
        Sandbox.running shouldBe true
        Sandbox.stop()
        Sandbox.running shouldBe false
    }
    "should autostart properly" {
        val executeMainResult = Source.fromSnippet(
            """
int i = 0;
i++;
System.out.println(i);
            """.trim()
        ).compile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("1")
        Sandbox.running shouldBe true
        Sandbox.stop()
        Sandbox.running shouldBe false
    }
})
