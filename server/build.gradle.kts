plugins {
    kotlin("jvm")
    kotlin("kapt")
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
    implementation("com.squareup.moshi:moshi:1.8.0")
    implementation("com.ryanharter.ktor:ktor-moshi:1.0.1")
    implementation("ch.qos.logback:logback-classic:1.2.3")

    val kotlintestVersion = "3.3.2"
    testImplementation("io.kotlintest:kotlintest-runner-junit5:$kotlintestVersion")
    testImplementation("io.kotlintest:kotlintest-assertions-ktor:$kotlintestVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}
