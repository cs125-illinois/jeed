import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.10" apply false
    kotlin("kapt") version "1.6.10" apply false
    id("org.jmailen.kotlinter") version "3.8.0" apply false
    id("com.github.ben-manes.versions") version "0.42.0"
    id("io.gitlab.arturbosch.detekt") version "1.19.0"
}
allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.google.com/")
        maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
    }
}
subprojects {
    group = "com.github.cs125-illinois.jeed"
    version = "2022.3.1"
    tasks.withType<Test> {
        useJUnitPlatform()
        enableAssertions = true
        jvmArgs(
            "-Dfile.encoding=UTF-8", // Fix encoding bug on Windows
            "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
        )
    }
    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_16.toString()
        }
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
