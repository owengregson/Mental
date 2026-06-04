plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    api(project(":api"))
    api(project(":common"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    implementation(libs.packetevents.spigot)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.paper.api.floor)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    dependsOn(":compat-folia:classes", ":compat-brigadier:classes")
    archiveBaseName.set("Mental")
    archiveClassifier.set("")

    from(project(":compat-folia").sourceSets.main.get().output)
    from(project(":compat-brigadier").sourceSets.main.get().output)

    relocate("com.github.retrooper.packetevents", "me.vexmc.mental.lib.packetevents.api")
    relocate("io.github.retrooper.packetevents", "me.vexmc.mental.lib.packetevents.impl")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
