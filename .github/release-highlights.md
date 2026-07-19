<!-- v2.9.1-beta -->
- The Cleaving enchant is now REAL on every modern server: the 2.9.0 registry
  probe could never actually resolve, so `mental:cleaving` silently degraded to
  level 0 everywhere. The injection chain is rebuilt and pinned against the
  live 1.21.4 AND 26.x server jars — including the year-scheme `Identifier`
  rename, so 26.x gets the real enchant too — and the test gate now hard-fails
  if the install ever stops landing.
- CT8c critical hits are un-inverted: the shipped guard skipped the ×1.5 on
  exactly the canonical non-sprint jump crit (only sprint crits applied). The
  flat ×1.5 now lands on every CT8c crit posture, live-pinned in the gate.
- CT8c i-frames no longer leak: the shaped hurt window (7–10 ticks melee, 0 for
  projectiles) is a persistent entity field, and one hit used to leave it
  shrunken forever — fire, cactus and drowning then re-applied every tick. The
  vanilla window is now restored the moment the shaped window drains, and
  disabling the feature restores every touched victim immediately.
- The `/mental` home is decluttered to five tiles: Combat Engine, Era Rules,
  Sustain & Potions, Effects & Loot, and Server & Diagnostics — three over two,
  flanked by just Combat Presets and Close. Each opens its category's sections,
  so every family screen is exactly two clicks away and nothing is lost.
