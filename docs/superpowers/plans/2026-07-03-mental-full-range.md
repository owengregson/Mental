# Mental full-range campaign — 1.9.4 → 26.1.2, one jar, no flags

> **For agentic workers:** this is a campaign master plan in the house style of
> `2026-07-02-mental-legacy-backport.md`: Fable writes design-complete phase
> briefs and line-reviews every phase's diff; Opus executes. Phases are
> sequential; each gates on fresh-nonce live-server evidence before the next
> dispatches.

**Goal:** close the two remaining holes in Mental's support story — Paper
1.14.4 (currently a documented impossibility) and the
`-DPaper.IgnoreJavaVersion=true` server flag required on 1.13.2/1.15.2/1.16.5 —
and ship the ENTIRE range Paper 1.9.4 → 26.1.2 as **one jar**, released as
**2.3.2** (a full release, not a beta: the hyphenless version makes the
auto-releaser take the "Latest" marker).

**The unifying insight:** both holes have one root cause. Mental ships Java-17
classfiles, which forces legacy servers onto a Java-17 JVM — a JVM that
1.13–1.16.5 tolerate only behind `IgnoreJavaVersion` and that 1.14.4's terminal
Paper build refuses outright (hard Java-13 cap, no bypass — proven in
`docs/superpowers/research/2026-07-02-legacy-boot-viability.md`). Run each
legacy server on its **native-era Java — the newest its build boots flagless**
(owner-directed: no outdated-Java advisories, maximal JVM performance) and
both problems dissolve — provided the plugin jar can classload from Java 8
up. JVMDowngrader (JDG) makes it load there: the shipped jar becomes a
**Multi-Release mega-jar** whose base tree is the same compilation downgraded
to class v52 (Java 8), with intermediate tiers where a loader would read them
(D-7), and whose `META-INF/versions/17` tree is the original v61 classes, so:

- a legacy server on its native-era JVM reads the richest tree its plugin
  loader supports (base v52 below the MR-aware line; see D-7),
- a 1.17.1+ server (Java 16+ mandatory) reads the original v61 tree via the
  runtime-versioned `JarFile` — **byte-identical modern behavior** to what has
  shipped and been matrix-verified since 2.3.0-beta,
- a legacy server run on ANY Java from 8 up (including the 2.3.1-beta
  Java-17 install style) keeps working — whichever tree its loader picks is
  the same code.

The kernel, the platform seam, and every product source file are untouched:
the fork happens at the **artifact** level. All the boot-time probing built in
the 2026-07-02 backport (resolvers select by API presence, never by version
string) already makes 1.14.4 a probe target like any other — classloading was
the only blocker, plus a tester `FakePlayer` v1_14_R1 branch.

## In-house prior art (owner-directed): StarEnchants

`~/Documents/StarEnchants` ships a JDG mega-jar supporting 1.8 through modern.
Its **architecture does not transfer** (it dual-compiles two era source trees
against different APIs; Mental compiles once against the 1.17.1 floor with
runtime probes — so StarEnchants' era-exclusivity soundness gate `MegaJarGate`
P1–P6 has no Mental analogue and must NOT be ported). What transfers is the
JDG mechanics, each of which cost them a debugging round:

- **JDG 1.3.6 CLI**, fetched from
  `https://repo1.maven.org/maven2/xyz/wagyourtail/jvmdowngrader/jvmdowngrader/1.3.6/jvmdowngrader-1.3.6-all.jar`;
  target flag `-c 52`; commands chain via `-` (stdout/stdin):
  `downgrade --target IN -` piped into `shade --prefix P --target - OUT`.
- **The util-shading trap (H2):** JDG's `shade` bundles the `--api` stub
  classes but NOT its own `xyz/wagyourtail/jvmdg/util/*` runtime helpers,
  which downgraded code calls. The fix (StarEnchants
  `scripts/build-legacy-jar.sh` lines 69–88): build a self-contained api jar =
  `debug downgradeApi` output + JDG's `util/*` classes, themselves downgraded,
  appended — then `--api` that jar during downgrade+shade.
- **`-i` ignore flags** for server-provided packages JDG must not try to
  resolve: for Mental that is `org.bukkit net.minecraft com.destroystokyo
  io.papermc io.netty com.mojang org.spigotmc` (we shade packetevents /
  adventure / bstats, so those downgrade WITH us and are not ignored).
- **MRJAR assembly** (their `build-mega-jar.sh`): base = the ENTIRE downgraded
  jar (resources live once, in base); overlay = ONLY `.class` files under
  `META-INF/versions/17`; `Multi-Release: true` added to the manifest
  CRLF-correctly; deterministic repack (sorted entries, `zip -X`). **Proven
  live on both eras** — modern Paper's plugin classloader honors
  runtime-versioned jars (their mega-smoke boots 1.8-on-JDK8 AND modern).
- **The closed-world JDK-8 API gate (H1):** JDG silently passes through
  JDK-9+ `java.*` APIs it has no stub for — they downgrade green and
  `NoSuchMethodError` on a real Java 8 JVM (their real-world catch:
  `ThreadLocalRandom.nextDouble(double)`). Their `scripts/tools/Jdk8ApiGate.java`
  (459-line standalone ASM tool) scans every member reference in the
  downgraded tree against a REAL JDK-8 `rt.jar` baseline, with an
  anchored-prefix allowlist (theirs is empty — genuine misses get fixed, not
  allowlisted). Ported here as a Gradle verification task; the baseline
  `rt.jar` comes from the same foojay-provisioned JDK 8 the servers use.
- **Detail traps:** `comm … | head` under `set -o pipefail` is SIGPIPE-flaky
  (take the first line via parameter expansion); manifest edits must keep CRLF.

**Mental's structural advantage:** both mega-jar trees are the SAME
compilation (one downgraded), so the class SETS are identical and there is no
era-exclusive class for a wrong-tree fallback to break. A non-MR-aware loader
on a modern JVM merely loads downgraded bytecode — functionally identical,
observably reported (Q1). StarEnchants needed a 6-part soundness gate here; we
need a sentinel self-check and a live tier assertion.

## Hazard register

| # | Hazard | Containment |
|---|---|---|
| H1 | JDG silently passes through un-shimmable JDK-9+ `java.*` APIs → runtime `NoSuchMethodError` on Java 8, potentially on paths the clientless suite never drives (the fast-path blind spot) | `verifyJdk8Api` Gradle task: ASM member-level scan of the mega-jar base tree against the toolchain JDK-8 `rt.jar` (+ `jre/lib/*.jar`); wired into `check`; anchored-prefix allowlist file starts EMPTY |
| H2 | JDG `shade` omits its `util/*` runtime helpers → `NoClassDefFoundError: xyz/wagyourtail/jvmdg/util/…` on Java 8 | self-contained api jar per the StarEnchants recipe; `verifyRelocation` extended: zero `xyz/wagyourtail` entries or constant-pool references outside the relocated prefix, in BOTH trees |
| H3 | artifact-glob consumers (`release.yml` `ls Mental-*.jar \| head -n1`; `scripts/integration-matrix.sh` `sort -V \| tail -1`) pick an intermediate | the mega jar takes the canonical `Mental-<version>.jar` name and is the ONLY `Mental-*.jar` in `core/build/libs/`; intermediates live under `core/build/jvmdg/` (same for the tester) |
| H4 | downgraded records are not reflective records (`Class.isRecord()` false, no `RecordComponent`) | Mental's config is hand-parsed and the kernel is reflection-free by design; `verifyDowngrade` additionally greps the shaded tree for `isRecord`/`RecordComponent` usage to prove no dependency does this |
| H5 | MR-awareness varies by plugin loader — which tree actually loaded must never be assumed | boot report prints the **bytecode tier** read from the plugin's own class bytes (major 52 ⇒ downgraded, 61 ⇒ modern) — self-inspection via `getResourceAsStream`, no injected marker class (both trees are the same source, so a source-level marker cannot fork); the tester asserts the tier expectation per era |
| H6 | 1.14.4 fake-player join: Paper 1.14 introduced async chunks — the 1.15+ sync-join necessity likely starts at 1.14 | v1_14_R1 takes the synchronous-join path; scout shapes verify the exact NMS surface |
| H7 | sentinel/majors confusion: `versions/17` contains third-party classes that are v52 in BOTH trees (packetevents/bstats are Java-8 targets already) | overlay contains ONLY first-party classes (`me/vexmc/mental/**` minus `me/vexmc/mental/lib/**`; tester: minus `tester/lib/**`); majors audit asserts base max == 52, overlay == exactly 61, and the sentinel is a named first-party class (`me/vexmc/mental/v5/MentalPluginV5.class`) |
| H8 | JDK-8 availability: Temurin 8 has no macOS-arm64 build | foojay toolchain provisioning (already in `settings.gradle.kts`) serves Zulu/Corretto on arm64 macs and Temurin on linux CI; the servers AND the `verifyJdk8Api` baseline both resolve through `javaToolchains.launcherFor(8)` |
| H9 | CI ordering: Phase 1 lands the JDK-8-baseline gate before Phase 2 adds 8 to the matrix `jdks` list | the gate self-provisions via Gradle toolchain auto-download, independent of `setup-java`; no ordering constraint |
| H10 | a leftover result masquerading as PASS | unchanged nonce honesty rule — every gate quotes `PASS nonce=<uuid>` verbatim against its own fresh nonce |

## Quiet-correctness requirements

- **Q1** — the bytecode tier is a per-version live FACT: boot report line +
  tester assertion (legacy-on-Java-8 ⇒ `downgraded`; 1.17.1+/Folia ⇒ expected
  `modern`, and if a modern loader surprises us by serving base, the assertion
  fails loudly and decision D-1 is revisited with evidence — never silently).
- **Q2** — `verifyDowngrade`: base-tree max major 52; overlay first-party
  classes exactly major 61; `Multi-Release: true` present; sentinel forked.
- **Q3** — `verifyJdk8Api` and `verifyDowngrade` run in `check` (every
  `./gradlew build`), not only at release.
- **Q4** — kernel stays pure-JDK, Bukkit-free, additive-only; no source file
  changes for the downgrade itself.
- **Q5** — zero-touch and era-exact no-op defaults are untouched: this
  campaign changes WHERE the jar loads, never what it does.

## Decision log

- **D-1 MRJAR over flat downgrade** — keeps the modern path byte-identical to
  the 2.3.0/2.3.1 line the matrix has been proving for months; StarEnchants
  proves modern Paper honors it, and the scout's javap sweep confirms every
  modern loader in our matrix (1.16.5 reflective, 1.17.1 direct, 26.1.2 both
  pipelines) opens the plugin JarFile runtime-versioned. Produced via jvmdg's
  built-in `multiReleaseOriginal` (per-class: third-party v52 classes get no
  duplicate), NOT a manual merge. Fallback to flat (base-only) ONLY if Q1
  evidence shows modern loaders serving base — decided then, with the tier
  facts on the table.
- **D-2 JDG wiring: the official Gradle plugin `xyz.wagyourtail.jvmdowngrader`
  1.3.6** (Plugin Portal; `DowngradeJar` + `ShadeJar` tasks against the
  shadowJar output, `shadePath` = `me/vexmc/mental/lib/jvmdg/`,
  `multiReleaseOriginal` on). Version pinned in the version catalog. The
  StarEnchants CLI recipe (including the self-contained-api util fix) is the
  proven fallback if the plugin path shows the util-runtime gap (H2) — switch
  only with gate evidence.
- **D-3 (REVISED per owner, 2026-07-03): each legacy entry runs on the NEWEST
  Java its server boots flagless** — no outdated-Java advisories, maximal JVM
  performance (GC/JIT improvements dominate; this is a per-version empirical
  fact from the round-2 ladder probe, not an assumption). The jar still LOADS
  from Java 8 up (base tree v52), so a minimum-Java admin install remains
  supported — the matrix simply tests the recommended maximal-Java
  configuration. Expected shape (evidence pending): 1.9.4–1.12.2 on the
  newest of {25,21,17} that boots (no version guard exists there),
  1.13.2 ≈ 11–16, 1.14.4 = 13 (its guard's hard cap), 1.15.2 ≈ 13–16,
  1.16.5 = 16 (its own JvmChecker asks for 16).
- **D-7 (owner-directed, evidence-final) tiered mega-jar bytecode**, produced
  by jvmdg from the ONE compilation. The round-2 javap map settles the tier
  set:
  - **base v52** — read by 1.12.2–1.15.2 on ANY JVM (their PluginClassLoader
    opens a plain `new JarFile(File)`, provably never runtime-versioned:
    ctor `invokespecial JarFile.<init>(File)` at offsets 66/80, `getJarEntry`
    reads), and by anything run on a Java 8 JVM (the JVM ignores
    `META-INF/versions/`). A **versions/13 tier is dead weight — killed**:
    1.14.4/1.15.2 would never read it.
  - **versions/16 (major 60, jvmdg `multiReleaseVersions += VERSION_16`)** —
    read by exactly 1.16.5-on-Java-16 (its loader opens reflectively
    runtime-versioned; Java 16 caps class majors at 60 so it cannot take the
    v61 tier).
  - **versions/17 (major 61, the ORIGINAL bytecode, jvmdg
    `multiReleaseOriginal`)** — read by 1.17+ (direct/pipeline MR), AND — the
    round-2 surprise — by **1.9.4/1.10.2/1.11.2 on Java 9+**: those loaders
    have no JarFile of their own and delegate to `URLClassLoader.findClass`,
    whose `URLClassPath$JarLoader` opens runtime-versioned. On their maximal
    JVMs the three oldest versions run ORIGINAL modern bytecode.
  - **No tier above 61 exists or is needed**: the compilation targets
    Java 17; v61 loads and JITs natively on Java 21/25 (bytecode version does
    not throttle the JIT), so a "Java 25 tier" would require a different
    compilation and buy nothing.
  Each support-matrix entry declares its expected tier (`bytecodeTier`), and
  the tester asserts the ACTUAL loaded major against it (Q1) — which tree
  loaded is always a live fact. jvmdg retains MR copies per-class only where
  the original major exceeded the tier target, so the already-v52 shaded
  third-party classes (packetevents/bstats) never duplicate (H7 by
  construction).
- **D-8 (spike-driven) cross-plugin stub isolation.** jvmdg prunes its shaded
  runtime to each jar's referenced arities AND rewrites Java-9+ types inside
  method descriptors — so two downgraded plugins must never share stub-typed
  API descriptors or a same-FQN pruned runtime (both failure modes were
  reproduced live: `NoSuchMethodError: …J_U_List.of(7-arg)` via Bukkit's
  shared class cache, and `NoClassDefFoundError:
  xyz/wagyourtail/jvmdg/j17/stub/…J_U_R_RandomGenerator` baked into the
  kernel's `KnockbackEngine.computeBase(RandomGenerator…)` descriptor as
  called by the tester). Resolution: **distinct shade prefixes**
  (`me/vexmc/mental/lib/jvmdg` vs `me/vexmc/mental/tester/lib/jvmdg`) plus a
  **Java-8-native additive overload** `computeBase(…, java.util.Random)` in
  the kernel (additive-only invariant preserved; `java.util.Random`
  implements `RandomGenerator` on 17+ so it delegates trivially) with the
  tester switched to it — no stub type then crosses any plugin boundary,
  enforced by a build gate: the tester jar's constant pool must contain ZERO
  references to Mental's `me/vexmc/mental/lib/jvmdg` prefix.
- **D-4 the tester ships the same mega-jar pipeline** — it must load on the
  Java-8 servers, and its modern suites keep running original bytecode.
- **D-5 version 2.3.2, hyphenless** — the releaser publishes it as a full
  release and moves "Latest" off v2.2.2 for the first time in the 2.3 line.
- **D-6 1.14.4 rides the `release` CI lane** (like its legacy neighbours; the
  `pr` lane keeps 1.9.4 as the legacy sentry).

## Phases

Sequential; each dispatches only after the previous phase's diff passes Fable
line review and its gate evidence (fresh nonce, verbatim) is in the plan's
outcome log.

### Phase 1 — the mega-jar build pipeline (Opus)

1. **Kernel (D-8, additive):** `KnockbackEngine.computeBase(…,
   java.util.Random)` overload delegating to the `RandomGenerator` variant;
   unit pin proving identical outputs; tester call sites
   (`ProjectileSuite`, `FishingSuite`, any other `RandomGenerator` use —
   grep) switch to it. Audit kernel/api/platform public descriptors for any
   OTHER Java-9+ JDK type crossing the tester boundary; fix additively if
   found.
2. **Core build:** jvmdg plugin 1.3.6 (version catalog); `DowngradeJar` on
   the shadowJar output — target Java 8, `multiReleaseOriginal` on,
   `multiReleaseVersions += VERSION_16` (D-7), downgrade classpath =
   `compileClasspath` PLUS what the shadowJar folds in beyond it (the
   compat-folia classes need the Folia-scheduler API supertypes — the spike
   needed paper-api 1.20.6 for zero-warning resolution; treat downgrade-time
   jvmdg warnings as failures); `ShadeJar` prefix
   `me/vexmc/mental/lib/jvmdg/`, output = the canonical
   `Mental-<version>.jar` as the ONLY `Mental-*.jar` in `core/build/libs/`
   (H3 — shadowJar + intermediates move to a directory the globs cannot see,
   e.g. `build/jvmdg-stage/`); `build`, the run tasks' `pluginJars`, and
   japicmp stay coherent.
3. **Tester build:** same pipeline, prefix `me/vexmc/mental/tester/lib/jvmdg/`
   (D-8 distinct-prefix rule), downgrade classpath includes Mental's
   shadowJar (kernel/api supertypes) + paper-api + netty; final
   `MentalTester-<version>.jar` the only tester jar in `tester/build/libs/`.
4. **Boot-report tier line (H5/Q1):** self-inspect the plugin's own class
   bytes (`getResourceAsStream` on `MentalPluginV5.class`, bytes 6–7) —
   prints `bytecode tier: modern (v61)` / `intermediate (v60)` /
   `downgraded (v52)`; identical source works in every tree by construction.
   Tester BootSuite asserts the tier: `-Dmental.tester.tier` when set
   (Phase 2 wires it per-entry), else JVM-derived default (8 ⇒ 52, 16 ⇒ 60,
   17+ ⇒ 61).
5. **Verify tasks, all in `check`:**
   - `verifyDowngrade` (Q2): `Multi-Release: true`; base tree ZERO entries
     >52; `versions/16` ZERO >60; `versions/17` first-party classes exactly
     61 and its class-name set equals the base first-party set (no drops);
     sentinel `me/vexmc/mental/v5/MentalPluginV5.class` forked across
     trees; H4 grep (`isRecord`/`RecordComponent`) scoped outside the jvmdg
     runtime.
   - `verifyJdk8Api` (H1): member-level closed-world scan of the mega-jar
     BASE tree — `java.*/javax.*` refs validated against the
     toolchain-provisioned JDK-8 `rt.jar` (+ `jre/lib` siblings), and every
     non-JDK ref must resolve in-jar or in the documented server-provided
     ignore set (subsumes the H2 util trap and stub-pruning gaps); ASM via a
     detached configuration + JavaExec tool (ported from StarEnchants'
     `scripts/tools/Jdk8ApiGate.java`); anchored-prefix allowlist file
     starts EMPTY. Same scan for the tester jar (its ignore set also spans
     `me/vexmc/mental/` — Mental provides those at runtime).
   - `verifyTesterIsolation` (D-8): tester jar constant pool contains ZERO
     `me/vexmc/mental/lib/jvmdg` references.
   - `verifyRelocation` extended: runs on the MEGA jar (all trees),
     `net/kyori` AND `xyz/wagyourtail` outside the relocated prefixes.
6. **Gate:** `./gradlew build` green (unit + japicmp + kernel-Bukkit-free +
   all verify tasks); fresh-nonce boots — 1.17.1 + 26.1.2 FULL suite via the
   normal Gradle tasks (tier line captured, expected `modern (v61)`); ad-hoc
   1.13.2 AND 1.14.4 on foojay Java 8, flagless, jars copied into `plugins/`
   (no `-add-plugin` on those builds), bare `nogui`, FULL suite PASS (tier
   `downgraded (v52)`). Quote every nonce line verbatim.

### Phase 2 — the native-JDK matrix flip (Opus)

`support-matrix.json`: every 1.9.4–1.16.5 entry → the round-2 ladder's
newest-flagless-Java `jdk` value (D-3), `serverFlags` removed, and every
paper entry gains its declared `bytecodeTier` (D-7); `_comment` rewritten
(the flag story is dead; the 1.14.4 hole note survives until Phase 3).
`scripts/integration-matrix.sh`: `java_for` handles the new JDK set (the
foojay-provisioned paths under `~/.gradle/jdks/` or a helpful error naming
the Gradle provisioning route), and the scout-found `--nogui` trap is fixed —
legacy joptsimple rejects the double-dash form (help-dump + exit); the bare
`nogui` token works range-wide. Tester tier assertion switches from
JVM-derived expectation to the entry-declared `bytecodeTier` (plumbed via a
`-Dmental.tester.tier` system property the run tasks pass). **Gate:** full
15-entry `integrationTestMatrix` + `integrationTestOcm`, fresh nonces, zero
`IgnoreJavaVersion` anywhere in the repo outside historical docs, zero
Java-advisory lines in the legacy boot logs.

### Phase 3 — 1.14.4 at full tier (Opus)

`support-matrix.json` gains `{ "version": "1.14.4", "jdk": 8, "platform":
"paper", "suites": "full", "ci": "release" }` (D-6) and loses the hole note.
Scout verdict: the FakePlayer needs ZERO code changes (every reflective scan
resolves on v1_14_R1; the sync-join probe routes it correctly) — the code
work is the shapes research doc
(`docs/superpowers/research/2026-07-03-v1_14_R1-shapes.md`), the stale
"1.14+ async" comment fix, the tester BootSuite manifest-expectations row for
1.14.4 (PDC present, AbstractArrow present, keyed-recipe/absorption/
attack-cooldown/isInWater floors per the 2026-07-02 doc), and whatever the
live gate surfaces; any probe gap is fixed at the platform seam per house
rules (boot-time probing, never version conditionals). **Gate:** 1.14.4
full-suite PASS twice consecutively (fresh nonce each), 1.13.2 + 1.15.2
re-PASS, then the full 16-entry matrix + OCM.

### Phase 4 — reconciliation and release prep (Opus)

`release.yml` notes template: the one-jar story ("Supports Paper 1.9.4 →
26.1.2 · Folia — Java 8+ on legacy, Java 17+ on 1.17+"), the
IgnoreJavaVersion line and the 1.14.4 hole line DELETED; README / CLAUDE.md /
skills (`paper-cross-version`, `matrix-gate`, `live-server-testing`) support
story updated; `gradle.properties` → `2.3.2` with the comment updated (first
non-beta of the 2.3 line). **Gate:** the release-candidate run — full matrix +
Folia + OCM fresh-nonce — plus workflow YAML lint.

### Release (Fable)

Merge `feat/full-range` → main; the auto-releaser must pass 16 paper entries +
Folia + OCM before tagging v2.3.2 as a FULL release taking "Latest"; verify
tag/assets/notes semantics and close out.

## Execution protocol

Identical to the backport campaign: Opus executors receive design-complete
briefs (this document is the shared context); they hold their turn open
through gates (blocking foreground waits — no monitor-and-yield); every gate
quotes its nonce line verbatim; any conflict between an assertion and reality
is escalated, never papered over by weakening the assertion. Fable line-reviews
each phase's complete diff before the next phase dispatches. Commit as you go,
conventional commits with prose bodies.

## Scout evidence (2026-07-03)

From the `scout-full-range` workflow (three parallel recon agents + spike; all
Opus, javap/live-verified).

**JDK-8 provisioning (H8 resolved):** foojay auto-provisions headlessly on
this arm64 Mac — it serves **Temurin 8u492 x86_64** (no arm64 Temurin 8
exists) which runs under Rosetta 2; `launcherFor(8)` resolves with zero
interaction to
`~/.gradle/jdks/temurin-8-x86_64-os_x.2/jdk8u492-b09/Contents/Home`. CI
(linux x64) gets native Temurin 8.

**Flagless native-Java boots — ALL SIX legacy versions boot vanilla on Java 8
with NO IgnoreJavaVersion flag:** 1.9.4 (Done 2.5s), 1.12.2 (2.2s), 1.13.2
(1.5s), **1.14.4 (6.9s — the "impossible" version boots cleanly on Java 8;
the Java-13 cap only ever excluded Java-17 classfiles)**, 1.15.2 (4.6s),
1.16.5 (4.7s, plus a non-fatal "outdated Java" advisory — cosmetic). Trap
found: legacy joptsimple REJECTS `--nogui` (help-dump + exit); the bare
`nogui` token works across the whole range → `scripts/integration-matrix.sh`
must switch (Phase 2). The Gradle run-paper path is unaffected (the canonical
gates always passed).

**JDG research (D-1/D-2 updated):** Gradle plugin `xyz.wagyourtail.jvmdowngrader`
**1.3.6** on the Plugin Portal; `DowngradeJar` (classpath defaults to
`compileClasspath`) + `ShadeJar` (`shadePath` closure → our
`me/vexmc/mental/lib/jvmdg/` prefix); CLI `-all` jar on Maven Central
(`…/jvmdowngrader/1.3.6/jvmdowngrader-1.3.6-all.jar`, ~1.5MB, self-contained,
bundled api stubs, `allowMaven=false`). **MRJAR support is BUILT IN** —
`multiReleaseOriginal` keeps the original bytecode under
`META-INF/versions/<origVersion>` with the downgrade as base, per-class (a
class already ≤ target, e.g. shaded packetevents/bstats at v52, gets no MR
copy — H7 handled by construction). StarEnchants' manual MRJAR merge is
therefore superseded by the flag; their util-shading recipe remains the
fallback if the spike shows the shade missing runtime helpers. Limitations
sweep: records/sealed/pattern-switches/string-concat-indy/nestmates all
handled; classes at/below target pass through untouched; risks confirmed as
(1) un-stubbed modern `java.*` APIs pass through silently → the H1
closed-world gate stands, and (2) reflective record introspection by
un-downgraded code → H4 grep stands. License: LGPL-2.1 (dual-licensed);
shading the api is a "Combined Work" — fine for this public-source repo, and
Phase 4 adds the LGPL notice for the shaded `lib/jvmdg` classes.

**v1_14_R1 shapes (Phase 3 shrinks to near-zero code):** the tester FakePlayer
is 100% reflective-scan-driven — **zero code changes needed for v1_14_R1**;
every branch resolves (javap-verified per claim). 1.14.4 straddles the eras:
modern NMS shapes (Vec3D `mot`/`setMot`, `PlayerInteractManager(WorldServer)`,
PDC + AbstractArrow present, `EnumGamemode` top-level) with the **1.13-era
synchronous join** (`PlayerList.a` inline; the async/`postChunkLoadJoin` split
is 1.15+, NOT 1.14+ — the `legacyAsyncJoin()` probe routes it correctly; one
stale comment to fix). `CraftMagicNumbers.SUPPORTED_API = ["1.13","1.14"]` —
api-version `1.13` accepted. Full rows land in
`docs/superpowers/research/2026-07-03-v1_14_R1-shapes.md` (Phase 3).

**Plugin-loader MR-awareness (D-1 confirmed):** 1.12.2 NO (plain JarFile),
1.13.2 NO, 1.16.5 YES (reflective, Java 9+), 1.17.1 YES (direct), 26.1.2 YES
for BOTH plugin pipelines (Paper's `FileProviderSource` opens the classloading
JarFile with `JarFile.runtimeVersion()` for paper AND legacy plugin.yml
plugins). Every configuration is safe: modern servers read `versions/17`
originals; legacy-on-Java-8 reads base (the JVM ignores `versions/`); a
legacy server still run on Java 17 reads whichever tree its loader supports —
same code either way. Tester tier expectation derives from the running JVM:
Java 8 ⇒ major 52 (downgraded), Java 16+ ⇒ major 61 (modern).

**Spike (downgrade e2e) — the whole design proven before Phase 1:** both
2.3.1-beta jars downgraded to clean class-v52 (zero entries >52; zero
downgrade warnings once the classpath carried paper-api 1.17.1 + paper-api
1.20.6-for-Folia-schedulers + guava + adventure 4.17 + netty; Mental
+2.3% size, tester +8.5%). **Paper 1.13.2 build 657 on native Temurin 8,
flagless: FULL suite 55 RUN / 55 PASS, 0 FAIL** (`PASS
nonce=64857764-8D56-4753-956C-1A0B99FA3700`, nonce-matched). **Paper 1.14.4
on native Java 8, flagless: FULL suite 55 RUN / 55 PASS, 0 FAIL** (`PASS
nonce=23A6F18E-F644-4195-8B67-F64028F273EF`) — stronger than predicted: the
reflective FakePlayer bootstrapped v1_14_R1 clientless players unmodified,
and the boot report shows era-correct resolver selections (PDC present so no
B10 warning, absorption via the 1.14+ Bukkit API where 1.13.2 correctly used
NMS, `crit-posture[climbing=FEET_BLOCK in-water=FEET_BLOCK
attack-charge=NMS_STRENGTH]`, probe transport TRANSACTION, `currentTick=true`).
Zero LinkageError/NoClassDefFoundError/NoSuchMethodError/VerifyError in both
final runs. The two intermediate failures that produced D-8 are documented
there. Also: `-add-plugin=` does NOT exist on these old Paper builds
(joptsimple help-dump + exit) — plugin injection on legacy = copy into
`plugins/` (Phase 2 fixes `scripts/integration-matrix.sh`, whose
"proven from the floor up" comment is wrong; the Gradle run-paper path
handles this itself and stays the canonical gate). Downgraded artifacts:
`<scratchpad>/spike/Mental-java8.jar`, `MentalTester-java8.jar`; jvmdg CLI
sha256 `dee569b7e231a47ade2281eb967b21c809d2f415820a1161acad2d1ca2237fb5`.

**Loader MR map (round 2, completes D-7):** 1.9.4/1.10.2/1.11.2 HONOR MR
tiers via URLClassLoader delegation (no own JarFile — verified zero
`java/util/jar/JarFile` constants in the class); 1.12.2→1.15.2 provably do
NOT (plain `new JarFile(File)`); 1.16.5+ honor (reflective/direct/pipeline).
The non-honoring window is exactly the manual-plain-JarFile era.

**Max-Java ladder:** round-2 ladder agent failed to emit its report — re-run
dispatched alongside Phase 1; its verdicts land here and gate only Phase 2's
`jdk`/`bytecodeTier` values.

## Outcome log

_Per-phase outcomes, review findings, and gate evidence land here as the
campaign executes._
