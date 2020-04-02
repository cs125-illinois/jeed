import java.io.File
import java.io.StringWriter
import java.util.Properties

group = "com.github.cs125-illinois"
version = "2020.4.2"

plugins {
    kotlin("jvm")
    kotlin("kapt")
    antlr
    java
    maven
    `maven-publish`
    id("org.jmailen.kotlinter")
}
dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.9.2")

    antlr("org.antlr:antlr4:4.8-1")

    implementation(kotlin("stdlib"))
    implementation(kotlin("compiler-embeddable"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.5")
    implementation("com.puppycrawl.tools:checkstyle:8.31")
    implementation("com.pinterest.ktlint:ktlint-core:0.36.0")
    implementation("com.pinterest.ktlint:ktlint-ruleset-standard:0.36.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.3.71")
    implementation("com.github.jknack:handlebars:4.1.2")
    implementation("com.squareup.moshi:moshi:1.9.2")
    implementation("org.ow2.asm:asm:8.0")
    implementation("org.ow2.asm:asm-util:8.0")
    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("io.github.microutils:kotlin-logging:1.7.9")
    implementation("io.github.classgraph:classgraph:4.8.67")
    implementation("net.java.dev.jna:jna:5.5.0")
    api("com.github.ben-manes.caffeine:caffeine:2.8.1")

    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
}
tasks.test {
    useJUnitPlatform()
    if (JavaVersion.current() >= JavaVersion.VERSION_11) {
        jvmArgs("-ea", "-Xmx1G", "-Xss256k", "--enable-preview")
    } else {
        jvmArgs("-ea", "-Xmx1G", "-Xss256k")
    }
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
    environment["JEED_MAX_THREAD_POOL_SIZE"] = 4
}
tasks.generateGrammarSource {
    outputDirectory = File(projectDir, "src/main/java/edu/illinois/cs/cs125/jeed/core/antlr")
    arguments.addAll(listOf(
        "-visitor",
        "-package", "edu.illinois.cs.cs125.jeed.core.antlr",
        "-Xexact-output-dir",
        "-lib", "src/main/antlr/edu/illinois/cs/cs125/jeed/antlr/lib/"
    ))
}
tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource, "createProperties")
}
task("createProperties") {
    dependsOn(tasks.processResources)
    doLast {
        val properties = Properties().also {
            it["version"] = project.version.toString()
        }
        File(projectDir, "src/main/resources/edu.illinois.cs.cs125.jeed.core.version")
        .printWriter().use { printWriter ->
            printWriter.print(
                StringWriter().also { properties.store(it, null) }.buffer.toString()
                    .lines().drop(1).joinToString(separator = "\n").trim()
            )
        }
    }
}
kapt {
    useBuildCache = true
    includeCompileClasspath = false
}
