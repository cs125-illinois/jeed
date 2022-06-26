import java.util.Properties
import java.io.StringWriter
import java.io.File

plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("com.palantir.docker") version "0.34.0"
    id("org.jmailen.kotlinter")
    id("io.gitlab.arturbosch.detekt")
}
dependencies {
    implementation("ch.qos.logback:logback-classic:1.2.11")
    implementation("io.github.microutils:kotlin-logging:2.1.23")
    implementation("com.github.ajalt.clikt:clikt:3.5.0")
    implementation("io.github.classgraph:classgraph:4.8.147")
}
application {
    @Suppress("DEPRECATION")
    mainClassName = "edu.illinois.cs.cs125.jeed.containerrunner.MainKt"
}
tasks.test {
    useJUnitPlatform()
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
}
docker {
    name = "cs125/jeed-containerrunner"
    @Suppress("DEPRECATION")
    tags("latest")
    files(tasks["shadowJar"].outputs)
}
task("createProperties") {
    doLast {
        val properties = Properties().also {
            it["version"] = project.version.toString()
        }
        File(
            projectDir,
            "src/main/resources/edu.illinois.cs.cs125.jeed.containerrunner.version"
        )
            .printWriter().use { printWriter ->
                printWriter.print(
                    StringWriter().also { properties.store(it, null) }.buffer.toString()
                        .lines().drop(1).joinToString(separator = "\n").trim()
                )
            }
    }
}
tasks.processResources {
    dependsOn("createProperties")
}
