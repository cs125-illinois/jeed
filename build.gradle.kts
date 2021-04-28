import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "1.5.0"
    kotlin("jvm") version kotlinVersion apply false
    kotlin("kapt") version kotlinVersion apply false
    id("org.jmailen.kotlinter") version "3.4.0" apply false
    id("com.github.ben-manes.versions") version "0.38.0"
    id("io.gitlab.arturbosch.detekt") version "1.16.0"
}
allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
        maven("https://maven.google.com/")
        jcenter()
    }
}
subprojects {
    group = "com.github.cs125-illinois.jeed"
    version = "2021.4.5"
    tasks.withType<KotlinCompile> {
        val javaVersion = JavaVersion.VERSION_1_8.toString()
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
        kotlinOptions {
            jvmTarget = javaVersion
        }
    }
    tasks.withType<Test> {
        enableAssertions = true
    }
}
tasks.dependencyUpdates {
    resolutionStrategy {
        componentSelection {
            all {
                if (listOf("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea", "eap", "release").any { qualifier ->
                        candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-+]*"))
                    }) {
                    reject("Release candidate")
                }
            }
        }
    }
    gradleReleaseChannel = "current"
}
detekt {
    buildUponDefaultConfig = true
}
tasks.register("check") {
    dependsOn("detekt")
}
