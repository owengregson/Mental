plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":common"))
    compileOnly(project(":core"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    implementation(libs.reflection.remapper)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveBaseName.set("MentalTester")
    archiveClassifier.set("")

    relocate("xyz.jpenilla.reflectionremapper", "me.vexmc.mental.tester.lib.reflectionremapper")
    relocate("net.fabricmc.mappingio", "me.vexmc.mental.tester.lib.mappingio")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
