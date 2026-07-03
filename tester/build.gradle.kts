import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.shadow)
}

// The tester must load on every supported server, so its plugin.yml api-version follows the SAME floor as
// Mental's — read from support-matrix.json (the single source), not hardcoded. On 1.13.2 the server rejects
// any plugin whose api-version isn't exactly the floor (CraftMagicNumbers.checkSupported), so a stale "1.17"
// here would fail the whole legacy boot.
@Suppress("UNCHECKED_CAST")
val floorApi: String =
    (JsonSlurper().parse(rootProject.layout.projectDirectory.file("support-matrix.json").asFile)
        as Map<String, Any>)["floorApi"] as String

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":platform"))
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
    val props = mapOf(
        "version" to project.version.toString(),
        "apiVersion" to floorApi,
    )
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
