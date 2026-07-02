plugins {
    // The Scheduling TCK (spec §12.4) is published as a test fixture: a
    // backend-agnostic conformance suite every Scheduling implementation must
    // pass. Platform's own test runs it against BukkitScheduling; compat-folia
    // consumes the same fixture for its live Folia run (Task 5.6).
    id("java-test-fixtures")
}

dependencies {
    // Platform is the Bukkit-facing foundation layer: it depends on the pure
    // kernel (spec §1) and compiles against the floor API. Kernel is exposed
    // transitively so platform consumers see the era-math types the boot-time
    // adapters build components from (e.g. EraReach).
    api(project(":kernel"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)

    // The TCK is written against the Scheduling interface + JUnit; its abstract
    // test cases need the floor API (Entity) and Jupiter on the fixture
    // classpath, exposed to fixture consumers via the API configuration.
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesCompileOnly(libs.paper.api.floor)
    testFixturesCompileOnly(libs.jetbrains.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.paper.api.floor)
}
