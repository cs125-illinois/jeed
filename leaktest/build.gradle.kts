plugins {
    kotlin("jvm")
    application
    id("org.jmailen.kotlinter")
    id("io.gitlab.arturbosch.detekt")
}
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("ch.qos.logback:logback-classic:1.4.5")
    implementation("io.github.microutils:kotlin-logging:3.0.4")
}
application {
    @Suppress("DEPRECATION")
    mainClassName = "edu.illinois.cs.cs125.jeed.leaktest.MainKt"
}
tasks {
    "run"(JavaExec::class) {
        jvmArgs(
            "-ea",
            "-Xms128m",
            "-Xmx128m",
            "--enable-preview",
            "--illegal-access=permit",
            "-verbose:class"
        )
    }
}
