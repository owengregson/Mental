import xyz.jpenilla.runpaper.task.RunServer

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

evaluationDependsOn(":tester")

dependencies {
    api(project(":api"))
    api(project(":common"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    implementation(libs.packetevents.spigot)
    implementation(libs.bstats.bukkit)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.paper.api.floor)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    dependsOn(":compat-folia:classes", ":compat-brigadier:classes")
    archiveBaseName.set("Mental")
    archiveClassifier.set("")

    from(project(":compat-folia").sourceSets.main.get().output)
    from(project(":compat-brigadier").sourceSets.main.get().output)

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
 *  For every version in gradle.properties' integrationTestVersions, a real
 *  Paper server boots with the Mental and MentalTester jars installed; the
 *  tester runs its suite in-process, writes PASS/FAIL, and shuts the server
 *  down; the paired check task fails the build on anything but PASS.
 * ──────────────────────────────────────────────────────────────────────── */

val integrationTestVersions: List<String> =
    (findProperty("integrationTestVersions") as String?)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: listOf("1.17.1", "26.1.2")

fun parseMinecraftVersion(version: String): Triple<Int, Int, Int> {
    val parts = version.split(".")
    return Triple(
        parts.getOrNull(0)?.toIntOrNull() ?: 0,
        parts.getOrNull(1)?.toIntOrNull() ?: 0,
        parts.getOrNull(2)?.toIntOrNull() ?: 0,
    )
}

// 1.17–1.20.4 class files target Java 17; 1.20.5+ requires 21 and runs
// happily on 25 — two toolchains cover the whole matrix.
fun requiredJavaVersion(version: String): Int {
    val (major, minor, patch) = parseMinecraftVersion(version)
    if (major > 1 || minor > 20 || (minor == 20 && patch >= 5)) return 25
    return 17
}

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
): Pair<TaskProvider<RunServer>, TaskProvider<Task>> {
    val runDir = rootProject.layout.projectDirectory.dir("run/$runDirName").asFile
    val resultFile = runDir.resolve("plugins/MentalTester/test-results.txt")
    val failuresFile = runDir.resolve("plugins/MentalTester/test-failures.txt")
    val logFile = layout.buildDirectory.file("integration-test-logs/${runDirName.replace('/', '-')}.log")
    val label = version + flavour

    val runTask = tasks.register<RunServer>("runIntegrationTest$taskSuffix") {
        group = "mental integration"
        description = "Boots Paper $label with Mental + tester and runs the suite."
        dependsOn(tasks.shadowJar, testerShadowJar)
        runDirectory.set(runDir)
        minecraftVersion(version)
        // disable.watchdog matters on slow CI runners: a >60s tick stall
        // trips the legacy watchdog, whose forced shutdown can deadlock old
        // servers into a hung process that never writes a test result.
        jvmArgs("-Dcom.mojang.eula.agree=true", "-Ddisable.watchdog=true", "-Xmx2G")
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(requiredJavaVersion(version)))
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
        description = "Verifies the $label suite reported PASS."
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
            when (val result = resultFile.readText().trim()) {
                "PASS" -> logger.lifecycle("[$label] integration tests passed. Log: ${log.absolutePath}")
                "FAIL" -> throw GradleException("Integration tests failed for $label. Log: ${log.absolutePath}")
                else -> throw GradleException("Unknown test result '$result' for $label.")
            }
        }
    }
    return runTask to checkTask
}

integrationTestVersions.forEach { version ->
    val suffix = "_" + version.replace(".", "_")
    val (runTask, checkTask) = registerIntegrationServer(suffix, version, version, emptyList(), "")
    previousCheck?.let { prior -> runTask.configure { mustRunAfter(prior) } }
    previousCheck = checkTask
    checkTasks.add(checkTask)
}

/* ────────────────────────────────────────────────────────────────────────
 *  OCM coexistence runs. When run/ocm-jar/OldCombatMechanics.jar exists
 *  (build it in the BukkitOldCombatMechanics repo: ./gradlew shadowJar,
 *  then copy build/libs/OldCombatMechanics.jar there), the floor and
 *  ceiling versions also boot with OCM installed; the tester detects it
 *  and runs the coexistence suite instead of the era suites.
 * ──────────────────────────────────────────────────────────────────────── */
val ocmJarFile = rootProject.layout.projectDirectory.file("run/ocm-jar/OldCombatMechanics.jar").asFile
val ocmCheckTasks = mutableListOf<TaskProvider<Task>>()

if (ocmJarFile.isFile) {
    setOf(integrationTestVersions.first(), integrationTestVersions.last()).forEach { version ->
        val suffix = "Ocm_" + version.replace(".", "_")
        val (runTask, checkTask) = registerIntegrationServer(
            suffix, version, "ocm/$version", listOf(ocmJarFile), " +OCM")
        previousCheck?.let { prior -> runTask.configure { mustRunAfter(prior) } }
        previousCheck = checkTask
        ocmCheckTasks.add(checkTask)
    }
}

tasks.register("integrationTest") {
    group = "mental integration"
    description = "Runs the suite on the floor and newest supported versions."
    val floorAndCeiling = setOf(integrationTestVersions.first(), integrationTestVersions.last())
    dependsOn(checkTasks.filter { provider ->
        floorAndCeiling.any { provider.name.endsWith("_" + it.replace(".", "_")) }
    })
}

tasks.register("integrationTestMatrix") {
    group = "mental integration"
    description = "Runs the suite on every version in integrationTestVersions."
    dependsOn(checkTasks)
}

tasks.register("integrationTestOcm") {
    group = "mental integration"
    description = "Runs the OldCombatMechanics coexistence suite on floor and ceiling " +
            "(requires run/ocm-jar/OldCombatMechanics.jar)."
    if (ocmJarFile.isFile) {
        dependsOn(ocmCheckTasks)
    } else {
        doFirst {
            throw GradleException(
                "run/ocm-jar/OldCombatMechanics.jar is missing. Build it in the " +
                        "BukkitOldCombatMechanics repo (./gradlew shadowJar) and copy " +
                        "build/libs/OldCombatMechanics.jar there.")
        }
    }
}
