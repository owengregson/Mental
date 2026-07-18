<!-- v2.9.0-beta -->
- Combat Test 8c is here: a faithful port of Mojang's experimental snapshot as
  thirteen opt-in modules — reworked shields (148° arc), charged attacks,
  per-weapon attack speeds, the split-vertical knockback, era i-frames, Cleaving
  and more — plus a matching `ct8c` knockback profile. All OFF by default and
  era-exact, decompiled from the real snapshot rather than the wiki.
- Rules bundles apply a whole ruleset in one click: `ct8c`, `signature` and
  `vanilla` ship in `bundles/` (drop your own in too), from the new Combat
  Presets screen or the API's RulesBundleAppliedEvent.
- The /mental menu is redesigned end-to-end: every family gets its own colour
  identity on woven glass chrome, and every screen speaks one click grammar —
  left-click toggles, right-click configures, Q resets a knob to your file.
- One preset gallery now holds both knockback profiles AND combat-effects
  tunes — legacy/modern is a tab, every tile previews its exact values, and
  one click applies server-wide.
- Nearly every scalar knob is now editable in-game: hit registration, latency
  compensation, the combo solver, fast pots, rods and projectiles, loadout
  rules, indicators, death effects and drop protection — applied atomically,
  no restart.
- On 1.9.4–1.12.2 the menus finally render in full colour: stained-pane
  backgrounds and ten previously-grey icons now resolve to their real
  pre-flattening glyphs.
- Under the hood: every CI workflow is now SHA-pinned with a runtime-only
  dependency graph and a published security policy, and the build tooling is
  refreshed (Shadow 9.6.0, JUnit 6.1.2).
