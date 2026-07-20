<!-- v2.11.0-beta -->
- Fixed: standing in lava, fire or a sweet-berry bush no longer spam-damages you.
  This affected servers running the Combat Test 8c i-frame rule, which briefly
  shrinks the invulnerability window so fast weapons re-hit sooner. That window is
  a single field every damage source shares, and the hand-back meant to restore it
  waited on the hurt counter — which a hazard re-arms every few ticks, so it never
  fired and the shortened window stayed pinned for as long as you stood in the
  hazard. The hand-back now keys off the hit that shrank the window, so a hazard
  cannot defer it and the window always returns after its own duration.
- The same fix covers the hit-timing override API added in 2.10.0-beta. It shares
  the window mechanism and had inherited the same problem, so a plugin holding a
  live override on a player standing in a hazard could pin that player's window
  too. Both paths now restore through one guard.
- Combat Test 8c i-frames now match 8c across the whole window. 8c makes the entire
  invulnerability window difference-damage: a stronger re-hit deals only the
  difference with no knockback, a same-or-weaker one deals nothing, and a fresh hit
  lands only once the window fully elapses. Mental previously reproduced only the
  first half, so a mid-window re-hit landed fresh — twice as permissive as 8c.
- Shields block projectiles again on the 8c rule. Arrows and other projectiles are
  absorbed in full inside 8c's 148° block cone (piercing arrows still get through)
  instead of falling back to vanilla's wider cone, and a crouch-blocking player is
  no longer left with no projectile protection at all.
- Getting hit no longer drops what you were holding up. The 8c hit-interrupt now
  cancels only eating and drinking, as 8c does — a raised shield, a drawn bow, a
  spyglass or a charging trident all survive the hit that most needed them.
- Bow accuracy under sustained fire follows 8c's real curve. Fatigue ramps smoothly
  between 3 and 10 seconds of held draw rather than snapping between two values,
  and a fresh full draw carries 8c's small baseline spread.
- Mushroom stew, rabbit stew and beetroot soup eat in one second on the 8c rule,
  matching 8c. Suspicious stew deliberately keeps its normal speed.
- Two 8c settings that did nothing now work: the charged-attack miss recovery and
  the charged reach bonus were both editable in the GUI but never read. Defaults
  are unchanged, so only servers that retuned them will notice.
- Known gap, unchanged this release: Mental's emulated crouch-block does not yet
  reproduce 8c's small self-knock toward the attacker. A natively raised shield
  already does. Composing that knock through the delivery core is genuine
  combat-core work, deliberately left to its own round rather than shipped
  half-applied.
