dependencies {
    compileOnly(project(":common"))
    compileOnly(libs.paper.api.modern)
    compileOnly(libs.jetbrains.annotations)
}

// Brigadier exists only on Paper 1.20.6+, whose JVM floor is 21 -- so Java 21
// bytecode here is loadable on every server that can possess the capability.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
