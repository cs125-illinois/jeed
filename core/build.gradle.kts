import java.io.File
import java.io.StringWriter
import java.util.Properties

plugins {
    kotlin("jvm")
    kotlin("kapt")
    antlr
    java
    `maven-publish`
    id("org.jmailen.kotlinter")
}
dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.11.0")

    antlr("org.antlr:antlr4:4.9.1")

    implementation(kotlin("stdlib"))
    implementation(kotlin("compiler-embeddable"))
    implementation(kotlin("reflect"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.2")
    implementation("com.puppycrawl.tools:checkstyle:8.40")
    implementation("com.pinterest.ktlint:ktlint-core:0.40.0")
    implementation("com.pinterest.ktlint:ktlint-ruleset-standard:0.40.0")
    implementation("com.github.jknack:handlebars:4.2.0")
    implementation("com.squareup.moshi:moshi:1.11.0")
    implementation("org.ow2.asm:asm:9.0")
    implementation("org.ow2.asm:asm-util:9.0")
    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("io.github.microutils:kotlin-logging:2.0.4")
    implementation("io.github.classgraph:classgraph:4.8.102")
    implementation("net.java.dev.jna:jna:5.6.0")
    api("com.github.ben-manes.caffeine:caffeine:2.8.8")

    testImplementation("io.kotest:kotest-runner-junit5:4.3.2")
}
tasks.test {
    useJUnitPlatform()
    if (JavaVersion.current() >= JavaVersion.VERSION_11) {
        jvmArgs("-ea", "-Xmx1G", "-Xss256k", "--enable-preview")
    } else {
        jvmArgs("-ea", "-Xmx1G", "-Xss256k")
    }
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
    @Suppress("MagicNumber")
    environment["JEED_MAX_THREAD_POOL_SIZE"] = 4
    environment["PATH"] = "${environment["PATH"]}:/usr/local/bin/"
    environment["JEED_CONTAINER_TMP_DIR"] = "/tmp/"

    if (!project.hasProperty("slowTests")) {
        exclude("**/TestResourceExhaustion.class")
        exclude("**/TestParallelism.class")
        exclude("**/TestContainer.class")
    }
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
java {
    withSourcesJar()
}
publishing {
    publications {
        create<MavenPublication>("core") {
            from(components["java"])
        }
    }
}
