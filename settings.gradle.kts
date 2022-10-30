rootProject.name = "jeed"
include("core", "server", "containerrunner", "leaktest")
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
