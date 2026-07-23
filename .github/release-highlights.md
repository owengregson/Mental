<!-- v2.12.0-beta -->
- Damage indicators now appear for every hit, not just player-versus-player melee.
  Hitting a mob shows its damage, a mob hitting someone shows theirs, and fall
  damage, lava, fire and drowning all show a number too. Previously only one case
  in the whole game drew an indicator: a player meleeing another player.
- Ranged hits show damage numbers again. A bow, trident or snowball hit reports the
  arrow as the attacker rather than the player who fired it, so every ranged hit
  failed the old player-attacker check and drew nothing at all — even in PvP. Shots
  are now credited to the shooter, who sees the number wherever they are standing.
- You no longer see damage numbers for damage happening to YOU. Everyone near a
  victim sees the number except the victim themself, so your own screen stays clear
  in a fight while you still see everything you deal.
- Mobs now show healing numbers too, on the same rule as damage. Heals are detected
  by watching health rather than by listening for a heal event, so heals applied
  directly by other plugins are caught the same way they already are for players.
- Hit sounds stay combat-only, deliberately. Fall damage, lava, drowning and
  freezing keep vanilla's own hurt sounds, so you can still hear WHY you are taking
  damage rather than getting one combat chord for everything.
- The low-health warning sound now plays on every damage cause, including
  environmental. It is a warning about your health rather than about the blow, so
  burning or drowning into the danger zone sounds the same alarm a hit does.
