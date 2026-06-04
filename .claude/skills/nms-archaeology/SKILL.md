---
name: nms-archaeology
description: Use when server internals behave unexpectedly on a specific Minecraft version — a reflection lookup misses, a mechanic gates differently, a field seems renamed or relocated. Gives the javap-based procedure for reading the actual server code instead of guessing.
---

# NMS archaeology: read the server, don't guess

When a version behaves unexpectedly, the answer is in the server's own class
files, already on disk and Mojang-mapped (1.20.5+):

```bash
# The live server classes for a matrix version:
jar=run/<version>/versions/<version>/paper-<version>.jar

# 1. List a class's fields/methods (declared members ONLY — superclasses
#    need their own javap; a "missing" member is often on the parent):
javap -p -classpath "$jar" net.minecraft.server.level.ServerPlayer | grep -i <term>

# 2. Disassemble the gate you care about and read which fields it consults:
javap -c -p -classpath "$jar" net.minecraft.server.network.ServerGamePacketListenerImpl \
  | grep -A12 "public boolean hasClientLoaded"
```

Pre-1.20.5 runtimes are spigot-mapped — route names through
reflection-remapper instead of javap'ing obfuscated jars.

## Lessons this procedure earned

- Mechanics MOVE between classes across versions. Join protection lived in
  three places across our range (ServerPlayer field → Player entity fields →
  connection-listener fields). A grep on one class proves nothing about the
  mechanic; disassemble the consumer (`isInvulnerableTo`) to find the true
  backing state.
- Modern `placeNewPlayer` builds its OWN `ServerGamePacketListenerImpl` —
  state you set on a listener you constructed yourself is dead; read the live
  listener back off the player's `connection` field.
- Bytecode reading beats wiki/memory: `ifeq 62` chains tell you the exact
  boolean composition of a vanilla gate, including Paper-added config checks.
- Check ALL int/boolean fields when hunting (`grep -E "boolean|int "`), not
  just name guesses — Mojang renames freely between snapshots.
