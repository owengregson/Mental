# Kill rewards — the kill title and drop protection (2.7.0)

Two opt-in extras that fire on a **PvP kill** (a player killed by another
player; never a mob or environmental death, never a self-kill). Both are
zero-touch when off, so a server that wants neither behaves exactly as before.

## Kill title (a death effect)

The kill title is an **effect inside the `death-effects` module** (the Combat
Effects / FEEDBACK family), not a separate feature. When Death Effects is
enabled and the selected preset carries kill-title text, the killer of an enemy
player is flashed a configurable title + subtitle. The signature preset ships:

```yaml
death-effects:
  kill-title:
    title: "&c&lKILLED:&r &f{NAME}&r"
    subtitle: "&c➥&r &7This player's drops are protected for &r&f&n15s&r&7!"
    fade-in: 5    # client title ticks (20 = 1s)
    stay: 40
    fade-out: 10
```

- **Recipient:** the killer only (an audience of one, distinct from the death
  cosmetics' 48-block audience — so it fires even with no bystander nearby).
- **Placeholders:** Mental's own tokens are substituted first —
  `{NAME}`/`{VICTIM}` (the slain player), `{KILLER}` (you), `{PROTECT_SECONDS}`
  (the configured Drop Protection window in whole seconds, blank when that
  feature is off). Then PlaceholderAPI's `%placeholders%` if it is installed
  (a soft-depend, resolved reflectively — absent, the raw text shows). Then the
  `&` colour codes are translated.
- **Wire:** version-gated PacketEvents title wrappers — three
  `Set-Title-{Times,Text,Subtitle}` packets on 1.17+, the combined
  `WrapperPlayServerTitle` below. Resolved once at assemble.
- **No-op default:** empty title *and* subtitle sends nothing, so a text-free
  `death-effects` section stays silent even with the module on.

Editable in-game on the **Death Effects** screen (title, subtitle, the three
timings, and the cosmetic lightning); the sound and firework lists stay in the
preset file.

## Drop protection (a LOOT feature)

`modules.drop-protection` (default **OFF**). When a player kills another player,
the victim's dropped items are **locked to the killer** for a window and glow to
the killer alone. Tuned in `drop-protection.yml`:

```yaml
drop-protection:
  seconds: 15        # killer-only window, [1, 3600]
  glow-color: GOLD   # GOLD or YELLOW
```

- **Capture:** at `PlayerDeathEvent` the drops are cleared from the event and
  re-dropped by Mental so the item entities can be tracked. `keepInventory` or
  a non-PvP death is left entirely to vanilla.
- **Pickup gate:** only the killer may pick the drops up during the window — the
  victim and everyone else are blocked, then the loot is free-for-all. Uses
  `EntityPickupItemEvent` on 1.12+ and `PlayerPickupItemEvent` below it (a
  version-split so the missing type is never referenced on the wrong server).
- **Glow:** the killer's client alone receives the "glowing" entity-metadata bit
  plus a named-team colour packet, so everyone else sees a plain drop. The glow
  outline colour is limited to the 16 named team colours (a client limitation) —
  GOLD is the shipped tint, YELLOW the alternative.
- **Expiry:** a single global sweep de-glows and frees elapsed entries. On
  disable everything is torn down as a unit (zero-touch).

Editable in-game on the **Loot Protection** screen (the window and the glow
colour); the pickup rule is fixed to killer-only.

## Notes

Neither feature claims 1.7/1.8 authenticity — they are explicit opt-in extras
that never touch the knockback / hit / technique contract, so they satisfy the
era-accuracy anti-feature gate by being default-OFF and zero-touch when off.
