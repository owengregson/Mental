pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Mental"

include(":api", ":common", ":core", ":compat-folia", ":compat-brigadier", ":tester")
project(":compat-folia").projectDir = file("compat/folia")
project(":compat-brigadier").projectDir = file("compat/brigadier")
