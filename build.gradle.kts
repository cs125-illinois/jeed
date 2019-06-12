import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    jcenter()
}
plugins {
    kotlin("jvm") version "1.3.31"
    java
    antlr
    id("com.github.ben-manes.versions") version "0.21.0"
    kotlin("kapt") version "1.3.31"
}
tasks.withType<KotlinCompile> {
    val javaVersion = JavaVersion.VERSION_1_8.toString()
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    kotlinOptions {
        jvmTarget = javaVersion
    }
}
tasks.test {
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
    enableAssertions = true
}
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    antlr("org.antlr:antlr4:4.7.2")

    implementation("com.puppycrawl.tools:checkstyle:8.21")
    api("org.jetbrains.kotlin:kotlin-reflect:1.3.31")

    implementation("org.slf4j:slf4j-api:1.7.26")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("io.github.microutils:kotlin-logging:1.6.26")

    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.3.2")
}
tasks.generateGrammarSource {
    outputDirectory = File("src/main/java/edu/illinois/cs/cs125/jeed/antlr")
    arguments.addAll(listOf("-visitor", "-package", "edu.illinois.cs.cs125.jeed.antlr", "-Xexact-output-dir"))
}
tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
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
