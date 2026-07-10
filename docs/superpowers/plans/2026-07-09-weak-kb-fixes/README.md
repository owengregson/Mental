# Weak-knockback fix plans — 2026-07-09

Designed on Fable from the verified investigation
(`docs/superpowers/research/2026-07-09-weak-kb-close-range-pathway-findings.md`);
implemented on Opus in parallel worktree lanes.

| Plan | Lane | Scope |
| --- | --- | --- |
| [F1](F1-sprint-retro-clear.md) | L1 | SprintWire arrival-sequence clear (w-tap retro-clear) |
| [F4](F4-desk-supersede.md) | L2 (1st) | Desk supersede resolves newest, journals SUPERSEDED |
| [F2](F2-presend-outcome-pin.md) | L2 (2nd) | UNSENDABLE pre-send downgrades to PINNED |
| [F3](F3-valve-lifecycle.md) | L2 (3rd) | Valve arm confirmation, burst immunity, age-aware disarm |
| [F9](F9-journal-capture.md) | L2 (4th) | Per-hit journal capture (the discriminating measurement) |
| [F5F6](F5F6-registration-truth.md) | L3 | Enchant-in-view + registration-yaw stamp |
| [F7](F7-servo-saturation.md) | L4 | Servo saturation deadband (declines to era 1.0) |
| [F8](F8-config-minor-batch.md) | L5 | Byte-hash preset upgrades, latency knobs, ring seed, Folia guard |

Lane-internal order in L2 is a dependency contract (resolve truth table F4 → F2 row, F3 arm semantics, F9 record signature).
