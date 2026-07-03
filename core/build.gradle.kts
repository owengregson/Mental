import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.service.DownloadsAPIService

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

evaluationDependsOn(":tester")

/* ────────────────────────────────────────────────────────────────────────
 *  support-matrix.json — THE single machine-readable source of truth for the
 *  supported platform matrix. Every version, its JDK, its CI lane, the OCM
 *  pin, and the plugin.yml api-version floor come from here; no Minecraft
 *  version or JDK literal lives anywhere else in the build (Task 5.3).
 * ──────────────────────────────────────────────────────────────────────── */

/** One supported-platform row from support-matrix.json. */
class SupportEntry(
    val version: String,
    val jdk: Int,
    val platform: String,
    val ci: String,
    val suites: String,
    val serverFlags: List<String>,
)

val supportMatrixFile = rootProject.layout.projectDirectory.file("support-matrix.json").asFile

@Suppress("UNCHECKED_CAST")
val supportMatrix: Map<String, Any> =
    JsonSlurper().parse(supportMatrixFile) as Map<String, Any>

/** The plugin.yml api-version floor (Bukkit compatibility level). */
val floorApi: String = supportMatrix["floorApi"] as String

@Suppress("UNCHECKED_CAST")
val supportEntries: List<SupportEntry> =
    (supportMatrix["entries"] as List<Map<String, Any>>).map { entry ->
        SupportEntry(
            version = entry["version"] as String,
            jdk = (entry["jdk"] as Number).toInt(),
            platform = entry["platform"] as String,
            ci = entry["ci"] as String,
            // The suite tier the tester runs on this version (full | boot | combat-smoke); the boot tier
            // is the legacy-backport classload/boot-safety suite. serverFlags are extra JVM args the legacy
            // Paper builds need (e.g. -DPaper.IgnoreJavaVersion=true); absent ⇒ none.
            suites = entry["suites"] as String,
            serverFlags = (entry["serverFlags"] as? List<String>) ?: emptyList(),
        )
    }

// Paper is the only live integration platform today; the Folia entry
// (platform: "folia") arrives with Task 5.6's Folia matrix wiring.
val paperEntries: List<SupportEntry> = supportEntries.filter { it.platform == "paper" }

dependencies {
    api(project(":api"))
    // v5 rewrite (spec §1): core depends on the Bukkit-facing platform layer
    // (scheduling, capabilities, boot-time NMS resolvers) and the pure kernel.
    // Platform re-exports kernel transitively; both are shaded into Mental.jar.
    api(project(":platform"))
    implementation(project(":kernel"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    implementation(libs.packetevents.spigot)
    implementation(libs.bstats.bukkit)
    // Adventure shaded into the jar (relocated below) so its legacy-string
    // serializer exists on servers older than Paper 1.16.5, where net.kyori is
    // absent. The BOM pins every module to the floor-API version (4.9.3); the
    // jetbrains-annotations it drags in transitively are compile-time only.
    implementation(platform(libs.adventure.bom))
    implementation(libs.adventure.api) { exclude(group = "org.jetbrains", module = "annotations") }
    implementation(libs.adventure.serializer.legacy) { exclude(group = "org.jetbrains", module = "annotations") }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.paper.api.floor)
    // The server supplies netty at runtime; packet-event construction in
    // unit tests needs a real allocator on the classpath.
    testRuntimeOnly(libs.netty.all)
}

tasks.processResources {
    // api-version derives from support-matrix.json's floorApi — the descriptor
    // owns the Bukkit compatibility floor, not a hardcoded plugin.yml literal.
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
    dependsOn(":compat-folia:classes")
    archiveBaseName.set("Mental")
    archiveClassifier.set("")

    from(project(":compat-folia").sourceSets.main.get().output)

    relocate("com.github.retrooper.packetevents", "me.vexmc.mental.lib.packetevents.api")
    relocate("io.github.retrooper.packetevents", "me.vexmc.mental.lib.packetevents.impl")
    relocate("org.bstats", "me.vexmc.mental.lib.bstats")
    // Relocate the shaded Adventure (adventure-api + adventure-key + examination,
    // all under net.kyori) so it never collides with — or falls back to — Paper's
    // native Adventure. Every net.kyori reference in core resolves to this copy;
    // no Component crosses a Bukkit boundary (TextPort is the only sink), so on
    // modern servers this copy is inert and on legacy it is the only one present.
    relocate("net.kyori", "me.vexmc.mental.lib.adventure")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

/* ────────────────────────────────────────────────────────────────────────
 *  Relocation-rot gate (review item F-BP3).
 *
 *  The legacy backport shades Adventure (net.kyori.*) into the jar RELOCATED to
 *  me.vexmc.mental.lib.adventure, so its legacy-string serializer exists on
 *  servers below Paper 1.16.5 (where net.kyori is absent) while never colliding
 *  with — or falling back to — Paper's native Adventure on modern servers. That
 *  whole safety property rests on the relocation holding: an un-relocated
 *  net.kyori reference would bind to Paper's own Adventure on modern servers
 *  (silent behaviour drift) and NoClassDefFoundError on legacy (a hard crash on
 *  the versions the shade exists to serve). The "no Component crosses a Bukkit
 *  boundary; every net.kyori is the relocated copy" invariant is grep-proven at
 *  authoring time; this task makes it a build gate so it cannot rot unnoticed.
 *
 *  Two scans over the produced shadowJar (both, per F-BP3):
 *   1. ENTRY NAMES (mandatory) — no jar entry may live under net/kyori/: an
 *      un-relocated Adventure class or resource file. Cheap; catches a whole
 *      package slipping the relocator.
 *   2. CLASS REFERENCES (the stronger, constant-pool form) — no .class entry
 *      OUTSIDE the relocated prefix (me/vexmc/mental/lib/) may contain the token
 *      "net/kyori" (a type reference) or "net.kyori" (a reflection/service
 *      string). Class bytes are read as ISO-8859-1 (a lossless byte↔char map), so
 *      a substring match is exactly a constant-pool byte-sequence match. This
 *      catches a single stray reference the entry-name scan would miss.
 *
 *  A clean shade has ZERO of either (Mental references Adventure only through the
 *  relocated copy — TextPort is the sole sink and every reference is rewritten to
 *  me.vexmc.mental.lib.adventure). Any hit fails the build.
 * ──────────────────────────────────────────────────────────────────────── */
val verifyRelocation = tasks.register("verifyRelocation") {
    group = "verification"
    description = "Fails if any un-relocated net/kyori entry or class reference survives the shade " +
            "(the shaded Adventure must stay under me.vexmc.mental.lib — review F-BP3)."
    dependsOn(tasks.shadowJar)
    val jarFile = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        val relocatedPrefix = "me/vexmc/mental/lib/"
        val entryViolations = mutableListOf<String>()
        val refViolations = mutableListOf<String>()
        ZipFile(jarFile.get().asFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                // Scan 1: no entry may live under the un-relocated net/kyori/ path.
                if (name.startsWith("net/kyori/")) {
                    entryViolations.add(name)
                }
                // Scan 2: class bytes outside the relocated prefix must not name net.kyori.
                if (name.endsWith(".class") && !name.startsWith(relocatedPrefix)) {
                    val text = zip.getInputStream(entry).use { input ->
                        String(input.readBytes(), Charsets.ISO_8859_1)
                    }
                    if (text.contains("net/kyori") || text.contains("net.kyori")) {
                        refViolations.add(name)
                    }
                }
            }
        }
        if (entryViolations.isNotEmpty() || refViolations.isNotEmpty()) {
            val message = buildString {
                append("Relocation rot: un-relocated net.kyori survived the shade.\n")
                if (entryViolations.isNotEmpty()) {
                    append("  ${entryViolations.size} entry name(s) under net/kyori/:\n")
                    entryViolations.take(20).forEach { append("    - $it\n") }
                }
                if (refViolations.isNotEmpty()) {
                    append("  ${refViolations.size} class(es) outside $relocatedPrefix referencing net.kyori:\n")
                    refViolations.take(20).forEach { append("    - $it\n") }
                }
                append("The shaded Adventure MUST relocate to me.vexmc.mental.lib.adventure; check the ")
                append("relocate(\"net.kyori\", …) rule and any net.kyori use that crosses a Bukkit boundary.")
            }
            throw GradleException(message)
        }
        logger.lifecycle(
            "[relocation] clean — no un-relocated net.kyori in ${jarFile.get().asFile.name}.")
    }
}

tasks.named("check") {
    dependsOn(verifyRelocation)
}

/* ────────────────────────────────────────────────────────────────────────
 *  Real-server integration matrix
 *
 *  For every paper entry in support-matrix.json, a real Paper server boots
 *  with the Mental and MentalTester jars installed; the tester runs its suite
 *  in-process, writes "PASS nonce=<n>", and shuts the server down; the paired
 *  check task fails the build unless the result carries THIS run's nonce and
 *  reads PASS. The per-entry JDK also comes from the descriptor.
 * ──────────────────────────────────────────────────────────────────────── */

val javaToolchains = extensions.getByType<JavaToolchainService>()
val testerShadowJar = project(":tester").tasks.named<AbstractArchiveTask>("shadowJar")

tasks.named<RunServer>("runServer") {
    enabled = false
    description = "Disabled — use integrationTest or integrationTestMatrix."
}

val checkTasks = mutableListOf<TaskProvider<Task>>()
var previousCheck: TaskProvider<Task>? = null

/**
 * One live-server suite: boots Paper [version] in run/[runDirName] with
 * Mental, the tester, and any [extraPluginJars]; the paired check task fails
 * the build unless the tester wrote PASS. Run tasks are chained sequentially
 * by the caller — every server binds the same port.
 */
fun registerIntegrationServer(
    taskSuffix: String,
    version: String,
    runDirName: String,
    extraPluginJars: List<File>,
    flavour: String,
    jdk: Int,
    platform: String = "paper",
    suites: String = "full",
    serverFlags: List<String> = emptyList(),
): Pair<TaskProvider<RunServer>, TaskProvider<Task>> {
    val runDir = rootProject.layout.projectDirectory.dir("run/$runDirName").asFile
    val resultFile = runDir.resolve("plugins/MentalTester/test-results.txt")
    val failuresFile = runDir.resolve("plugins/MentalTester/test-failures.txt")
    val logFile = layout.buildDirectory.file("integration-test-logs/${runDirName.replace('/', '-')}.log")
    val label = version + flavour

    // A fresh freshness nonce per Gradle invocation, shared by this run+check
    // pair: the run task stamps it into the boot (-Dmental.tester.nonce), the
    // tester echoes it into the verdict line, and the check accepts ONLY this
    // nonce. A leftover test-results.txt from an earlier boot fails the check
    // structurally — it can never masquerade as this run's PASS.
    val nonce = UUID.randomUUID().toString()

    val runTask = tasks.register<RunServer>("runIntegrationTest$taskSuffix") {
        group = "mental integration"
        description = "Boots $platform $label with Mental + tester and runs the suite."
        dependsOn(tasks.shadowJar, testerShadowJar)
        runDirectory.set(runDir)
        // Folia downloads from its own project on the PaperMC API; Paper is the
        // RunServer default. Set the source before the version so the requested
        // build is fetched from the right project (Task 5.6).
        if (platform == "folia") {
            downloadsApiService.set(DownloadsAPIService.folia(project))
        }
        minecraftVersion(version)
        // disable.watchdog matters on slow CI runners: a >60s tick stall
        // trips the legacy watchdog, whose forced shutdown can deadlock old
        // servers into a hung process that never writes a test result.
        // serverFlags carries per-version boot flags from support-matrix.json (the
        // legacy Paper builds ≥1.13 need -DPaper.IgnoreJavaVersion=true to run on
        // Java 17); mental.tester.suites selects the tester's suite tier for this
        // entry (boot ⇒ classload/boot-safety suite only).
        jvmArgs("-Dcom.mojang.eula.agree=true", "-Ddisable.watchdog=true", "-Xmx2G",
                "-Dmental.tester.nonce=$nonce", "-Dmental.tester.suites=$suites")
        jvmArgs(serverFlags)
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(jdk))
        })
        pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
        pluginJars.from(testerShadowJar.flatMap { it.archiveFile })
        extraPluginJars.forEach { pluginJars.from(it) }

        doFirst {
            resultFile.delete()
            failuresFile.delete()
            // The whole config tree resets per run — split files, profiles,
            // migration artifacts — so every suite starts from the defaults.
            runDir.resolve("plugins/Mental").deleteRecursively()
            // Companion plugins must start pristine too — their defaults are
            // part of what the coexistence suite asserts against.
            runDir.resolve("plugins/OldCombatMechanics").deleteRecursively()
            // Belt-and-suspenders EULA acceptance: modern Paper honours the
            // -Dcom.mojang.eula.agree property, but the legacy builds are more
            // reliably satisfied by the eula.txt on disk. Idempotent everywhere.
            runDir.mkdirs()
            val eula = runDir.resolve("eula.txt")
            if (!eula.exists()) {
                eula.writeText("eula=true\n")
            }
            val properties = runDir.resolve("server.properties")
            if (!properties.exists()) {
                runDir.mkdirs()
                properties.writeText(
                    """
                    level-type=flat
                    online-mode=false
                    spawn-protection=0
                    view-distance=4
                    simulation-distance=4
                    motd=Mental integration test
                    """.trimIndent() + "\n"
                )
            }
            val log = logFile.get().asFile
            log.parentFile.mkdirs()
            val stream = log.outputStream()
            standardOutput = stream
            errorOutput = stream
        }
        doLast {
            (standardOutput as? java.io.Closeable)?.close()
        }
    }

    val checkTask = tasks.register("checkIntegrationTest$taskSuffix") {
        group = "mental integration"
        description = "Verifies the $label suite reported PASS with this run's nonce."
        dependsOn(runTask)
        doLast {
            val log = logFile.get().asFile
            if (!resultFile.exists()) {
                throw GradleException(
                    "No test result for $label — server crashed or hung. Log: ${log.absolutePath}")
            }
            if (failuresFile.exists()) {
                failuresFile.readLines().filter { it.isNotBlank() }.take(10).forEach {
                    logger.lifecycle("[$label] FAILURE: $it")
                }
            }
            val result = resultFile.readText().trim()
            val match = Regex("""^(PASS|FAIL) nonce=(.+)$""").matchEntire(result)
                ?: throw GradleException(
                    "Unrecognised test result '$result' for $label (expected 'PASS nonce=<n>'). " +
                            "Log: ${log.absolutePath}")
            val (verdict, gotNonce) = match.destructured
            if (gotNonce != nonce) {
                throw GradleException(
                    "Stale test result for $label — expected nonce=$nonce, got nonce=$gotNonce. " +
                            "The tester did not boot this run (a leftover result was left behind). " +
                            "Log: ${log.absolutePath}")
            }
            when (verdict) {
                "PASS" -> logger.lifecycle(
                    "[$label] integration tests passed (nonce=$nonce). Log: ${log.absolutePath}")
                "FAIL" -> throw GradleException(
                    "Integration tests failed for $label. Log: ${log.absolutePath}")
                else -> throw GradleException("Unknown verdict '$verdict' for $label.")
            }
        }
    }
    return runTask to checkTask
}

paperEntries.forEach { entry ->
    val suffix = "_" + entry.version.replace(".", "_")
    val (runTask, checkTask) =
        registerIntegrationServer(
            suffix, entry.version, entry.version, emptyList(), "", entry.jdk,
            suites = entry.suites, serverFlags = entry.serverFlags)
    previousCheck?.let { prior -> runTask.configure { mustRunAfter(prior) } }
    previousCheck = checkTask
    checkTasks.add(checkTask)
}

/* ────────────────────────────────────────────────────────────────────────
 *  Folia integration entries (Task 5.6).
 *
 *  A Folia entry (platform: "folia") boots a real Folia server — fetched from
 *  the Folia project on the PaperMC API by the RunServer's Folia downloads
 *  service — with Mental + the tester. The tester runtime-detects Folia and
 *  runs the boot suite + the Folia combat smoke (a same-region fake pair driven
 *  on their owning region threads, journal-asserted). Chained AFTER the paper
 *  entries so the sequential matrix never double-binds the port, and kept in a
 *  SEPARATE list so the floor+ceiling `integrationTest` smoke stays paper-only
 *  (its name filter would otherwise also match a Folia task on the shared
 *  ceiling version).
 * ──────────────────────────────────────────────────────────────────────── */
val foliaEntries: List<SupportEntry> = supportEntries.filter { it.platform == "folia" }
val foliaCheckTasks = mutableListOf<TaskProvider<Task>>()
foliaEntries.forEach { entry ->
    val suffix = "Folia_" + entry.version.replace(".", "_")
    val (runTask, checkTask) = registerIntegrationServer(
        suffix, entry.version, "folia/${entry.version}", emptyList(), " Folia", entry.jdk, "folia",
        suites = entry.suites, serverFlags = entry.serverFlags)
    previousCheck?.let { prior -> runTask.configure { mustRunAfter(prior) } }
    previousCheck = checkTask
    foliaCheckTasks.add(checkTask)
}

/* ────────────────────────────────────────────────────────────────────────
 *  OCM coexistence runs (Task 5.4: reproducible CI staging).
 *
 *  The OCM artifact is PINNED in support-matrix.json (an "ocm" object:
 *  version, url, sha256). stageOcmJar guarantees
 *  run/ocm-jar/OldCombatMechanics.jar exists before either OCM server boots:
 *    - a locally-present jar (a fork build: ./gradlew shadowJar in the
 *      BukkitOldCombatMechanics repo, copied there) is used AS-IS — the
 *      developer override, hash not enforced;
 *    - otherwise the pinned kernitus release is downloaded and its sha256
 *      verified, so CI is byte-reproducible.
 *  The OCM tasks are always registered but wired ONLY into integrationTestOcm
 *  (never into build / integrationTestMatrix), so the pinned download happens
 *  only when the coexistence gate is explicitly requested.
 * ──────────────────────────────────────────────────────────────────────── */
val ocmJarFile = rootProject.layout.projectDirectory.file("run/ocm-jar/OldCombatMechanics.jar").asFile

@Suppress("UNCHECKED_CAST")
val ocmPin: Map<String, Any> = supportMatrix["ocm"] as Map<String, Any>
val ocmVersion = ocmPin["version"] as String
val ocmUrl = ocmPin["url"] as String
val ocmSha256 = ocmPin["sha256"] as String

val stageOcmJar = tasks.register("stageOcmJar") {
    group = "mental integration"
    description = "Ensures run/ocm-jar/OldCombatMechanics.jar exists: a local build wins " +
            "(fork override); else downloads the pinned kernitus $ocmVersion release and " +
            "verifies its sha256."
    outputs.file(ocmJarFile)
    doLast {
        if (ocmJarFile.isFile) {
            logger.lifecycle(
                "[ocm] using existing ${ocmJarFile.absolutePath} — local override, hash not enforced.")
            return@doLast
        }
        ocmJarFile.parentFile.mkdirs()
        logger.lifecycle("[ocm] downloading pinned OldCombatMechanics $ocmVersion from $ocmUrl")
        URI(ocmUrl).toURL().openStream().use { input ->
            Files.copy(input, ocmJarFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(ocmJarFile.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        if (!actual.equals(ocmSha256, ignoreCase = true)) {
            ocmJarFile.delete()
            throw GradleException(
                "[ocm] sha256 mismatch for $ocmUrl — expected $ocmSha256, got $actual. Refusing to stage.")
        }
        logger.lifecycle("[ocm] verified sha256=$actual — staged to ${ocmJarFile.absolutePath}")
    }
}

val ocmCheckTasks = mutableListOf<TaskProvider<Task>>()
// The OCM coexistence gate runs on the FIXED 1.17.1 + 26.1.2 pair — its scope (the OCM ownership split)
// is unchanged by the legacy backport, so it is pinned by version rather than positionally (the legacy
// entries re-sorted 1.9.4 to entries[0], which would otherwise pull the floor of this pair down to a
// boot-tier legacy version OCM does not target).
val ocmVersions = listOf("1.17.1", "26.1.2")
paperEntries.filter { it.version in ocmVersions }.distinctBy { it.version }.forEach { entry ->
    val suffix = "Ocm_" + entry.version.replace(".", "_")
    val (runTask, checkTask) = registerIntegrationServer(
        suffix, entry.version, "ocm/${entry.version}", listOf(ocmJarFile), " +OCM", entry.jdk,
        suites = entry.suites, serverFlags = entry.serverFlags)
    runTask.configure { dependsOn(stageOcmJar) }
    previousCheck?.let { prior -> runTask.configure { mustRunAfter(prior) } }
    previousCheck = checkTask
    ocmCheckTasks.add(checkTask)
}

tasks.register("integrationTest") {
    group = "mental integration"
    description = "Runs the suite on the modern floor and newest supported versions (PR smoke)."
    // The floor+ceiling PR smoke stays on the FULL-tier range: the modern floor (first non-boot entry,
    // 1.17.1) + the ceiling (last paper entry). The re-sort put a boot-tier legacy version at
    // paperEntries.first(), which this smoke deliberately does not select — the legacy boot tier is
    // covered by integrationTestMatrix, and whether a legacy floor joins the PR lane is a Phase 6 decision.
    val modernFloor = paperEntries.first { it.suites != "boot" }.version
    val floorAndCeiling = setOf(modernFloor, paperEntries.last().version)
    dependsOn(checkTasks.filter { provider ->
        floorAndCeiling.any { provider.name.endsWith("_" + it.replace(".", "_")) }
    })
}

tasks.register("integrationTestMatrix") {
    group = "mental integration"
    description = "Runs the suite on every paper AND folia entry in support-matrix.json."
    dependsOn(checkTasks + foliaCheckTasks)
}

tasks.register("integrationTestFolia") {
    group = "mental integration"
    description = "Runs the suite on every folia entry in support-matrix.json."
    dependsOn(foliaCheckTasks)
}

tasks.register("integrationTestOcm") {
    group = "mental integration"
    description = "Runs the OldCombatMechanics coexistence suite on floor and ceiling. " +
            "Stages the pinned OCM release (a local run/ocm-jar override wins) then boots both."
    dependsOn(ocmCheckTasks)
}
