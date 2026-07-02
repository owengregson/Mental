allprojects {
    group = "me.vexmc"
    // The single version home is gradle.properties; every module reads it here.
    version = providers.gradleProperty("version").get()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://repo.codemc.io/repository/maven-snapshots/")
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        // Build JDK, not a support-matrix member: the toolchain that COMPILES the
        // plugin (25 = the newest matrix runtime's required JDK). The per-entry
        // RUNTIME JDKs live only in support-matrix.json.
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile>().configureEach {
        // Compile floor, not a support-matrix member: the bytecode target that
        // keeps the jar loadable on the matrix FLOOR (Java 17, Paper 1.17.1). The
        // matrix's own JDK list lives only in support-matrix.json.
        options.release.set(17)
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
