import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
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
}

tasks.build {
    dependsOn(tasks.shadowJar)
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
        jvmArgs("-Dcom.mojang.eula.agree=true", "-Ddisable.watchdog=true", "-Xmx2G",
                "-Dmental.tester.nonce=$nonce")
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
        registerIntegrationServer(suffix, entry.version, entry.version, emptyList(), "", entry.jdk)
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
        suffix, entry.version, "folia/${entry.version}", emptyList(), " Folia", entry.jdk, "folia")
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
listOf(paperEntries.first(), paperEntries.last()).distinctBy { it.version }.forEach { entry ->
    val suffix = "Ocm_" + entry.version.replace(".", "_")
    val (runTask, checkTask) = registerIntegrationServer(
        suffix, entry.version, "ocm/${entry.version}", listOf(ocmJarFile), " +OCM", entry.jdk)
    runTask.configure { dependsOn(stageOcmJar) }
    previousCheck?.let { prior -> runTask.configure { mustRunAfter(prior) } }
    previousCheck = checkTask
    ocmCheckTasks.add(checkTask)
}

tasks.register("integrationTest") {
    group = "mental integration"
    description = "Runs the suite on the floor and newest supported versions."
    val floorAndCeiling = setOf(paperEntries.first().version, paperEntries.last().version)
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
