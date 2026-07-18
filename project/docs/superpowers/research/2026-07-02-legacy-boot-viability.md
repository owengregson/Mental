# Legacy Paper boot viability on modern JVMs

Empirical probe run on **2026-07-02**, one machine (Apple Silicon, macOS
Darwin 25.3.0). Question: can the terminal patch of each legacy Minecraft major
(1.9.4 → 1.16.5) boot the newest Paper build on a modern JVM (Java 17 / 21)?
This decides which versions Mental — a **Java-17-classfile** plugin — can
support. No plugins were installed; every server ran vanilla-flat, offline, on
shared port 25599, booted strictly one at a time.

## JVMs used (exact binaries)

| Label | Path | Version | Classfile major |
| --- | --- | --- | --- |
| **java17** (primary) | `/Users/owengregson/Library/Java/JavaVirtualMachines/jdk-17.0.19+10/Contents/Home/bin/java` | Eclipse Temurin 17.0.19+10 | 61 |
| **java25** (fallback) | `/opt/homebrew/opt/openjdk@25/bin/java` | Homebrew OpenJDK 25.0.2 | 69 |

> **Java 21 was NOT installed on this machine.** `/usr/libexec/java_home -V`
> lists only Temurin 17.0.19 and Oracle GraalVM 25.0.2; `~/.gradle/jdks/` does
> not exist; Homebrew carries only `openjdk`/`openjdk@25` (both 25.0.2). The
> ladder's "Java 21" rung (L4) was therefore run on **Java 25** as the
> newer-than-17 substitute, clearly labelled `java25` below. This does not
> change any verdict: the only failure (1.14.4) is an unconditional guard that
> rejects everything above Java 13, so Java 21 would fail it identically, and
> every passing version already passes on Java 17.

## Boot ladder

L1 = `<java> -Xmx1G -jar paper.jar nogui`
L2 = L1 + `-DPaper.IgnoreJavaVersion=true`
L3 = L2 + the six `--add-opens java.base/… =ALL-UNNAMED` flags (before `-jar`)
L4 = repeat L1–L3 on the newer JVM (here java25). Stop at first success.
Success = a `Done (` line within 180 s.

## Verdict table

| Version | Paper build | JDK | Minimal flag set that boots | Boots? | Time-to-Done | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 1.9.4 | 775 | java17 | **none** (L1) | ✅ YES | 0.775 s | clean boot, graceful `stop` |
| 1.10.2 | 918 | java17 | **none** (L1) | ✅ YES | 0.785 s | clean |
| 1.11.2 | 1106 | java17 | **none** (L1) | ✅ YES | 0.810 s | clean |
| 1.12.2 | 1620 | java17 | **none** (L1) | ✅ YES | 0.997 s | clean |
| 1.13.2 | 657 | java17 | `-DPaper.IgnoreJavaVersion=true` (L2) | ✅ YES | 1.179 s | L1 aborts on soft guard "Only up to Java 12"; property bypass works |
| 1.14.4 | 245 | java17 **and** java25 | **NONE — no flag boots it** | ❌ NO | — | HARD guard "Only up to Java 13 is supported"; patched jar carries **no** `IgnoreJavaVersion` escape → unbypassable |
| 1.15.2 | 393 | java17 | `-DPaper.IgnoreJavaVersion=true` (L2) | ✅ YES | 3.699 s | L1 aborts on soft guard "Only up to Java 14"; property bypass works |
| 1.16.5 | 794 | java17 | `-DPaper.IgnoreJavaVersion=true` (L2) | ✅ YES | 1.133 s | L1 aborts on soft guard "Only up to Java 16"; property bypass works |

**7 of 8 boot on Java 17. Only 1.14.4 cannot boot on any modern JVM.**

## The single most important finding

**1.14.4 is the lone hard wall, and it is not a paperclip/patching problem — it
is the server's own Java-version guard with no escape hatch.** Paperclip
patching *succeeded* on Java 17 for every version (the "Downloading vanilla
jar…/Patching vanilla jar…" stage completed and left a `patched_<ver>.jar` in
`cache/` in all 8 cases). The 1.13.2 / 1.15.2 / 1.16.5 L1 aborts are a *soft*
post-patch guard that the standard `-DPaper.IgnoreJavaVersion=true` bypasses.
The 1.14.4 (build 245) patched main class contains **only** the guard strings
`Unsupported Java detected (` and `). Only up to Java 13 is supported.` and
**no** `IgnoreJavaVersion` (nor any `ignorejava*`) property reference — so the
flag is read by nothing and the JVM is rejected unconditionally. Proven by
grepping the patched jars:

- `1.13.2/cache/patched_1.13.2.jar` → contains `Paper.IgnoreJavaVersion` (bypassable)
- `1.15.2` / `1.16.5` patched jars → contain `Paper.IgnoreJavaVersion` (bypassable)
- `1.14.4/cache/patched_1.14.4.jar` → contains the guard message but **not** the property (unbypassable)

Confirmed empirically: 1.14.4 rejected all six rungs — L1/L2/L3 on java17 and
L1/L2/L3 on java25 — with the identical guard, printing major `61.0` under
java17 and `69.0` under java25.

## Exact failing error lines

- 1.14.4 (java17, L1/L2/L3): `Unsupported Java detected (61.0). Only up to Java 13 is supported.`
- 1.14.4 (java25, L1/L2/L3): `Unsupported Java detected (69.0). Only up to Java 13 is supported.`
- 1.13.2 (java17, L1 only; bypassed at L2): `Unsupported Java detected (61.0). Only up to Java 12 is supported.`
- 1.15.2 (java17, L1 only; bypassed at L2): `Unsupported Java detected (61.0). Only up to Java 14 is supported.`
- 1.16.5 (java17, L1 only; bypassed at L2): `Unsupported Java detected (61.0). Only up to Java 16 is supported.`

## Paperclip-vs-server distinction

**Zero paperclip/patching failures** on modern JVMs. Every failure observed was
the *server's* post-patch Java-version guard (fires after `Patching vanilla
jar…`, before `Loading libraries`). For 1.9.4–1.12.2 there is no guard at all
(they boot bare on Java 17); 1.13.2/1.15.2/1.16.5 have a soft, property-gated
guard; 1.14.4 has a hard, ungated guard.

## Combat/plugin-relevant warnings seen

None specific to combat surfaced in the boot logs. General notes across the
booting versions:

- Netty warning on all versions: *"Your platform does not provide complete
  low-level API for accessing direct buffers reliably … heap buffer will always
  be preferred"* — Apple-Silicon/direct-buffer notice, benign for a boot probe
  but worth noting for any packet-layer (netty fast-path) work on this host.
- Offline-mode warnings as expected (probe config).
- All bootable servers reached `Done (` and shut down cleanly via `stop`.

## Implication for Mental (Java-17 classfile)

- **Supportable on Java 17:** 1.9.4, 1.10.2, 1.11.2, 1.12.2 (no flags), and
  1.13.2, 1.15.2, 1.16.5 (with `-DPaper.IgnoreJavaVersion=true`). All run on the
  same Java 17 that loads Mental's Java-17 classfiles.
- **Not supportable via a modern JVM at the terminal build:** 1.14.4 build 245
  refuses every JVM above Java 13. Reaching 1.14.4 would require an actual
  Java 8–13 runtime (out of scope of "modern JVM"), or an earlier/patched 1.14.4
  build that carries the `IgnoreJavaVersion` escape (unverified here).

## Reuse / artifact locations

All downloads and patched caches were left in place under
`/Users/owengregson/Documents/StrikeSync/run/legacy-probe/<version>/` (gitignored
runtime area):

- `paper.jar` (the exact build above) per version — re-usable without re-download.
- `cache/patched_<version>.jar` per version — vanilla already downloaded + patched.
- `eula.txt`, `server.properties` (port 25599, offline, flat, view-distance 4),
  generated worlds, and `boot-attempt-<n>.log` (full per-attempt logs) per version.
- Probe driver script: session scratchpad `probe.sh` (not in repo).

Paper builds resolved via the **fill.papermc.io v3** API
(`https://fill.papermc.io/v3/projects/paper/versions/<V>/builds`, newest-first);
the legacy **api.papermc.io v2** endpoint is **sunset** (`{"ok":false,
"error":"sunset"}`) and cannot be used.
