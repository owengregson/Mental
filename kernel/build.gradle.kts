plugins { `java-library` }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

// The kernel realm may never see Bukkit or PacketEvents — this is the
// architecture's enforcement edge, so fail the build if anything sneaks in.
configurations.all {
    resolutionStrategy.eachDependency {
        require(!requested.group.startsWith("io.papermc")) { "kernel must stay Bukkit-free" }
        require(!requested.group.startsWith("com.github.retrooper")) { "kernel must stay PacketEvents-free" }
    }
}
