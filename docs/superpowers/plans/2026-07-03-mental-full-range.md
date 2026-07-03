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
`docs/superpowers/research/2026-07-02-legacy-boot-viability.md`). Run the
legacy servers on their **native Java 8** instead and both problems dissolve —
provided the plugin jar can classload there. JVMDowngrader (JDG) makes it
load there: the shipped jar becomes a **Multi-Release mega-jar** whose base
tree is the same compilation downgraded to class v52 (Java 8) and whose
`META-INF/versions/17` tree is the original v61 classes, so:

- a 1.9.4–1.16.5 server on Java 8 reads the base v52 tree (Java 8 ignores
  `META-INF/versions/`),
- a 1.17.1+ server (Java 16+ mandatory) reads the original v61 tree via the
  runtime-versioned `JarFile` — **byte-identical modern behavior** to what has
  shipped and been matrix-verified since 2.3.0-beta,
- a legacy server still run on Java 17 (the 2.3.1-beta install style) keeps
  working either way — whichever tree its loader picks is the same code.

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
  proves modern Paper honors it. Fallback to flat (base-only) ONLY if Q1
  evidence shows modern loaders serving base — decided then, with the tier
  facts on the table.
- **D-2 JDG wiring: pinned CLI driven by Gradle `JavaExec`**, following the
  proven StarEnchants recipe (including the util-shading fix), with the CLI
  jar download sha256-pinned `stageOcmJar`-style. The official jvmdg Gradle
  plugin is the alternative if scout evidence shows it handles the util
  runtime correctly — executor may switch WITH proof, but the recipe below is
  the default. Version pinned: 1.3.6.
- **D-3 all seven-plus-one legacy entries run on `jdk: 8`** (not just the
  three that needed the flag): the matrix must prove the configuration admins
  actually run, and "legacy = Java 8+" is the new support claim. 1.9.4–1.12.2
  flip too.
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

Core + tester `build.gradle.kts`: stage the pinned JDG CLI (sha256-verified
download, cached); assemble the self-contained api jar (downgradeApi + downgraded
`util/*`); `downgrade` + `shade` the shadow jar (prefix
`me/vexmc/mental/lib/jvmdg`; tester: `me/vexmc/mental/tester/lib/jvmdg`);
assemble the MRJAR (base = downgraded jar in full; overlay = first-party
`.class` only; `Multi-Release: true`; deterministic zip); the mega jar is the
canonical artifact (H3) consumed by `build`, the run tasks, and the matrix
script. Boot-report tier line (H5/Q1) + tester-side tier assertion. Verify
tasks: `verifyDowngrade` (Q2), `verifyJdk8Api` (H1, tool ported from
StarEnchants' `Jdk8ApiGate.java`), `verifyRelocation` extended (H2, both
trees, `net/kyori` AND `xyz/wagyourtail`). **Gate:** `./gradlew build` green
with all verify tasks; ad-hoc boots with fresh nonces — 1.13.2 on Java 8
flagless FULL-suite PASS (base tree), 1.17.1 + 26.1.2 FULL-suite PASS with the
tier line captured (expected `modern`).

### Phase 2 — the native-JDK matrix flip (Opus)

`support-matrix.json`: every 1.9.4–1.16.5 entry → `"jdk": 8`, `serverFlags`
removed; `_comment` rewritten (the flag story is dead; the 1.14.4 hole note
survives until Phase 3). `scripts/integration-matrix.sh`: `java_for` handles
8 (foojay-provisioned path discovery or a helpful error naming the Gradle
provisioning route). **Gate:** full 15-entry `integrationTestMatrix` +
`integrationTestOcm`, fresh nonces, zero `IgnoreJavaVersion` anywhere in the
repo outside historical docs.

### Phase 3 — 1.14.4 at full tier (Opus)

`support-matrix.json` gains `{ "version": "1.14.4", "jdk": 8, "platform":
"paper", "suites": "full", "ci": "release" }` (D-6) and loses the hole note;
tester `FakePlayer` + friends gain the v1_14_R1 rows (scout shapes doc:
`docs/superpowers/research/2026-07-03-v1_14_R1-shapes.md`); any live probe
gaps fixed at the platform seam per house rules (boot-time probing, never
version conditionals). **Gate:** 1.14.4 full-suite PASS twice consecutively
(fresh nonce each), 1.13.2 + 1.15.2 re-PASS, then the full 16-entry matrix +
OCM.

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

_To be filled from the `scout-full-range` workflow before Phase 1 dispatches:
JDK-8 provisioning verdict, flagless native-Java boot table, JDG research
cross-check, v1_14_R1 shapes, plugin-loader MR-awareness per version, and the
end-to-end downgrade spike (1.13.2@Java8 full suite; first 1.14.4 contact)._

## Outcome log

_Per-phase outcomes, review findings, and gate evidence land here as the
campaign executes._
