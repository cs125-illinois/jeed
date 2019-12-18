import java.util.*

group = "edu.illinois.cs.cs125"
version = "2019.12.5"

plugins {
    kotlin("jvm")
    kotlin("kapt")
    application
    id("com.github.johnrengelman.shadow") version "5.2.0"
    id("com.palantir.docker") version "0.22.1"
}
dependencies {
    val ktorVersion = "1.2.6"

    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.9.2")

    implementation(project(":core"))
    implementation(kotlin("stdlib"))
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("org.mongodb:mongodb-driver:3.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.3")
    implementation("com.squareup.moshi:moshi-kotlin-codegen:1.9.2")
    implementation("com.ryanharter.ktor:ktor-moshi:1.0.1")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("com.uchuhimo:konf-core:0.21.0")
    implementation("com.uchuhimo:konf-yaml:0.21.0")
    implementation("io.github.microutils:kotlin-logging:1.7.8")
    implementation("com.google.api-client:google-api-client:1.30.6")

    val kotlintestVersion = "3.4.2"
    testImplementation("io.kotlintest:kotlintest-runner-junit5:$kotlintestVersion")
    testImplementation("io.kotlintest:kotlintest-assertions-ktor:$kotlintestVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}
val mainClass = "edu.illinois.cs.cs125.jeed.server.MainKt"
application {
    mainClassName = mainClass
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
    environment["MONGODB"] = "mongodb://localhost:27038/cs125"
}
tasks.jar {
    manifest {
        attributes["Main-Class"] = mainClass
    }
}
task("createProperties") {
    doLast {
        val properties = Properties().also {
            it["version"] = project.version.toString()
        }
        File(projectDir, "src/main/resources/edu.illinois.cs.cs125.jeed.server.version").printWriter().use { properties.store(it, null) }
    }
}
tasks.processResources {
    dependsOn("createProperties")
}
