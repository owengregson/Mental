plugins {
    `maven-publish`
}

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
    // The version lives in the catalog; the classifier cannot (TOML catalogs
    // carry no classifier), so the coordinate is assembled here.
    japicmp("com.github.siom79.japicmp:japicmp:${libs.versions.japicmp.get()}:jar-with-dependencies")
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mentalApi") {
            from(components["java"])
            groupId = "me.vexmc"
            artifactId = "mental-api"
            version = providers.gradleProperty("apiVersion").get()
            pom {
                name.set("mental-api")
                description.set("Mental's public combat-integration surface (API generation 3)")
            }
        }
    }
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
    val supertypeClasspath = configurations.named("compileClasspath")

    inputs.file(apiBaselineJar)
    inputs.file(newJar)
    inputs.files(supertypeClasspath)
    outputs.dir(reportDir)

    doFirst {
        val dir = reportDir.get().asFile
        dir.mkdirs()
        // --only-modified keeps the report to the surface that actually
        // changed; --error-on-binary-incompatibility is the gate (non-zero
        // exit => build fails). Both jars resolve their supertypes against
        // the module's own compile classpath (the 1.17.1 Paper floor +
        // annotations) rather than --ignore-missing-classes: that flag's
        // startup WARNING banner leaked into the jvmdg warning gate's
        // GLOBAL console capture under parallel execution and failed the
        // 2.4.1 release job — and a real classpath is the stronger check
        // anyway (an unresolvable supertype now fails loudly here).
        val cp = supertypeClasspath.get().asPath
        args(
            "--old", apiBaselineJar.absolutePath,
            "--new", newJar.get().asFile.absolutePath,
            "--old-classpath", cp,
            "--new-classpath", cp,
            "--only-modified",
            "--error-on-binary-incompatibility",
            "--html-file", dir.resolve("api-compat.html").absolutePath,
        )
    }
}

tasks.named("check") {
    dependsOn(apiCompat)
}
