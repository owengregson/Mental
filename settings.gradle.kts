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

include(":api", ":platform", ":core", ":compat-folia", ":tester")
include("kernel")

// The build tree lives under modules/ to keep the repo root uncluttered. Every
// project PATH (":api", ":core", …) is unchanged — inter-module dependencies and
// the gradle command line are unaffected; only the on-disk locations move.
project(":api").projectDir = file("modules/api")
project(":platform").projectDir = file("modules/platform")
project(":core").projectDir = file("modules/core")
project(":compat-folia").projectDir = file("modules/compat/folia")
project(":tester").projectDir = file("modules/tester")
project(":kernel").projectDir = file("modules/kernel")
