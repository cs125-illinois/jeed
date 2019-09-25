import java.util.Properties

plugins {
    kotlin("jvm")
    antlr
    java
    maven
    `maven-publish`
}

group = "com.github.cs125-illinois"
version = "2019.9.2"

tasks.test {
    useJUnitPlatform()
    jvmArgs("-ea", "-Xmx1G")
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
}
dependencies {
    antlr("org.antlr:antlr4:4.7.2")

    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.1")
    implementation("com.puppycrawl.tools:checkstyle:8.24")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.3.50")
    implementation("com.github.jknack:handlebars:4.1.2")
    implementation("com.squareup.moshi:moshi:1.8.0")
    implementation("org.ow2.asm:asm:7.1")
    implementation("org.ow2.asm:asm-util:7.1")
    implementation("org.slf4j:slf4j-api:1.7.28")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("io.github.microutils:kotlin-logging:1.7.6")

    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.1")
}
tasks.generateGrammarSource {
    outputDirectory = File(projectDir, "src/main/java/edu/illinois/cs/cs125/jeed/core/antlr")
    arguments.addAll(listOf("-visitor", "-package", "edu.illinois.cs.cs125.jeed.core.antlr", "-Xexact-output-dir"))
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
        File(projectDir, "src/main/resources/version.properties").printWriter().use { properties.store(it, null) }
    }
}
