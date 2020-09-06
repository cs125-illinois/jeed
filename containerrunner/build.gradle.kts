import java.util.Properties
import java.io.StringWriter
import java.io.File

plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow") version "6.0.0"
    id("com.palantir.docker") version "0.25.0"
    id("org.jmailen.kotlinter")
}
dependencies {
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("io.github.microutils:kotlin-logging:1.8.3")
    implementation("com.github.ajalt:clikt:2.8.0")
    implementation("io.github.classgraph:classgraph:4.8.89")
}
application {
    mainClassName = "edu.illinois.cs.cs125.jeed.containerrunner.MainKt"
}
tasks.test {
    useJUnitPlatform()
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
}
docker {
    name = "cs125/jeed-containerrunner"
    tag("latest", "cs125/jeed-containerrunner:latest")
    tag(version.toString(), "cs125/jeed-containerrunner:$version")
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
