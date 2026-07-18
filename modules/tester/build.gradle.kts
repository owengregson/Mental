import groovy.json.JsonSlurper
import xyz.wagyourtail.jvmdg.gradle.JVMDowngraderExtension

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.jvmdowngrader)
}

// The tester must load on every supported server, so its plugin.yml api-version follows the SAME floor as
// Mental's — read from project/support-matrix.json (the single source), not hardcoded. On 1.13.2 the server rejects
// any plugin whose api-version isn't exactly the floor (CraftMagicNumbers.checkSupported), so a stale "1.17"
// here would fail the whole legacy boot.
@Suppress("UNCHECKED_CAST")
val floorApi: String =
    (JsonSlurper().parse(rootProject.layout.projectDirectory.file("project/support-matrix.json").asFile)
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
    // Staged out of build/libs: the shaded v61 tester jar is now an intermediate of the
    // mega-jar pipeline (D-4). The final glob-visible MentalTester-<version>.jar is the
    // mega jar emitted by the shade task below.
    archiveClassifier.set("modern")
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))

    relocate("xyz.jpenilla.reflectionremapper", "me.vexmc.mental.tester.lib.reflectionremapper")
    relocate("net.fabricmc.mappingio", "me.vexmc.mental.tester.lib.mappingio")
}

/* ────────────────────────────────────────────────────────────────────────
 *  The tester mega-jar pipeline (full-range campaign D-4/D-8).
 *
 *  The tester ships the SAME Multi-Release mega-jar shape as core so it loads on
 *  the Java-8 legacy servers (base v52) and keeps running original v61 bytecode on
 *  modern ones. Its jvmdg runtime relocates under a DISTINCT prefix
 *  (me/vexmc/mental/tester/lib/jvmdg/) — the load-bearing D-8 isolation: two
 *  downgraded plugins sharing a same-FQN pruned runtime cross-link and fail on the
 *  server's shared class cache (both failure modes were reproduced live in the spike).
 *
 *  The downgrade classpath is the default compileClasspath: it carries the EXTERNAL
 *  supertypes the shadowJar does not bundle — paper-api, netty, and the kernel/api/
 *  platform/core classes the Mental jar provides at runtime (all compileOnly here).
 *  reflection-remapper + mapping-io are bundled, so they self-resolve from the jar.
 * ──────────────────────────────────────────────────────────────────────── */

/** Treats any jvmdowngrader warning during [this] task as a build failure (see core). */
fun Jar.failOnJvmdgWarnings() {
    val captured = StringBuilder()
    val sink = StandardOutputListener { text -> captured.append(text) }
    val logSink = layout.buildDirectory.file("jvmdg-stage/${name}-output.log")
    doFirst {
        logging.addStandardOutputListener(sink)
        logging.addStandardErrorListener(sink)
    }
    doLast {
        logging.removeStandardOutputListener(sink)
        logging.removeStandardErrorListener(sink)
        val text = captured.toString()
        val file = logSink.get().asFile
        file.parentFile.mkdirs()
        file.writeText(text)
        // JDK-24+ JEP-498 Unsafe deprecation notes from parallel test JVMs leak into
        // this global capture and are not jvmdg output — filtered (see core's twin).
        val jvmNoise = Regex("sun\\.misc\\.Unsafe|Please consider reporting this to the maintainers")
        val warnings = text.lines().filter { line ->
            Regex("(?i)\\b(warn|warning|error)\\b").containsMatchIn(line)
        }.filterNot { line -> jvmNoise.containsMatchIn(line) }
        if (warnings.isNotEmpty()) {
            throw GradleException(
                "jvmdowngrader emitted ${warnings.size} warning/error line(s) during '$name' — the " +
                        "full-range campaign treats these as build failures. First lines:\n" +
                        warnings.take(20).joinToString("\n") { "    $it" } +
                        "\nFull capture: ${file.absolutePath}")
        }
    }
}

val jvmdg = extensions.getByType<JVMDowngraderExtension>()

val downgradeMegaJar = jvmdg.defaultTask
downgradeMegaJar.configure {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    // base = v52 (Java 8) + versions/17 = the untouched original v61. multiReleaseOriginal
    // is set WITHOUT multiReleaseVersions: jvmdg 1.3.6 treats -mro (keep original) and
    // -mr <ver> (keep a semi-downgraded intermediate) as MUTUALLY EXCLUSIVE — requesting a
    // v60/versions-16 tier DROPS the original v61, which would silently downgrade the modern
    // path (voiding D-1). Keeping the original is the D-1-critical decision; the v60 tier
    // 1.16.5-on-Java-16 would have used is a Phase-2 concern (it reads base v52 without it —
    // fully functional, since v52 is the tested legacy base). See the Phase 1 outcome log.
    multiReleaseOriginal.set(true)
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    archiveBaseName.set("MentalTester")
    archiveClassifier.set("downgraded")
    // The warning capture listens on Gradle's GLOBAL console stream, so a
    // parallel task's output can land in the window (the 2.4.1 release job
    // failed on japicmp's banner this way — see core's twin comment).
    mustRunAfter(":api:apiCompat")
    failOnJvmdgWarnings()
}

val megaJar = jvmdg.defaultShadeTask
megaJar.configure {
    inputFile.set(downgradeMegaJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    shadePath.set { "me/vexmc/mental/tester/lib/jvmdg/" }
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    archiveBaseName.set("MentalTester")
    archiveClassifier.set("")
    mustRunAfter(":api:apiCompat") // same global-capture caveat as downgradeJar above
    failOnJvmdgWarnings()
}

tasks.build {
    dependsOn(megaJar)
}
