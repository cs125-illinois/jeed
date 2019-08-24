plugins {
    kotlin("jvm")
    kotlin("kapt")
    id("com.github.johnrengelman.shadow") version "5.1.0"
}
tasks.test {
    useJUnitPlatform()
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
}
dependencies {
    val ktorVersion = "1.2.2"

    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.8.0")

    implementation(project(":core"))
    implementation(kotlin("stdlib"))
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("org.mongodb:mongodb-driver:3.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.0-M2")
    implementation("com.squareup.moshi:moshi:1.8.0")
    implementation("com.ryanharter.ktor:ktor-moshi:1.0.1")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("com.uchuhimo:konf-core:0.15.1")
    implementation("com.uchuhimo:konf-yaml:0.15.1")
    implementation("io.github.microutils:kotlin-logging:1.7.6")

    val kotlintestVersion = "3.4.0"
    testImplementation("io.kotlintest:kotlintest-runner-junit5:$kotlintestVersion")
    testImplementation("io.kotlintest:kotlintest-assertions-ktor:$kotlintestVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}
tasks.jar {
    manifest {
        attributes["Main-Class"] = "edu.illinois.cs.cs125.jeed.server.MainKt"
    }
}
