// java-library, UTF-8/-parameters compilation, and JUnit-platform test wiring
// all come from the root build's subprojects block; only the kernel-specific
// pieces live here.
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// The kernel realm may never see Bukkit or PacketEvents — this is the
// architecture's enforcement edge, so fail the build if anything sneaks in.
configurations.all {
    resolutionStrategy.eachDependency {
        require(!requested.group.startsWith("io.papermc")) { "kernel must stay Bukkit-free" }
        require(!requested.group.startsWith("com.github.retrooper")) { "kernel must stay PacketEvents-free" }
    }
}
