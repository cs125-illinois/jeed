import org.jetbrains.kotlin.gradle.dsl.Coroutines

plugins {
    kotlin("jvm")
    kotlin("kapt")
}
tasks.test {
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
}
dependencies {
    val ktorVersion = "1.2.2"

    implementation(kotlin("stdlib"))

    implementation(project(":core"))
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    implementation("com.squareup.moshi:moshi:1.8.0")
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.8.0")
    implementation("com.ryanharter.ktor:ktor-moshi:1.0.1")

    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.3.2")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}
