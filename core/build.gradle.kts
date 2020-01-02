import java.util.Properties

plugins {
    kotlin("jvm")
    kotlin("kapt")
    antlr
    java
    maven
    `maven-publish`
    id("org.jmailen.kotlinter")
}

group = "com.github.cs125-illinois"
version = "2019.12.6"

dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.9.2")

    antlr("org.antlr:antlr4:4.7.2")

    implementation(kotlin("stdlib"))
    implementation(kotlin("compiler-embeddable"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.3")
    implementation("com.puppycrawl.tools:checkstyle:8.28")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.3.61")
    implementation("com.github.jknack:handlebars:4.1.2")
    implementation("com.squareup.moshi:moshi:1.9.2")
    implementation("org.ow2.asm:asm:7.2")
    implementation("org.ow2.asm:asm-util:7.2")
    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("io.github.microutils:kotlin-logging:1.7.8")
    implementation("io.github.classgraph:classgraph:4.8.59")
    implementation("net.java.dev.jna:jna:5.5.0")

    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
}
tasks.test {
    useJUnitPlatform()
    if (JavaVersion.current() >= JavaVersion.VERSION_11) {
        jvmArgs("-ea", "-Xmx1G", "--enable-preview")
    } else {
        jvmArgs("-ea", "-Xmx1G")
    }
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
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
        File(projectDir, "src/main/resources/edu.illinois.cs.cs125.jeed.core.version").printWriter().use { properties.store(it, null) }
    }
}
kapt {
    includeCompileClasspath = false
}
