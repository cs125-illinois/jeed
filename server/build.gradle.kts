import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    jcenter()
}
plugins {
    kotlin("jvm")
}
tasks.test {
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
}
dependencies {
    implementation(kotlin("stdlib"))

    api(project(":core"))

    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.3.2")
}
