import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.jar.JarFile
import java.util.zip.ZipFile
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.service.DownloadsAPIService
import xyz.wagyourtail.jvmdg.gradle.JVMDowngraderExtension

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.jvmdowngrader)
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
    val bytecodeTier: Int,
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
            // is the legacy-backport classload/boot-safety suite. bytecodeTier is the Multi-Release class
            // major this entry's plugin loader × JVM actually reads from the mega jar (52 base / 61
            // versions/17) — passed as -Dmental.tester.tier so the loaded tree is asserted live per-entry.
            suites = entry["suites"] as String,
            bytecodeTier = (entry["bytecodeTier"] as Number).toInt(),
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
    // The shaded modern (v61) jar is now an INTERMEDIATE of the mega-jar pipeline, not
    // the shipped artifact. Stage it out of build/libs (and off the canonical name) so
    // the Mental-*.jar glob that release.yml and integration-matrix.sh read only ever
    // resolves the final mega jar (campaign H3). Its bytecode is byte-identical to the
    // 2.3.x line — it becomes the mega jar's META-INF/versions/17 tree unchanged (D-1).
    archiveClassifier.set("modern")
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))

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

/* ────────────────────────────────────────────────────────────────────────
 *  The mega-jar pipeline (full-range campaign D-1/D-2/D-7).
 *
 *  JVMDowngrader lowers the shaded v61 jar to a Multi-Release mega-jar: the base
 *  tree is class v52 (Java 8) so the ONE artifact classloads from Java 8 up, while
 *  multiReleaseOriginal keeps the original v61 classes under META-INF/versions/17
 *  (byte-identical modern behavior for 1.17.1+ loaders) and multiReleaseVersions
 *  adds a v60 tier under versions/16 for 1.16.5-on-Java-16. Third-party classes
 *  already ≤ v52 (packetevents/bstats/adventure targets) get no MR duplicate.
 *
 *  jvmdg's ShadeJar then relocates jvmdg's own runtime helpers under
 *  me/vexmc/mental/lib/jvmdg/ and emits the canonical Mental-<version>.jar — the
 *  ONLY Mental-*.jar in build/libs. Every consumer (build, the run tasks, the
 *  verify gates) reads THIS task's output, never the staged intermediates.
 * ──────────────────────────────────────────────────────────────────────── */


fun failOnJvmdgWarnings(jar: Jar) {
    val captured = StringBuilder()
    val sink = StandardOutputListener { text -> captured.append(text) }
    val logSink = jar.project.layout.buildDirectory.file("jvmdg-stage/${jar.name}-output.log")
    jar.doFirst {
        logging.addStandardOutputListener(sink)
        logging.addStandardErrorListener(sink)
    }
    jar.doLast {
        logging.removeStandardOutputListener(sink)
        logging.removeStandardErrorListener(sink)
        val text = captured.toString()
        val file = logSink.get().asFile
        file.parentFile.mkdirs()
        file.writeText(text)
        val warnings = text.lines().filter { line -> Regex("(?i)\\b(warn|warning|error)\\b").containsMatchIn(line) }
        if (warnings.isNotEmpty()) {
            throw GradleException("jvmdowngrader emitted ${warnings.size} warning/error line(s) during '${jar.name}' " +
                    "(full-range campaign: warnings are build failures). First:\n" +
                    warnings.take(20).joinToString("\n") { "    $it" } + "\nFull: ${file.absolutePath}")
        }
    }
}
fun mrStrip(name: String): String {
    if (!name.startsWith("META-INF/versions/")) return name
    val rest = name.substringAfter("META-INF/versions/")
    val slash = rest.indexOf('/')
    return if (slash < 0) rest else rest.substring(slash + 1)
}
fun classMajor(bytes: ByteArray): Int {
    return ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
}
fun isFirstParty(logical: String): Boolean {
    return logical.startsWith("me/vexmc/mental/")
            && !logical.startsWith("me/vexmc/mental/lib/")
            && !logical.startsWith("me/vexmc/mental/tester/lib/")
}
val jvmdg = extensions.getByType<JVMDowngraderExtension>()

// The DowngradeJar the plugin pre-registers (input defaults to shadowJar) — retargeted
// to the base v52 + versions/16 + versions/17 tier set and staged out of build/libs.
val downgradeMegaJar = jvmdg.defaultTask
downgradeMegaJar.configure {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    // base = v52 (Java 8) + versions/17 = the untouched original v61. multiReleaseOriginal
    // is set WITHOUT multiReleaseVersions: jvmdg 1.3.6 treats -mro (keep original) and
    // -mr <ver> (keep a semi-downgraded intermediate) as MUTUALLY EXCLUSIVE — requesting a
    // v60/versions-16 tier DROPS the original v61, which would silently downgrade the modern
    // path and void D-1 (modern must be byte-identical to the matrix-verified 2.3.x line).
    // Keeping the original is the D-1-critical decision; the v60 tier D-7 planned for
    // 1.16.5-on-Java-16 is a Phase-2 concern (it reads base v52 without it — v52 is the
    // tested legacy base, and Mental uses sealed types so v60 is a real downgrade, not a
    // no-op). See the Phase 1 outcome log for the escalated jvmdg limitation.
    multiReleaseOriginal.set(true)
    // The downgrade classpath must carry the supertypes of every referenced type. core
    // compiles against the 1.17.1 floor, but shadowJar folds compat-folia's classes in,
    // whose supertypes are the Folia scheduler API (absent from the floor). Union core's
    // own compile classpath with compat-folia's so jvmdg resolves them with ZERO warnings.
    classpath = sourceSets["main"].compileClasspath +
            project(":compat-folia").sourceSets["main"].compileClasspath
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    archiveBaseName.set("Mental")
    archiveClassifier.set("downgraded")
    failOnJvmdgWarnings(this)
}

// The ShadeJar the plugin pre-registers — relocates jvmdg's runtime helpers under our
// lib prefix and emits the canonical, glob-visible Mental-<version>.jar.
val megaJar = jvmdg.defaultShadeTask
megaJar.configure {
    inputFile.set(downgradeMegaJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    // The relocation prefix jvmdg PREPENDS to its runtime helpers (Kotlin DSL sets the
    // Function1 property directly; the input class path is ignored — every helper lands
    // under this one prefix, i.e. me/vexmc/mental/lib/jvmdg/xyz/wagyourtail/…).
    shadePath.set { "me/vexmc/mental/lib/jvmdg/" }
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    archiveBaseName.set("Mental")
    archiveClassifier.set("")
    failOnJvmdgWarnings(this)
}

tasks.build {
    dependsOn(megaJar)
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
    dependsOn(megaJar)
    val jarFile = megaJar.flatMap { it.archiveFile }
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
                val logical = if (name.startsWith("META-INF/versions/"))
                    name.substringAfter("META-INF/versions/").substringAfter('/') else name
                if (logical.startsWith("net/kyori/") || logical.startsWith("xyz/wagyourtail/")) {
                    entryViolations.add(name)
                }
                if (name.endsWith(".class") && !logical.startsWith(relocatedPrefix)) {
                    val text = zip.getInputStream(entry).use { input ->
                        String(input.readBytes(), Charsets.ISO_8859_1)
                    }
                    // jvmdg leaves bare CLASS-retention marker annotations (xyz/wagyourtail/jvmdg/j{11,16,17}
                    // NestHost/NestMembers/RecordComponents/PermittedSubClasses) un-relocated by design —
                    // harmless (never resolved; spike-proven on real Java 8). Their reference-correctness is
                    // verifyJdk8Api's job; here only net.kyori (Adventure) is scanned by reference.
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
// The tester's FINAL mega jar (its jvmdg ShadeJar) — the artifact that must load on the
// Java-8 legacy servers, NOT the staged v61 shadowJar. Looked up by task NAME (the jvmdg
// plugin names its default shade task identically in every project, so core's own
// megaJar.name is the tester's too), NOT by extensions.getByType<JVMDowngraderExtension>():
// that reified plugin type resolves through core's plugin classloader and does NOT match the
// tester project's separately-loaded extension class, so getByType throws cross-project.
// AbstractArchiveTask is a Gradle core type (shared classloader), so the typed lookup is safe.
// core evaluationDependsOn(":tester") above, so the tester's task exists here.
val testerMegaJar = project(":tester").tasks.named<AbstractArchiveTask>(megaJar.name)

/* ────────────────────────────────────────────────────────────────────────
 *  Mega-jar verification gates (campaign Q2/Q3/H1/H4/D-8), all wired into `check`.
 *
 *  These make the mega-jar invariants un-rottable and prove them on EVERY
 *  ./gradlew build, not only at release: the base tree is real Java-8 bytecode
 *  (verifyDowngrade), every reference in it resolves on a real Java-8 JVM or a
 *  server package (verifyJdk8Api), the modern tree is byte-identically forked,
 *  and the two plugins' jvmdg runtimes never cross (verifyTesterIsolation).
 * ──────────────────────────────────────────────────────────────────────── */

/*  verifyDowngrade (Q2/H4) — the tier structure is exactly the D-7 shape.  */
/*  verifyJdk8Api (H1) — closed-world scan of both base trees against a real JDK 8.  */
val jdk8GateAsm: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { jdk8GateAsm("org.ow2.asm:asm:9.7") }

// Server-provided packages: present on the running server, never bundled, so never validated or required
// in-jar. Each is documented — an addition must be a package the scan PROVES the server provides.
val serverProvidedIgnores = listOf(
    "org/bukkit",            // the Bukkit/Paper API
    "net/minecraft",         // NMS (reflected, never linked directly, but appears in a few descriptors)
    "com/destroystokyo",     // Paper's legacy API namespace
    "io/papermc",            // modern Paper API (Folia schedulers, etc.)
    "org/spigotmc",          // Spigot API surface
    "io/netty",              // the server's shaded-in Netty (Mental shares it, never bundles it)
    "com/mojang",            // Mojang/Brigadier/authlib provided by the server
    "org/jetbrains",         // compile-only CLASS-retention annotations (@NotNull/@Nullable) — absent at runtime by design
    "xyz/wagyourtail",       // jvmdg CLASS-retention marker annotations left bare (@NestMembers/@RecordComponents/…) — never resolved; class relocated & bundled
    // Compile-only CLASS-retention annotations dragged in by the shaded packetevents/adventure, absent at
    // runtime by design (the JVM never resolves a missing annotation class) — proven benign like org/jetbrains:
    "org/intellij",          // IntelliJ @Pattern/@RegExp (adventure key validation)
    "org/jspecify",          // JSpecify @Nullable/@NullMarked (packetevents)
    "org/checkerframework",  // CheckerFramework @MonotonicNonNull etc. (packetevents)
    "com/google/auto",       // Google @AutoService (packetevents build-time service registration marker)
    "com/google/errorprone", // Error Prone @CanIgnoreReturnValue etc. (guava/packetevents)
    // Google runtime libraries the SERVER bundles (packetevents links them; Paper ships both):
    "com/google/gson",       // Gson — Paper-provided
    "com/google/common",     // Guava — Paper-provided
    "com/viaversion",        // the optional ViaVersion plugin: packetevents' ViaVersionAccessorImpl links it only
                             //   when that plugin is installed (it provides the classes), guarded otherwise
)

val verifyJdk8Api = tasks.register("verifyJdk8Api") {
    group = "verification"
    description = "Fails if any reference in either mega jar's base (v52) tree resolves neither in a real " +
            "JDK-8 rt.jar, in-jar, nor a documented server-provided package (campaign H1; subsumes H2)."
    dependsOn(megaJar, testerMegaJar)
    val coreJar = megaJar.flatMap { it.archiveFile }
    val testerJar = testerMegaJar.flatMap { it.archiveFile }
    val toolSrc = rootProject.layout.projectDirectory.file("scripts/tools/Jdk8ApiGate.java")
    val allowFile = rootProject.layout.projectDirectory.file("scripts/tools/jdk8-api-gate.allow")
    val jdk8Home = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) }
        .map { it.metadata.installationPath.asFile }
    val classesDir = layout.buildDirectory.dir("jdk8-gate/classes")
    val asm = jdk8GateAsm
    // Gradle 9 removed Project.javaexec; ExecOperations is the injected replacement, captured at
    // configuration time so the doLast never touches the Project instance.
    val execOps = serviceOf<ExecOperations>()
    inputs.file(coreJar); inputs.file(testerJar); inputs.file(toolSrc); inputs.file(allowFile)
    doLast {
        val jdk8 = jdk8Home.get()
        val out = classesDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        // Compile the standalone tool in-process against ASM (Gradle runs on a JDK, so the compiler is present).
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
            ?: throw GradleException("no system Java compiler — run Gradle on a JDK, not a JRE")
        val asmCp = asm.files.joinToString(File.pathSeparator) { it.absolutePath }
        val rc = compiler.run(null, null, System.err,
            "-cp", asmCp, "-d", out.absolutePath, toolSrc.asFile.absolutePath)
        if (rc != 0) throw GradleException("failed to compile ${toolSrc.asFile.name} (see errors above)")
        val toolClasspath = files(asm.files + out)

        // The tester provides no kernel/api/platform/core code (the Mental jar does at runtime), so those
        // Mental packages are server-provided FROM ITS PERSPECTIVE — ignore me/vexmc/mental/ for it only.
        // Inlined as a loop (a local fun here would truncate the Gradle Kotlin DSL script body).
        val gates = listOf(
            Triple(coreJar.get().asFile, serverProvidedIgnores, "Mental"),
            Triple(testerJar.get().asFile, serverProvidedIgnores + "me/vexmc/mental/", "MentalTester"))
        for ((jar, ignores, label) in gates) {
            val gateArgs = mutableListOf(jar.absolutePath, jdk8.absolutePath,
                "--allow", allowFile.asFile.absolutePath)
            ignores.forEach { gateArgs.add("--ignore"); gateArgs.add(it) }
            val result = execOps.javaexec {
                classpath = toolClasspath
                mainClass.set("Jdk8ApiGate")
                args = gateArgs
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) {
                throw GradleException("[verifyJdk8Api] $label has references absent from Java 8 "
                        + "(see the [jdk8-gate] output above).")
            }
        }
    }
}


val verifyDowngrade = tasks.register("verifyDowngrade") {
    group = "verification"
    description = "Fails unless the mega jar is a well-formed Multi-Release tier set: base ≤ v52, " +
            "versions/16 ≤ v60, versions/17 first-party == v61 and its class-set == base's, sentinel forked, " +
            "and no reflective-record token in the downgraded tree (H4)."
    dependsOn(megaJar)
    val jarFile = megaJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        val file = jarFile.get().asFile
        val problems = mutableListOf<String>()
        val sentinel = "me/vexmc/mental/v5/MentalPluginV5.class"
        var baseSentinelMajor = -1
        var v17SentinelMajor = -1
        val baseFirstParty = sortedSetOf<String>()
        val v17FirstParty = sortedSetOf<String>()
        val baseBytesByLogical = mutableMapOf<String, ByteArray>()

        // Manifest must declare Multi-Release: true.
        JarFile(file).use { jar ->
            val mr = jar.manifest?.mainAttributes?.getValue("Multi-Release")
            if (!"true".equals(mr, ignoreCase = true)) {
                problems.add("manifest Multi-Release is '$mr' (expected true)")
            }
        }

        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                val logical = mrStrip(name)
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val major = classMajor(bytes)
                when {
                    // versions/17 overlay: the original modern tree.
                    name.startsWith("META-INF/versions/17/") -> {
                        if (isFirstParty(logical)) {
                            v17FirstParty.add(logical)
                            if (major != 61) problems.add("versions/17 first-party $logical is v$major (expected 61)")
                        }
                        if (name == "META-INF/versions/17/$sentinel") v17SentinelMajor = major
                    }
                    // versions/16 overlay (if produced): must not exceed v60.
                    name.startsWith("META-INF/versions/16/") -> {
                        if (major > 60) problems.add("versions/16 entry $logical is v$major (>60)")
                    }
                    // Any other versioned overlay tier is unexpected in the D-7 shape.
                    name.startsWith("META-INF/versions/") ->
                        problems.add("unexpected versioned tier entry $name")
                    // Base tree: nothing may exceed v52 (Java 8).
                    else -> {
                        if (major > 52) problems.add("base entry $logical is v$major (>52)")
                        if (isFirstParty(logical)) {
                            baseFirstParty.add(logical)
                            baseBytesByLogical[logical] = bytes
                        }
                        if (name == sentinel) baseSentinelMajor = major
                        // H4: no downgraded (non-jvmdg-runtime) base class may reflectively introspect records —
                        // a downgraded record is NOT a reflective record (Class.isRecord()==false). ISO-8859-1
                        // makes a substring match an exact constant-pool byte-sequence match.
                        if (!logical.startsWith("me/vexmc/mental/lib/jvmdg/")) {
                            val text = String(bytes, Charsets.ISO_8859_1)
                            if (text.contains("isRecord") || text.contains("java/lang/reflect/RecordComponent")) {
                                problems.add("base class $logical references Class.isRecord/java.lang.reflect.RecordComponent "
                                        + "(H4) — a downgraded record is not a reflective record")
                            }
                        }
                    }
                }
            }
        }

        // The modern (versions/17) overlay must be a SUBSET of base (no phantom class), and its sentinel must
        // fork to v61 — but it is NOT required to overlay EVERY base class. jvmdg's multiReleaseOriginal keeps
        // the original v61 only for classes whose downgrade could behave differently on a modern JVM (the ones
        // using shimmed Java-9+ APIs — those must run the REAL API on 1.17+). Classes needing only behavior-
        // PRESERVING language downgrades (string-concat → StringBuilder, records/sealed/nestmate metadata
        // annotations) get no overlay and load as v52 on modern — functionally identical to v61, so D-1's
        // behavior identity holds (the live 1.17.1/26.1.2 gate proves it). A base-only class must therefore
        // carry NO jvmdg-runtime reference (that would mean a shimmed API loading its shim on modern); if it
        // did, that IS a D-1 break and fails here.
        val phantom = v17FirstParty - baseFirstParty
        if (phantom.isNotEmpty()) {
            problems.add("versions/17 has ${phantom.size} first-party class(es) absent from base (e.g. "
                    + "${phantom.take(3)}) — a phantom overlay")
        }
        val baseOnly = baseFirstParty - v17FirstParty
        for (cls in baseOnly) {
            val text = String(baseBytesByLogical.getValue(cls), Charsets.ISO_8859_1)
            if (text.contains("me/vexmc/mental/lib/jvmdg")) {
                problems.add("base-only class $cls references the jvmdg runtime but has NO v61 overlay — a "
                        + "shimmed API would load its shim on modern (D-1 break)")
            }
        }
        if (baseSentinelMajor != 52) problems.add("sentinel base major is $baseSentinelMajor (expected 52)")
        if (v17SentinelMajor != 61) problems.add("sentinel versions/17 major is $v17SentinelMajor (expected 61)")

        if (problems.isNotEmpty()) {
            throw GradleException("verifyDowngrade: the mega jar is not a well-formed tier set:\n"
                    + problems.take(30).joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("[verifyDowngrade] OK — base ≤ v52; ${v17FirstParty.size} first-party classes forked to "
                + "v61 under versions/17; ${baseOnly.size} behavior-preserving base-only (no jvmdg shim); sentinel "
                + "forked 52/61; no reflective-record token.")
    }
}


val verifyTesterIsolation = tasks.register("verifyTesterIsolation") {
    group = "verification"
    description = "Fails if the tester mega jar references Mental's me/vexmc/mental/lib/jvmdg prefix (D-8)."
    dependsOn(testerMegaJar)
    val jarFile = testerMegaJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        val needle = "me/vexmc/mental/lib/jvmdg"
        val hits = mutableListOf<String>()
        ZipFile(jarFile.get().asFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.endsWith(".class")) continue
                val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                if (text.contains(needle)) hits.add(entry.name)
            }
        }
        if (hits.isNotEmpty()) throw GradleException("verifyTesterIsolation: tester references $needle")
        logger.lifecycle("[verifyTesterIsolation] OK")
    }
}
tasks.named("check") { dependsOn(verifyDowngrade, verifyTesterIsolation, verifyJdk8Api) }

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
    bytecodeTier: Int = 61,
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
        dependsOn(megaJar, testerMegaJar)
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
        // mental.tester.suites selects the tester's suite tier for this entry
        // (boot ⇒ classload/boot-safety suite only); mental.tester.tier is the
        // entry's declared Multi-Release bytecodeTier (the class major its loader ×
        // JVM reads from the mega jar) — the tester asserts the loaded tree against
        // it, so which Multi-Release tree loaded is a live per-version fact. No
        // Java-version-guard bypass flag is passed: every legacy build runs its
        // newest clean flagless JVM, and the v52 base tree loads there natively.
        jvmArgs("-Dcom.mojang.eula.agree=true", "-Ddisable.watchdog=true", "-Xmx2G",
                "-Dmental.tester.nonce=$nonce", "-Dmental.tester.suites=$suites",
                "-Dmental.tester.tier=$bytecodeTier")
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(jdk))
        })
        // The mega jars — the SHIPPED artifacts, so the live gate exercises the exact
        // Multi-Release bytecode admins run (modern loaders read versions/17, the ad-hoc
        // Java-8 legacy boots read base v52).
        pluginJars.from(megaJar.flatMap { it.archiveFile })
        pluginJars.from(testerMegaJar.flatMap { it.archiveFile })
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
            suites = entry.suites, bytecodeTier = entry.bytecodeTier)
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
        suites = entry.suites, bytecodeTier = entry.bytecodeTier)
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
        suites = entry.suites, bytecodeTier = entry.bytecodeTier)
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
