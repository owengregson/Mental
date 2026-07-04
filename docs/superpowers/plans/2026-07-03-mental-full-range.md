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
  - **versions/16 — DROPPED (Phase 1 escalation 1):** jvmdg 1.3.6's `-mro`
    and `-mr` are CLI-verified mutually exclusive, and versions/17 (the
    original bytecode) is the D-1-critical tier. 1.16.5-on-Java-16 therefore
    reads the base v52 tier — fully functional and live-gated; a true v60
    tier would need a two-pass merge, deferred with that evidence.
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

Pre-step (two boots, ~5 min): probe 1.13.2 on Java 15 then 14, and 1.15.2 on
Java 14 (foojay `launcherFor`; same flagless recipe as the ladder) — take the
newest CLEAN rung per version. Then `support-matrix.json`: legacy `jdk`
values per the ladder map (1.9.4–1.12.2 → 21; 1.13.2 → probed; 1.14.4 → 13;
1.15.2 → probed; 1.16.5 → 16), `serverFlags` removed everywhere, every paper
entry gains its declared `bytecodeTier` (D-7 map as revised by Phase 1
escalation 1: 1.9.4–1.11.2 → 61, 1.12.2–1.15.2 → 52, **1.16.5 → 52**,
modern/folia → 61); `_comment` rewritten (the flag story is dead — record
the 25-boots-but-JEP-472-warns fact and why 21; the 1.14.4 hole note
survives until Phase 3). Run tasks pass
`-Dmental.tester.tier=<bytecodeTier>` (mandatory — the JVM-derived default
is WRONG for plain-loader entries like 1.12.2@21) and the tester asserts it;
fix F-FR1 while there: `BootSuite.expectedBytecodeMajor()`'s JVM-derived
default still maps Java 16 ⇒ 60, but the v60 tier no longer exists — map
16 ⇒ 52 with the escalation-1 comment. Step-down rule: if a version's FULL
suite fails on its ladder-max JDK and the failure is JVM-version-caused
(encapsulation/Unsafe/reflection drift, not a product bug), step that entry
down one rung (e.g. 21 → 17, both boot-proven) and document the evidence in
the outcome log — never weaken a suite assertion to keep the higher rung.
`scripts/integration-matrix.sh`: `java_for` maps the full JDK set to the
foojay-provisioned homes under `~/.gradle/jdks/` (helpful error naming the
Gradle provisioning route when absent); fix the scout-found `--nogui` trap
(bare `nogui` token range-wide); fix plugin injection — `-add-plugin=` does
not exist on the legacy builds (spike evidence; the "proven from the floor
up" comment is wrong): copy the jars into `plugins/` for legacy entries (or
universally) with the same pristine-reset discipline; pass the tier property.
**Gate:** full 15-entry `integrationTestMatrix` + `integrationTestOcm`,
fresh nonces, tier lines quoted per entry matching `bytecodeTier`, zero
`IgnoreJavaVersion` anywhere outside historical docs, zero Java-advisory
lines in the legacy boot logs; PLUS one `scripts/integration-matrix.sh` run
ending `MATRIX PASSED` (the script was rewritten — its fixes need their own
live proof).

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

`release.yml` + `build.yml`: notes template rewritten to the one-jar story
("Supports Paper 1.9.4 → 26.1.2 · Folia — one jar, Java 8+ on legacy,
Java 17+ on 1.17+; each version tested on its maximal clean JVM"), the
IgnoreJavaVersion line and the 1.14.4 hole line DELETED; the `setup-java`
steps stop installing the full matrix JDK set (Temurin has no 13/14/16) —
install only the build JDK(s) and let the Gradle foojay toolchain
auto-provision server JDKs, exactly as locally; cache `~/.gradle/jdks` in the
runner caches. README / CLAUDE.md / skills (`paper-cross-version`,
`matrix-gate`, `live-server-testing`) support story updated (including the
bytecode-tier table and the per-version recommended JVM). LGPL-2.1 notice for
the shaded jvmdg runtime (scout license evidence) added where the repo keeps
third-party notices. `gradle.properties` → `2.3.2` with the comment updated
(first non-beta of the 2.3 line). **Gate:** the release-candidate run — full
16-entry matrix + Folia + OCM fresh-nonce — plus workflow YAML lint.

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

**Max-Java ladder (re-run, complete):** two gating regimes exist. 1.9.4–1.12.2
have NO paperclip Java cap — they boot on Java 25, but Java 24+ prints the
JEP-472 native-access / `sun.misc.Unsafe`-deprecation warning walls (hawtjni
`System::loadLibrary` on 1.9.4–1.11.2; + log4j-disruptor `Unsafe` and JNA on
1.12.2); **Java 21 is their newest warning-free rung (verified clean on all
four)**. 1.13.2–1.16.5 hard-refuse too-new Java flagless: 1.13.2 boots 13
clean (its refusal message "Only up to Java 12" is stale — real cap 13–15,
14/15 unprobed), 1.14.4 = 13 clean (cap confirmed), 1.15.2 boots 13 clean and
its own refusal names Java 14 as the real cap (14 unprobed — a rung is on
the table), 1.16.5 = exactly 16, clean, no PaperJvmChecker banner (8 prints
the outdated-Java wall, 17 is refused). JDK availability: foojay resolves
8/11/13/16/17/21/25 headlessly on this arm64 mac (13/16 as x64-Rosetta —
AdoptOpenJDK 13.0.2 / Temurin 16.0.2; 11/21 native aarch64 Temurin; CI linux
has native builds of all). Owner's intent is warning-free maximal-Java ⇒
**jdk map: 1.9.4–1.12.2 → 21; 1.13.2 → 13 (Phase 2 probes 15/14 first and
takes the newest clean rung); 1.14.4 → 13; 1.15.2 → 14 if it boots clean
else 13; 1.16.5 → 16; modern entries unchanged**. Resulting `bytecodeTier`
map (jdk × loader-MR): 1.9.4/1.10.2/1.11.2 → **61** (URLClassLoader MR on
Java 21 serves the ORIGINAL bytecode); 1.12.2 → 52 (plain loader);
1.13.2/1.14.4/1.15.2 → 52 (plain loaders); 1.16.5 → **52** (MR-aware on
Java 16, but the v60 tier was dropped — Phase 1 escalation 1; the loader
finds no versioned tier ≤ 16 and serves base); 1.17.1+/Folia/OCM → 61. CI
note for Phase 4: Temurin has no
13/14/16 for `setup-java` — the workflows must install only the build JDK(s)
via setup-java and let the Gradle foojay toolchain auto-provision the server
JDKs (exactly as it does locally).

## Outcome log

_Per-phase outcomes, review findings, and gate evidence land here as the
campaign executes._

### Phase 1 outcome (2026-07-03, Opus) — mega-jar pipeline shipped, all gates green

Commit `42ee688`. The one-jar mega-jar pipeline, kernel D-8 overload, boot-report
tier line + tester assertion, and the four verify gates are in and green end to
end. Build-script mechanics all resolved through the jvmdg gradle plugin
(`defaultTask`/`defaultShadeTask`); the CLI-recipe fallback (H2) was NOT needed —
the plugin shades its own runtime.

**Gate evidence (verbatim, fresh nonces):**
- `./gradlew build` — GREEN: unit tests (+ the new D-8 overload equivalence pin),
  japicmp (`:api:apiCompat` UP-TO-DATE — the kernel overload + ValvePayload
  toString are additive), kernel-Bukkit-free, and all four verify tasks.
- `[1.17.1] integration tests passed (nonce=bb20e63d-3e93-453c-b4e2-4e5928d1d271)`
  — boot log: `[Mental] bytecode tier: modern (v61)`; 0 linkage errors.
- `[26.1.2] integration tests passed (nonce=5415edc5-a788-4415-a29f-ac1e5fad9a0c)`
  — boot log: `[Mental] bytecode tier: modern (v61)`; 0 linkage errors.
- Ad-hoc 1.13.2 on foojay Temurin 8u492, flagless, mega jars copied into
  `plugins/`, bare `nogui --port 25704`, full suite: `PASS
  nonce=C0EA33C7-B722-4357-9E0C-A2E231A82A07`; `[Mental] bytecode tier:
  downgraded (v52)`; 0 linkage errors.
- Ad-hoc 1.14.4 (probe jar + copied `cache/`) on Temurin 8, flagless, full suite:
  `PASS nonce=79BE2CB4-DAAC-4ED6-843D-4DFB02B42E72`; `[Mental] bytecode tier:
  downgraded (v52)`; 0 linkage errors.
- Each verify task proven to FAIL on a broken invariant (verifyRelocation/
  verifyDowngrade/verifyJdk8Api organically during dev; verifyTesterIsolation by
  temporarily scanning the core mega jar; the jvmdg-warning gate by removing the
  compat-folia classpath → 45 captured ERROR lines → build failed).

**Decisions / escalations (none blocked delivery; Phases 2–4 must absorb these):**

1. **jvmdg `-mro` and `-mr` are MUTUALLY EXCLUSIVE (D-7 revised).** CLI-verified:
   `-mro` alone gives base v52 + versions/17 v61; `-mro -mr 60` DROPS the original
   and yields only versions/16. jvmdg 1.3.6 cannot co-produce versions/16 AND
   versions/17. versions/17 (the original v61) is the D-1-critical tier, so the
   pipeline ships **base v52 + versions/17 v61, NO versions/16**. Consequence for
   Phase 2: **1.16.5-on-Java-16 reads the base v52 tier, not v60** — fully
   functional (v52 is the tested legacy base, and Mental uses `sealed` types so v60
   would be a real downgrade, not a no-op). Phase 2 must declare 1.16.5's
   `bytecodeTier` as **52**, not 60. If a true v60 tier is ever required, it needs a
   two-pass merge (jvmdg `-mro` for base+v17, a second `-mr 60` pass, merge the
   versions/16 tree, then shade) — deferred with this evidence.

2. **jvmdg overlays only classes that use SHIMMED Java-9+ APIs (D-1 nuance).**
   `-mro` keeps the original v61 only for classes whose downgrade could behave
   differently on modern (the ones calling jvmdg's API stubs). Classes needing only
   behaviour-PRESERVING language downgrades (string-concat→StringBuilder, record/
   sealed/nestmate metadata annotations) get NO versions/17 overlay and load as v52
   on modern — 180 first-party classes forked to v61, **88 behaviour-preserving
   base-only** (all proven shim-free by verifyDowngrade). So D-1 is **behaviour
   identity, not literal byte identity** for those 88; the live 1.17.1/26.1.2 gate
   proves the behaviour, and the tier-line sentinel (`MentalPluginV5`) IS forked so
   the modern tier report stays truthful. The original "class-set == base" assertion
   was too strict and was relaxed to "versions/17 ⊆ base, all v61, sentinel forked,
   base-only carry no jvmdg-shim ref".

3. **jvmdg 1.3.6 record-`toString` bug — caught by verifyJdk8Api (H1 worked).**
   `ValvePayload` (a record with `short` components) downgraded to a call to the
   non-existent `StringBuilder.append(short)` — a `NoSuchMethodError` reproduced on
   real Java 8. Fixed with an explicit `toString` widening each short to int
   (behaviour/API unchanged; the only such class in the tree). This is exactly the
   fast-path blind spot H1 exists for — the clientless suite never drove that
   `toString`.

4. **verifyJdk8Api ignore set grew (all documented in `core/build.gradle.kts`):**
   server-provided (org/bukkit, net/minecraft, com/destroystokyo, io/papermc,
   org/spigotmc, io/netty, com/mojang) + Paper-provided libs (com/google/gson,
   com/google/common) + the optional ViaVersion plugin (com/viaversion, packetevents
   guards it) + compile-only CLASS-retention annotation packages (org/jetbrains,
   org/intellij, org/jspecify, org/checkerframework, com/google/auto,
   com/google/errorprone) + jvmdg's own bare marker annotations (xyz/wagyourtail —
   @NestMembers/@RecordComponents/@PermittedSubClasses, never resolved at runtime).
   The anchored `--allow` file stays EMPTY.

5. **Gradle Kotlin DSL script-size fragility (build-hygiene warning for Phases
   2–4).** `core/build.gradle.kts` sits near the compiler's synthesized-method size
   limit: past it, the DSL SILENTLY drops every later top-level statement (task
   registration) with no compile error. Two concrete traps hit and fixed here: a
   **local `fun` inside a task-action lambda** truncates the body (all mega-jar
   scan helpers are TOP-LEVEL funs), and `project.javaexec` is **gone in Gradle 9**
   (use the injected `ExecOperations` via `serviceOf`). Also, `getByType<T>()` for a
   plugin's extension does NOT work cross-project (per-project plugin classloader —
   the tester mega jar is looked up by task NAME). Keep additions to this file lean;
   if it must grow, move the verify logic into an `apply(from = …)` script (a
   separate script class with its own budget).

### Phase 2 outcome (2026-07-03, Opus) — native-JDK matrix flipped, all gates green

Commits `e85a444` (the flip) + `9b7a75b` (two gate-discovered corrections). The
`-DPaper.IgnoreJavaVersion` server flag is dead: every legacy Paper build runs on
the newest Java it boots FLAGLESS, and the tester asserts the loaded Multi-Release
tier per entry.

**Pre-step rung probes (flagless, port 25703, bare `nogui`, never
IgnoreJavaVersion; foojay-provisioned AdoptOpenJDK 14.0.2 / 15.0.2 under Rosetta):**
- **1.13.2 → Java 13.** Java 15 (major 59) AND Java 14 (major 58) BOTH refuse and
  exit: `Unsupported Java detected (59.0/58.0). Only up to Java 12 is supported.`
  The scout's "real cap 13–15, 14/15 unprobed" is **DISPROVEN** — the real cap is
  Java 13 (the ladder's proven-clean rung). Newest clean rung = **13**.
- **1.15.2 → Java 14.** Java 14 boots CLEAN: `Done (5.473s)`, zero advisory lines.
  Its guard names 14 as the cap. Newest clean rung = **14**.

**Final jdk × bytecodeTier table (as landed, every tier live-asserted):**

| version | jdk | tier | loaded (live) | why |
|---|---|---|---|---|
| 1.9.4 | 21 | 61 | modern (v61) | Java 21 ≥17 → versions/17 via URLClassLoader-delegating loader |
| 1.10.2 | 21 | 61 | modern (v61) | ″ |
| 1.11.2 | 21 | 61 | modern (v61) | ″ |
| 1.12.2 | 21 | **61** | modern (v61) | ″ — round-2 javap mis-placed the boundary; 1.12.2's loader honors MR too |
| 1.13.2 | 13 | 52 | downgraded (v52) | Java 13 <17 → versions/17 unreachable → base |
| 1.15.2 | 14 | 52 | downgraded (v52) | Java 14 <17 → base |
| 1.16.5 | 16 | 52 | downgraded (v52) | Java 16 <17 → base |
| 1.17.1 / 1.18.2 / 1.19.4 | 17 | 61 | modern (v61) | Java 17 → versions/17 |
| 1.20.6 / 1.21.4 / 1.21.11 / 26.1.2 | 25 | 61 | modern (v61) | Java 25 → versions/17 |
| 26.1.2 Folia | 25 | 61 | modern (v61) | ″ |

**The one correction (escalate-don't-weaken, toward the observed truth):** the
first `integrationTestMatrix` run FAILED at 1.12.2 — the tester's tier assertion
caught `loaded bytecode major 61 … != expected 52`. 1.12.2 has no Java cap so it
runs on Java 21 (≥17) and its loader reaches versions/17 — it reads v61, exactly
like 1.9.4–1.11.2. The declaration (52, from the scout's plain-JarFile javap
claim) was the error, not the tester; corrected to **61** (`9b7a75b`). Standalone
tier-probes then confirmed 1.13.2/1.15.2/1.16.5 (Java 13/14/16, all <17) read v52
by the MR spec regardless of loader. No suite assertion was weakened; a wrong
prediction was corrected to the live fact. This was **not** a step-down (no rung
changed).

**Gate evidence (verbatim, fresh nonces):**
- `./gradlew build` — GREEN (unit + japicmp + kernel-Bukkit-free + verifyDowngrade
  / verifyJdk8Api / verifyRelocation / verifyTesterIsolation).
- `integrationTestMatrix` — **BUILD SUCCESSFUL in 18m 25s, all 15 entries**; every
  boot's `[Mental] bytecode tier:` line matched its declared `bytecodeTier`
  (table above); **legacy Java-advisory sweep = 0 lines across all 7 legacy logs**
  (OUTDATED / Unsupported Java / restricted method / terminally deprecated /
  illegal reflective / Unsafe / UnsupportedClassVersionError):
  - `[1.9.4] … (nonce=d3a63cfd-4a54-43f5-ad80-d5944fd65a54)`
  - `[1.10.2] … (nonce=4b4d057a-4838-4355-af4a-33048e4bad6d)`
  - `[1.11.2] … (nonce=b9ba10c7-1408-406d-81ce-50806cf53c3c)`
  - `[1.12.2] … (nonce=205d5a97-8e73-4026-9069-74fff6c2708b)` — tier 61, the URLClassLoader MR surprise, live-proven here for the first time
  - `[1.13.2] … (nonce=98c40f35-d534-452c-89ca-5ad106b31b65)`
  - `[1.15.2] … (nonce=8f525ddc-c674-46af-baa4-07fd6d576e55)`
  - `[1.16.5] … (nonce=b3094027-b467-4aed-ac0f-a4024ab2045f)`
  - `[1.17.1] … (nonce=5e2c11db-258e-48bf-a888-25df1aa2d883)`
  - `[1.18.2] … (nonce=3e88d96c-f64c-413e-afea-279d6a841d10)`
  - `[1.19.4] … (nonce=8c078a40-f3e2-4964-8680-de7469ea3f7f)`
  - `[1.20.6] … (nonce=8f7cab86-ffec-47ca-bc5f-c6ddaa364d70)`
  - `[1.21.4] … (nonce=92e24ac6-eded-4715-8ba5-c86a32e5f9a7)`
  - `[1.21.11] … (nonce=34255401-e526-4126-afda-6bf1e7c0ceb1)`
  - `[26.1.2] … (nonce=fc40c557-a89c-4c55-b988-1b029d296f40)`
  - `[26.1.2 Folia] … (nonce=7683f079-ebe1-4ec1-a394-5f169ba4db2d)`
- `integrationTestOcm` — BUILD SUCCESSFUL, both entries tier v61:
  - `[1.17.1 +OCM] … (nonce=964845ef-8a9a-4fb8-b5fb-893f2421d3aa)`
  - `[26.1.2 +OCM] … (nonce=fa403127-5f2b-4542-96d1-684007be1bd5)`
- `scripts/integration-matrix.sh --no-ocm` — **MATRIX PASSED (14 servers)** (the
  rewritten script's own live proof: bare `nogui`, jars copied into `plugins/`,
  per-entry JDK, `-Dmental.tester.tier` asserted). The FIRST script run FAILED
  (NO-RESULT for 1.13.2/1.15.2/1.16.5): `/usr/libexec/java_home -v N` is "N or
  newer" on macOS and handed the capped servers Java 25 → refused. Fixed
  (`9b7a75b`): `java_home_for` validates the returned home's exact major (release
  file) else uses the major-named foojay glob; re-run passed clean, 1.9.4 on
  Java 21 with zero JEP-472 advisories.
- `grep -r IgnoreJavaVersion` — zero hits in Phase-2-owned live code/config: the
  build script, the integration script, and BootSuite carry **0**; support-matrix
  carries exactly **1** — the deliberately-kept 1.14.4-hole sentence (Phase 3
  removes it). Remaining hits are historical docs + this plan, plus README.md /
  release.yml / the matrix-gate skill — all **Phase 4's** explicit scope (the
  plan tasks Phase 4 with deleting the README/workflow flag lines and updating the
  skills), untouched here by design. The live `serverFlags` mechanism is entirely
  gone (0 occurrences repo-wide outside historical docs).

**Notes for Phases 3–4:**
1. **The loader-MR map is simpler than D-7 framed it.** With only base v52 +
   versions/17 in the jar, the legacy tier is purely a function of the entry's
   JVM: ≥17 → 61, <17 → 52. The plain-JarFile-vs-URLClassLoader boundary is moot
   for 1.13.2–1.16.5 (their JVMs are <17 regardless), and wrong for 1.12.2 (its
   loader DOES honor MR). Phase 3's 1.14.4 runs on Java 8 → tier **52**.
2. **`/usr/libexec/java_home -v N` is minimum-version, not exact** — any local
   tooling picking a JDK by feature version must validate the major (the script
   now does). CI (Phase 4) sidesteps this: it lets the Gradle foojay toolchain
   resolve JDKs (`launcherFor(N)` is exact), installing only the build JDK via
   `setup-java`.
3. **Dead-but-harmless Phase-1 artifacts left in place** (out of Phase-2 scope):
   `MentalPluginV5.describeBytecodeTier` still has an `intermediate (v60)` branch
   and the verify tasks still guard a `versions/16` tree — neither can occur now
   (no v60 tier), so both are no-ops. Fold away when convenient.

### Phase 3 outcome (2026-07-03, Opus) — 1.14.4 at full tier, the last hole closed

Commit `bbfe10a`. 1.14.4 is now a supported full-tier matrix entry; the range is
Paper 1.9.4 → 26.x with **no holes and no flags**. The scout was right — the
FakePlayer needed **zero code changes** (every `v1_14_R1` reflective branch
resolves; the `legacyAsyncJoin()` probe routes the synchronous join correctly).

**1.14.4 download-source verdict (load-bearing for CI): GREEN.** `fill.papermc.io`
v3 serves Paper **1.14.4 build 245** (`GET …/v3/projects/paper/versions/1.14.4/builds`
→ HTTP 200; `paper-1.14.4-245.jar`, sha256 `bd8ec5cdb2…`, 43,972,138 bytes —
byte-identical to the reference jar). run-paper downloaded it on the first
`checkIntegrationTest_1_14_4` ("Latest build for 1.14.4 is 245… Verified SHA256
hash") into `~/.gradle/caches/run-task-jars/paper/jars/1.14.4/245.jar`. No cache
seeding was needed; CI will fetch it the same way.

**What landed, file-by-file:**
- `support-matrix.json`: the `{ "version": "1.14.4", "jdk": 13, "platform":
  "paper", "suites": "full", "ci": "release", "bytecodeTier": 52 }` entry
  (version-sorted between 1.13.2 and 1.15.2); the `_comment`'s hole sentence
  rewritten — no longer a hole, keeping WHY it ever was (the old v61 jar could not
  load on any JVM 1.14.4 accepts).
- `docs/superpowers/research/2026-07-03-v1_14_R1-shapes.md`: authored (14
  per-branch verdicts, the straddle, loader/PDC/AbstractArrow/api-version
  oddities, live proof).
- `docs/superpowers/research/2026-07-02-legacy-fakeplayer-nms-shapes.md`: three
  divergence rows corrected in place (dated 2026-07-03) — PIM `(WorldServer)` and
  `Vec3D mot`/`setMot` begin at **1.14.4** (not 1.15.2); the async-join split
  begins at **1.15.2** (not 1.14).
- `FakePlayer.java`: the two stale async-join comments fixed (split is 1.15+, and
  1.14.4 stays synchronous — no code change, comment only).
- `core/build.gradle.kts`: the integration `doFirst` now prunes stale versioned
  plugin JARs (`Mental-*.jar` / `MentalTester-*.jar` / `OldCombatMechanics*.jar`)
  from the shared `run/<v>/plugins` dir. **This was necessary, not just
  future-proofing:** run-paper's own `deleteOldPlugins` only clears its
  `_RunServer_plugin.jar`-suffixed copies, and the Phase-2 `integration-matrix.sh`
  run had left verbatim `Mental-2.3.1-beta.jar` copies in every run dir — a Gradle
  run over those would double-load an ambiguous "Mental". The fix is live-proven:
  after `checkIntegrationTest_1_13_2`, `run/1.13.2/plugins` held only run-paper's
  suffixed jars, zero ambiguous-plugin errors. (Placed in `doFirst`, which Gradle
  runs before run-paper's `setupPlugins` in `exec()`, so this run's set is
  re-copied afterward — verified against run-task 3.0.2 via javap.)

**Item-4 finding — the "manifest-expectations table" is version-agnostic.**
`BootSuite.manifestPresentSince()` keys by manifest ENTRY name with a
present-since band, not by version — there are no per-version "1.13.2/1.15.2 rows"
to add a "1.14.4 row" beside, and `ServerEnvironment.parse()` recognizes any
numeric version generically. No manifest entry has a 1.14.x boundary, so 1.14.4
resolves identically to its neighbours by construction; the live
`manifestDegradesPerVersion` test (16/25 handles resolved, features disabled:
none) IS the per-version pin and PASSED. Per escalate-don't-weaken, no fake
version-keyed row was fabricated. **Every boot-report resolver cell matched the
plan's predictions exactly** (captured live from the 1.14.4 gate boot log):

> `platform profile — 16/25 handles resolved; sword-block=NONE
> attack-range=attribute-only max-damage=material hurt-protocol=legacy; features
> disabled: none` · `bytecode tier: downgraded (v52)` · `latency probe transport:
> TRANSACTION (rim=TransactionProbeRim)` · `currentTick=true folia=false
> modernSchedulers=false brigadierCommands=false registryAttributes=false
> knockbackEvent=false` · `rule-feature accessors — absorption=LivingEntity#getAbsorptionAmount(),
> potion-effect=LivingEntity#getPotionEffect(type), item-cooldown API present
> (1.11.2+), crit-posture[climbing=FEET_BLOCK in-water=FEET_BLOCK
> attack-charge=NMS_STRENGTH], hand-raised=HumanEntity#isHandRaised()` · PDC
> present (no NBT-fallback warning; golden-apples takes the legacy `recipeIterator`
> path).

**Gate evidence (verbatim, fresh nonces; all runs: tier line matched
`bytecodeTier`, zero LinkageError/NoClassDefFoundError/NoSuchMethodError/
VerifyError, zero Java-advisory lines in every legacy log):**
- `./gradlew build` — GREEN: unit tests, `:api:apiCompat` (japicmp), `:kernel:check`
  (Bukkit-free), and all four mega-jar verify gates (verifyDowngrade / verifyJdk8Api
  / verifyRelocation / verifyTesterIsolation).
- **1.14.4 stability (twice, sequential, fresh nonce each):**
  `[1.14.4] … (nonce=55eba8cb-d393-48d0-8578-73304870c65b)` then
  `[1.14.4] … (nonce=a9c5654c-c580-4c15-9c82-6125e6023208)` — both tier
  `downgraded (v52)`, 56/56.
- **Neighbours re-pass:** `[1.13.2] … (nonce=d666c58b-787f-4d6d-a715-46e770b38e10)`,
  `[1.15.2] … (nonce=60e39d93-e87d-41cc-a692-eb612d25456c)`.
- **`integrationTestMatrix` — BUILD SUCCESSFUL in 19m 54s, all 16 entries:**
  `[1.9.4] 7d70dba5-d4f4-4b95-af24-d508d461de7e` ·
  `[1.10.2] e6bb85a0-2277-47e2-b736-d1f763c324c5` ·
  `[1.11.2] 98c35b4e-e921-4b8b-bc4c-3937f037f39a` ·
  `[1.12.2] f37a7833-aca7-4aee-86da-4779a97bba9d` ·
  `[1.13.2] a05ef2a5-7bf0-482a-b8a6-adcc24f56440` ·
  `[1.14.4] 6068814e-96ef-457d-8698-63b04b9a7dd8` (tier 52 — the new entry) ·
  `[1.15.2] db25fc06-aba9-4b73-9a69-981a3ad80763` ·
  `[1.16.5] 9ea71b60-0bbd-4262-b5b0-2e3840e85425` ·
  `[1.17.1] f80e5b9f-0382-4064-ab56-539796f22edd` ·
  `[1.18.2] 49bd56ba-8f07-4786-8e39-85d8ad410413` ·
  `[1.19.4] 8747f4be-341f-48e5-949c-ec4678787868` ·
  `[1.20.6] c735c5eb-87e9-4fb5-a9fd-2a217b1050eb` ·
  `[1.21.4] a9f6643b-34df-451b-9549-da823ade3a66` ·
  `[1.21.11] c4a2ea90-038a-401e-99d6-e8875c647568` ·
  `[26.1.2] f576c028-59a8-47dd-8f3e-a28b4490e80e` ·
  `[26.1.2 Folia] 73c1650d-3e6a-4681-92ab-21235a66e810`.
- **`integrationTestOcm` — both v61:**
  `[1.17.1 +OCM] a9970893-d449-4d49-857c-8e3805d7fd77` ·
  `[26.1.2 +OCM] 08b840fc-4915-4657-aaaa-3208e09d8150`.
- **`scripts/integration-matrix.sh --no-ocm` — MATRIX PASSED (15 servers)** on a
  retry. The FIRST script run FAILED 1.9.4 only (55/56 — the combo-stacking ledger
  test missed by `best delta 0.035`, a sub-tick wall/game skew), while the
  authoritative sequential Gradle matrix passed 1.9.4 cleanly (`7d70dba5`) and
  Phase 3 touches no combat/ledger code. Adding 1.14.4 makes this the **15th**
  concurrent JVM on one machine — the extra load tipped 1.9.4 (oldest, most
  load-sensitive) into the documented concurrency flake; the retry cleared it with
  no assertion weakened. (CI runs the matrix sequentially / parallel-across-runners,
  never 15-on-one-machine, so this is a local-only load ceiling.)

**Note for Phase 4:** `support-matrix.json` now carries **1** `IgnoreJavaVersion`
mention — the historical clause in the rewritten 1.14.4 note explaining why it was
ever a hole (kept deliberately). README / release.yml / the matrix-gate &
live-server-testing skills still describe 1.14.4 as an "impossible hole" and cite
the 14/15-entry gate shape — all Phase 4's scope.
