dependencies {
    // Platform is the Bukkit-facing foundation layer: it depends on the pure
    // kernel (spec §1) and compiles against the floor API. Kernel is exposed
    // transitively so platform consumers see the era-math types the boot-time
    // adapters build components from (e.g. EraReach).
    api(project(":kernel"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.paper.api.floor)
}
