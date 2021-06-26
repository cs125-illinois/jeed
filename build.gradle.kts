plugins {
    kotlin("jvm") version "1.5.20" apply false
    kotlin("kapt") version "1.5.20" apply false
    id("org.jmailen.kotlinter") version "3.4.5" apply false
    id("com.github.ben-manes.versions") version "0.39.0"
    id("io.gitlab.arturbosch.detekt") version "1.17.1"
}
allprojects {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.google.com/")
        maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
    }
}
subprojects {
    group = "com.github.cs125-illinois.jeed"
    version = "2021.6.8"
    tasks.withType<Test> {
        useJUnitPlatform()
        enableAssertions = true
        // Fix encoding bug on Windows
        jvmArgs("-Dfile.encoding=UTF-8")
    }
}
tasks.dependencyUpdates {
    fun String.isNonStable() = !(
        listOf("RELEASE", "FINAL", "GA").any { toUpperCase().contains(it) }
            || "^[0-9,.v-]+(-r)?$".toRegex().matches(this)
        )
    rejectVersionIf { candidate.version.isNonStable() }
    gradleReleaseChannel = "current"
}
detekt {
    buildUponDefaultConfig = true
}
tasks.register("check") {
    dependsOn("detekt")
}
