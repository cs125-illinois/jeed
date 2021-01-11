import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "1.4.21-2"
    kotlin("jvm") version kotlinVersion apply false
    kotlin("kapt") version kotlinVersion apply false
    id("org.jmailen.kotlinter") version "3.3.0" apply false
    id("com.github.ben-manes.versions") version "0.36.0"
    id("io.gitlab.arturbosch.detekt") version "1.15.0"
}
allprojects {
    repositories {
        mavenCentral()
        jcenter()
        maven(url = "https://jitpack.io")
    }
}
subprojects {
    group = "com.github.cs125-illinois.jeed"
    version = "2021.1.0"
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
    input = files("core/src/main/kotlin", "server/src/main/kotlin", "containerrunner/src/main/kotlin")
    buildUponDefaultConfig = true
}
tasks.register("check") {
    dependsOn("detekt")
}
