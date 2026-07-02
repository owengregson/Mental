/* ────────────────────────────────────────────────────────────────────────
 *  API binary-compatibility gate (R12).
 *
 *  The public :api surface may only GROW. `apiCompat` compares the freshly
 *  built jar against the committed v2.2.2 baseline (gradle/api-baseline/) with
 *  japicmp and fails on ANY binary-incompatible change — a removed class,
 *  a removed or re-signed method. Additive changes (a new default method such
 *  as apiVersion(), a new event) are binary compatible and pass. It is wired
 *  into `check`, so `./gradlew build` enforces it.
 * ──────────────────────────────────────────────────────────────────────── */

// The japicmp CLI (self-contained fat jar) runs via JavaExec, so the gate is
// agnostic to the Gradle version. Created before `dependencies` references it.
val japicmp: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    japicmp("com.github.siom79.japicmp:japicmp:0.23.1:jar-with-dependencies")
}

val apiBaselineJar = rootProject.layout.projectDirectory.file("gradle/api-baseline/api-2.2.2.jar").asFile

val apiCompat = tasks.register<JavaExec>("apiCompat") {
    group = "verification"
    description = "Fails on binary-incompatible changes to the :api surface versus the " +
            "committed v2.2.2 baseline (additive-only policy)."
    dependsOn(tasks.named("jar"))
    classpath = japicmp
    mainClass.set("japicmp.JApiCmp")

    val newJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val reportDir = layout.buildDirectory.dir("reports/japicmp")

    inputs.file(apiBaselineJar)
    inputs.file(newJar)
    outputs.dir(reportDir)

    doFirst {
        val dir = reportDir.get().asFile
        dir.mkdirs()
        // --only-modified keeps the report to the surface that actually
        // changed; --error-on-binary-incompatibility is the gate (non-zero
        // exit => build fails); the baseline compiled against a different
        // Paper build, so unresolved supertypes are ignored, not failed.
        args(
            "--old", apiBaselineJar.absolutePath,
            "--new", newJar.get().asFile.absolutePath,
            "--only-modified",
            "--error-on-binary-incompatibility",
            "--ignore-missing-classes",
            "--html-file", dir.resolve("api-compat.html").absolutePath,
        )
    }
}

tasks.named("check") {
    dependsOn(apiCompat)
}
