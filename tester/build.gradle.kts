plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":common"))
    compileOnly(project(":core"))
    // v5: the era-parity oracle and the knockback expectations derive from the
    // kernel motion authority directly — no third re-implementation (spec §12.7).
    // Kernel classes ship (unrelocated) inside the Mental jar at runtime.
    compileOnly(project(":kernel"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.netty.all)
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
