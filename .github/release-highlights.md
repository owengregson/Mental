<!-- v2.10.0-beta -->
- New developer API — temporary hit-timing overrides. A companion plugin can now
  ask Mental to briefly speed up how fast ONE attacker re-hits a victim, priced
  inside Mental's own timing instead of fought around it. This is what makes
  abilities like StarEnchants' Berry Overdrive ("your strikes land twice as fast
  for a few seconds") behave correctly on a Mental server: the speed-up is scoped
  to that one attacker — a third party still waits the normal window — it composes
  with the CT8c i-frame profile, and it never writes the vanilla invulnerability
  counter, so the old i-frame-write hacks, spawn-invulnerability traps and
  third-party fairness guards are all gone.
- The service is published on Bukkit's ServicesManager (the registration is the
  capability a consumer probes) and ships with a live acceptance suite that runs
  on every server in the matrix: registration, per-attacker isolation, the ct8c
  `min(attackDelay,10) × factor` window composition, refresh-not-stack, and
  victim-death cleanup — all hard-pinned.
