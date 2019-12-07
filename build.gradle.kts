import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "1.3.61"
    kotlin("jvm") version kotlinVersion apply false
    kotlin("kapt") version kotlinVersion apply false
    maven
    id("com.github.ben-manes.versions") version "0.27.0"
    id("io.gitlab.arturbosch.detekt") version "1.2.2"
}
allprojects {
    repositories {
        mavenCentral()
        jcenter()
        maven(url = "https://jitpack.io")
    }
    tasks.withType<KotlinCompile> {
        val javaVersion = JavaVersion.VERSION_1_8.toString()
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
        kotlinOptions {
            jvmTarget = javaVersion
        }
    }
}
subprojects {
    tasks.withType<Test> {
        enableAssertions = true
    }
}
tasks.dependencyUpdates {
    resolutionStrategy {
        componentSelection {
            all {
                if (listOf("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea", "eap").any { qualifier ->
                    candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-+]*"))
                }) {
                    reject("Release candidate")
                }
            }
        }
    }
}
detekt {
    toolVersion = "1.1.1"
    input = files("core/src/main/kotlin", "server/src/main/kotlin")
    config = files("config/detekt/detekt.yml")
}
