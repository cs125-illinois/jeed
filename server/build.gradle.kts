import java.io.File
import java.io.StringWriter
import java.util.Properties

plugins {
    kotlin("jvm")
    kotlin("kapt")
    application
    `maven-publish`
    id("com.github.johnrengelman.shadow") version "6.1.0"
    id("com.palantir.docker") version "0.25.0"
    id("org.jmailen.kotlinter")
}
repositories {
    maven(url = "https://maven.google.com/")
}
dependencies {
    val ktorVersion = "1.5.1"

    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.11.0")

    implementation(project(":core"))
    implementation(project(":cs125"))
    implementation(kotlin("stdlib"))
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("org.mongodb:mongodb-driver:3.12.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.2")
    implementation("com.squareup.moshi:moshi-kotlin-codegen:1.11.0")
    implementation("com.github.cs125-illinois:ktor-moshi:1.0.3")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("com.uchuhimo:konf-core:1.0.0")
    implementation("com.uchuhimo:konf-yaml:1.0.0")
    implementation("io.github.microutils:kotlin-logging:2.0.4")
    implementation("com.google.api-client:google-api-client:1.31.2")

    val kotestVersion = "4.3.2"
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-ktor:$kotestVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}
application {
    @Suppress("DEPRECATION")
    mainClassName = "edu.illinois.cs.cs125.jeed.server.MainKt"
}
docker {
    name = "cs125/jeed"
    tag("latest", "cs125/jeed:latest")
    tag(version.toString(), "cs125/jeed:$version")
    files(tasks["shadowJar"].outputs)
}
tasks.test {
    useJUnitPlatform()
    if (JavaVersion.current() >= JavaVersion.VERSION_11) {
        jvmArgs("-ea", "-Xmx1G", "--enable-preview")
    } else {
        jvmArgs("-ea", "-Xmx1G")
    }
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
    environment["MONGODB"] = "mongodb://localhost:27017/cs125"
    environment["SEMESTER"] = "Spring2020"
    environment["JEED_USE_CACHE"] = "true"
}
task("createProperties") {
    doLast {
        val properties = Properties().also {
            it["version"] = project.version.toString()
        }
        File(projectDir, "src/main/resources/edu.illinois.cs.cs125.jeed.server.version")
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
kapt {
    useBuildCache = true
    includeCompileClasspath = false
}
publishing {
    publications {
        create<MavenPublication>("server") {
            from(components["java"])
        }
    }
}

