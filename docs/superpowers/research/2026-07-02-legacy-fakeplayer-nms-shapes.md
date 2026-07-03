# Legacy FakePlayer NMS shapes — javap-verified, all 7 revisions

Source: javap against the real terminal-patch server jars under
`run/legacy-probe/<version>/cache/patched_<version>.jar` (Phase 4 scout,
2026-07-02). Consumed by Phase 5 (tester FakePlayer legacy branch). Packages:
1.9.4=`v1_9_R2`, 1.10.2=`v1_10_R1`, 1.11.2=`v1_11_R1`, 1.12.2=`v1_12_R1`,
1.13.2=`v1_13_R2`, 1.15.2=`v1_15_R1`, 1.16.5=`v1_16_R3`. `<v>` = the row's
package token; all NMS classes live in `net.minecraft.server.<v>`.

## Identical across all 7 revisions

- **EntityPlayer ctor** (the single public one, 4 params):
  `public EntityPlayer(MinecraftServer, WorldServer, com.mojang.authlib.GameProfile, PlayerInteractManager)`
- **PlayerConnection ctor**: `public PlayerConnection(MinecraftServer, NetworkManager, EntityPlayer)`
- **NetworkManager ctor**: `public NetworkManager(EnumProtocolDirection)`
  (direction field `private final EnumProtocolDirection h`)
- **PlayerList register** (login/add-player entrypoint):
  `public void a(NetworkManager, EntityPlayer)`
- **EntityHuman NMS melee**: `public void attack(net.minecraft.server.<v>.Entity)`
  — the spigot name is literally `attack` on every revision.
- **Bukkit `HumanEntity.attack(Entity)` is ABSENT on ALL 7** (1.15.2/1.16.5
  add only `float getAttackCooldown()`). Tester melee MUST go through the NMS
  `attack` above on every legacy version.

## Divergence axes

**PlayerInteractManager ctor** — split at 1.14:
`(World)` on 1.9.4–1.13.2; `(WorldServer)` on 1.15.2/1.16.5.

**Entity motion**:
`public double motX/motY/motZ` on 1.9.4–1.13.2;
`private Vec3D mot` on 1.15.2/1.16.5 (use `getMot`/`setMot`, or setAccessible).

**Tick entrypoint** (the Entity per-tick override; the inner `playerTick()`
exists only from 1.11.2):

| Version | Entity tick override | playerTick() |
|---|---|---|
| 1.9.4 / 1.10.2 | `m()` | absent |
| 1.11.2 | `A_()` | present |
| 1.12.2 | `B_()` | present |
| 1.13.2 / 1.15.2 / 1.16.5 | `tick()` | present |

**PlayerList join callback**:
`onPlayerJoin(EntityPlayer, String)` on 1.9.4–1.13.2 →
`onPlayerJoinFinish(EntityPlayer, WorldServer, String)` on 1.15.2/1.16.5
(async/chunk-gated there).

**MinecraftServer → WorldServer access**:
- 1.9.4–1.12.2: `getWorldServer(int)`; fields `WorldServer[] worldServer`,
  `List<WorldServer> worlds`
- 1.13.2/1.15.2: `getWorldServer(DimensionManager)`; `Iterable<WorldServer> getWorlds()`
- 1.16.5: `getWorldServer(ResourceKey<World>)`; `getWorlds()`; overworld
  shortcut `WorldServer E()`

**CraftServer**:
`getHandle()` → `DedicatedPlayerList` on all 7.
`getServer()` → `MinecraftServer` (1.9.4–1.13.2) vs `DedicatedServer`
(1.15.2/1.16.5 — still MinecraftServer-assignable).
Ctor `CraftServer(MinecraftServer, PlayerList)` exists 1.9.4–1.13.2 only.
