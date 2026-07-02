# API binary-compatibility baseline

`api-2.2.2.jar` is the built output of the `:api` module at release tag
**`v2.2.2`** (commit `1c24b5f`, "chore: release 2.2.2 — era-faithful
armour/weapon stats"). It is the frozen reference the `apiCompat` gate
(`api/build.gradle.kts`) compares every build's `:api` jar against.

## Policy

The public API surface (`me.vexmc.mental.api.*`) may only **grow**. `apiCompat`
runs japicmp with `--error-on-binary-incompatibility`, so the build **fails**
on any binary-incompatible change — a removed class, a removed or re-signed
method. Additive changes are binary compatible and pass: for example the
`MentalApi.apiVersion()` **default** method added after v2.2.2 shows up as
`+++ NEW METHOD` and does not trip the gate. `apiCompat` is wired into `check`,
so `./gradlew build` enforces it.

## Provenance / how it was produced

```bash
git worktree add /tmp/api-baseline-wt v2.2.2
( cd /tmp/api-baseline-wt && ./gradlew :api:jar )
cp /tmp/api-baseline-wt/api/build/libs/api-2.2.2.jar gradle/api-baseline/api-2.2.2.jar
git worktree remove /tmp/api-baseline-wt --force
```

- Baseline sha256: `99c4a0fd775eabbe59147e54ceb3fb31652c3297fd8fe10df9dd89784494ab24`
- Tool: japicmp `0.23.1` (`jar-with-dependencies`), run via JavaExec.

## When to bump the baseline

When a new release intentionally ships an additive API surface and you want
that surface to become the new floor, rebuild this jar from the new release
tag (same steps) and update the version referenced in `api/build.gradle.kts`
and this README. Never rebuild it to "make the gate pass" for a removal — that
would defeat the gate.
