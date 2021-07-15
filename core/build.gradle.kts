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
    id("io.gitlab.arturbosch.detekt")
}
dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.12.0")

    antlr("org.antlr:antlr4:4.9.2")

    implementation(kotlin("stdlib"))
    implementation(kotlin("compiler-embeddable"))
    implementation(kotlin("reflect"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.1")
    implementation("com.puppycrawl.tools:checkstyle:8.44")
    implementation("com.pinterest.ktlint:ktlint-core:0.40.0")
    implementation("com.pinterest.ktlint:ktlint-ruleset-standard:0.41.0")
    implementation("com.github.jknack:handlebars:4.2.0")
    implementation("com.squareup.moshi:moshi:1.12.0")
    implementation("org.ow2.asm:asm:9.2")
    implementation("org.ow2.asm:asm-util:9.2")
    implementation("org.slf4j:slf4j-api:1.7.31")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("io.github.microutils:kotlin-logging:2.0.10")
    implementation("io.github.classgraph:classgraph:4.8.110")
    implementation("net.java.dev.jna:jna:5.8.0")
    implementation("io.github.java-diff-utils:java-diff-utils:4.10")
    api("com.github.ben-manes.caffeine:caffeine:3.0.3")

    testImplementation("io.kotest:kotest-runner-junit5:4.6.1")
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
    arguments.addAll(
        listOf(
            "-visitor",
            "-package", "edu.illinois.cs.cs125.jeed.core.antlr",
            "-Xexact-output-dir",
            "-lib", "src/main/antlr/edu/illinois/cs/cs125/jeed/antlr/lib/"
        )
    )
}
tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource, "createProperties")
}
tasks.compileTestKotlin {
    dependsOn(tasks.generateTestGrammarSource)
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
tasks {
    val sourcesJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }
    artifacts {
        add("archives", sourcesJar)
    }
}
tasks.detekt {
    dependsOn(tasks.generateGrammarSource)
}
tasks.lintKotlinMain {
    dependsOn(tasks.generateGrammarSource)
}
tasks.formatKotlinMain {
    dependsOn(tasks.generateGrammarSource)
}
publishing {
    publications {
        create<MavenPublication>("core") {
            from(components["java"])
        }
    }
}
