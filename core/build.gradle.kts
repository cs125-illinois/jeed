import java.util.Properties

plugins {
    kotlin("jvm")
    antlr
    java
    maven
    `maven-publish`
}

group = "com.github.cs125-illinois"
version = "2019.12.1"

tasks.test {
    useJUnitPlatform()
    if (JavaVersion.current() >= JavaVersion.VERSION_11) {
        jvmArgs("-ea", "-Xmx1G", "--enable-preview")
    } else {
        jvmArgs("-ea", "-Xmx1G")
    }
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
}
dependencies {
    antlr("org.antlr:antlr4:4.7.2")

    implementation(kotlin("stdlib"))
    implementation(kotlin("compiler"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.2-1.3.60")
    implementation("com.puppycrawl.tools:checkstyle:8.27")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.3.61")
    implementation("com.github.jknack:handlebars:4.1.2")
    implementation("com.squareup.moshi:moshi:1.9.2")
    implementation("org.ow2.asm:asm:7.2")
    implementation("org.ow2.asm:asm-util:7.2")
    implementation("org.slf4j:slf4j-api:1.7.29")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("io.github.microutils:kotlin-logging:1.7.8")
    implementation("io.github.classgraph:classgraph:4.8.58")

    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
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
        File(projectDir, "src/main/resources/core_version.properties").printWriter().use { properties.store(it, null) }
    }
}
