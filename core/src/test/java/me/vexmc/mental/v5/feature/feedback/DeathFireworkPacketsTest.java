package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.FireworkExplosion;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemFireworks;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTIntArray;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire contracts of the death-effects colored firework blast — the
 * REAL vanilla mechanism ({@code FireworkRocketEntity.explode()} is just
 * {@code broadcastEntityEvent(this, (byte) 17)}; the CLIENT renders the colored
 * blast from the rocket's fireworks item), shipped packet-only: spawn, the
 * fireworks-item metadata, entity status 17, destroy. No fake-player suite can
 * observe any of it (fakes carry no PacketEvents user), so the shapes pin here.
 *
 * <h2>The fireworks-item index band table</h2>
 * The rocket's fireworks-item metadata index is the count of Entity base
 * DataWatcher registrations before it, verified against the actual servers
 * (javap over the cached jars, per {@code nms-archaeology}): 1.9.x → 5;
 * 1.10 (no-gravity) → 6 through 1.13.2; 1.14 (pose) → 7 through 1.16.5;
 * 1.17 (ticks-frozen) → 8 onward (stable through 26.1.2). On 1.9–1.10 the
 * entry's serializer is {@code Optional<ItemStack>}; from 1.11 it is the plain
 * {@code ItemStack} — the flavor is part of the pinned contract.
 *
 * <p>A minimal stub API supplying a modern {@link ServerVersion} is installed
 * (the same posture as {@link DeathEffectsPacketsTest}) because the spawn
 * wrapper reads the server version off the API to resolve its packet id.</p>
 */
class DeathFireworkPacketsTest {

    /** The signature blast: white &f, yellow &e, gold &6. */
    private static final List<Integer> COLORS = List.of(0xFFFFFF, 0xFFFF55, 0xFFAA00);

    @BeforeAll
    static void installStubApi() {
        NettyManager nettyManager = new NettyManagerImpl();
        ServerManager serverManager = () -> ServerVersion.V_1_21; // getVersion() is the one abstract method
        PacketEvents.setAPI(new PacketEventsAPI<>() {
            @Override public void load() {}
            @Override public boolean isLoaded() { return true; }
            @Override public void init() {}
            @Override public boolean isInitialized() { return true; }
            @Override public void terminate() {}
            @Override public boolean isTerminated() { return false; }
            @Override public Object getPlugin() { return null; }
            @Override public ServerManager getServerManager() { return serverManager; }
            @Override public ProtocolManager getProtocolManager() { return null; }
            @Override public PlayerManager getPlayerManager() { return null; }
            @Override public NettyManager getNettyManager() { return nettyManager; }
            @Override public ChannelInjector getInjector() { return null; }
        });
    }

    // ---------------------------------------------------------------------
    // The javap-verified fireworks-item metadata index band table.
    // ---------------------------------------------------------------------

    @Test
    void fireworksItemIndexMatchesTheJavapVerifiedBands() {
        // Entity base defines counted in the actual server jars' static inits.
        assertEquals(5, DeathFirework.fireworksItemIndex(ClientVersion.V_1_9), "1.9.x: 5 Entity defines");
        assertEquals(6, DeathFirework.fireworksItemIndex(ClientVersion.V_1_10), "1.10: +no-gravity");
        assertEquals(6, DeathFirework.fireworksItemIndex(ClientVersion.V_1_11), "held through 1.13.2");
        assertEquals(6, DeathFirework.fireworksItemIndex(ClientVersion.V_1_13_2), "the last 6-band client");
        assertEquals(7, DeathFirework.fireworksItemIndex(ClientVersion.V_1_14), "1.14: +pose");
        assertEquals(7, DeathFirework.fireworksItemIndex(ClientVersion.V_1_16_4), "held through 1.16.5");
        assertEquals(8, DeathFirework.fireworksItemIndex(ClientVersion.V_1_17), "1.17: +ticks-frozen");
        assertEquals(8, DeathFirework.fireworksItemIndex(ClientVersion.V_1_21), "stable through 26.x");
    }

    // ---------------------------------------------------------------------
    // The spawn wrapper: a FIREWORK_ROCKET at the blast anchor, zero velocity.
    // ---------------------------------------------------------------------

    @Test
    void spawnCarriesAFireworkRocketAtTheBlastAnchor() {
        int rocketId = 373737;
        UUID uuid = UUID.randomUUID();
        Vector3d anchor = new Vector3d(12.5, 65.0, -33.25);
        WrapperPlayServerSpawnEntity spawn = DeathFirework.spawn(rocketId, uuid, anchor);

        assertEquals(rocketId, spawn.getEntityId(), "the client-only rocket id");
        assertSame(EntityTypes.FIREWORK_ROCKET, spawn.getEntityType(), "a client-only rocket render");
        assertEquals(anchor, spawn.getPosition(), "spawned at the frozen blast anchor");
        assertEquals(Optional.of(uuid), spawn.getUUID());
        assertEquals(0, spawn.getData(), "no object data — the item metadata carries the blast");
        assertEquals(Optional.of(new Vector3d(0.0, 0.0, 0.0)), spawn.getVelocity(),
                "an explicit zero velocity — the wrapper's empty-velocity fallback writes -1,-1,-1");
    }

    // ---------------------------------------------------------------------
    // The rocket item, modern flavor: the 1.20.5+ FIREWORKS component.
    // ---------------------------------------------------------------------

    @Test
    void modernRocketCarriesTheBurstComponent() {
        ItemStack rocket = DeathFirework.rocket(COLORS, true);
        assertSame(ItemTypes.FIREWORK_ROCKET, rocket.getType());
        assertEquals(1, rocket.getAmount());

        Optional<ItemFireworks> fireworks = rocket.getComponent(ComponentTypes.FIREWORKS);
        assertTrue(fireworks.isPresent(), "the FIREWORKS component carries the blast on 1.20.5+");
        assertEquals(0, fireworks.get().getFlightDuration(), "no fuse — status 17 detonates immediately");
        assertEquals(1, fireworks.get().getExplosions().size(), "exactly one explosion");
        FireworkExplosion explosion = fireworks.get().getExplosions().get(0);
        assertSame(FireworkExplosion.Shape.BURST, explosion.getShape());
        assertEquals(COLORS, explosion.getColors(), "white/yellow/gold, in config order");
        assertTrue(explosion.getFadeColors().isEmpty(), "no fade");
        assertTrue(!explosion.isHasTrail() && !explosion.isHasTwinkle(), "no trail, no twinkle");
    }

    // ---------------------------------------------------------------------
    // The rocket item, legacy flavor: the pre-1.20.5 Fireworks NBT.
    // ---------------------------------------------------------------------

    @Test
    void legacyRocketCarriesTheFireworksNbt() {
        ItemStack rocket = DeathFirework.rocket(COLORS, false);
        assertSame(ItemTypes.FIREWORK_ROCKET, rocket.getType());
        assertEquals(0, rocket.getLegacyData(), "pre-1.13 damage short must ship 0, not the -1 default");

        NBTCompound tag = rocket.getNBT();
        assertNotNull(tag, "the legacy flavor rides the item NBT");
        NBTCompound fireworks = tag.getCompoundTagOrNull("Fireworks");
        assertNotNull(fireworks, "Fireworks:{...}");
        NBTList<NBTCompound> explosions = fireworks.getCompoundListTagOrNull("Explosions");
        assertNotNull(explosions, "Explosions:[...]");
        assertEquals(1, explosions.size(), "exactly one explosion");
        NBTCompound explosion = explosions.getTag(0);
        assertEquals((byte) 4, explosion.getTagOfTypeOrThrow("Type", NBTByte.class).getAsByte(),
                "Type 4b is vanilla's BURST");
        int[] colors = explosion.getTagOfTypeOrThrow("Colors", NBTIntArray.class).getValue();
        assertEquals(COLORS, List.of(colors[0], colors[1], colors[2]), "Colors:[I;...] in config order");
        assertEquals(3, colors.length);
    }

    // ---------------------------------------------------------------------
    // The metadata entry: plain ItemStack from 1.11, Optional<ItemStack> below.
    // ---------------------------------------------------------------------

    @Test
    void fireworksEntryRidesThePlainItemStackFlavorFrom1_11() {
        ItemStack rocket = DeathFirework.rocket(COLORS, true);
        EntityData<?> entry = DeathFirework.fireworksEntry(8, rocket, false);
        assertEquals(8, entry.getIndex(), "the modern band index");
        assertSame(EntityDataTypes.ITEMSTACK, entry.getType(), "plain item stack from 1.11");
        assertSame(rocket, entry.getValue());
    }

    @Test
    void fireworksEntryRidesTheOptionalFlavorBelow1_11() {
        // The javap ground truth: 1.9/1.10 EntityFireworks.FIREWORK_ITEM is a
        // DataWatcherObject<Optional<ItemStack>> — the serializer slot PE maps
        // as optional_itemstack on those versions only.
        ItemStack rocket = DeathFirework.rocket(COLORS, false);
        EntityData<?> entry = DeathFirework.fireworksEntry(5, rocket, true);
        assertEquals(5, entry.getIndex(), "the 1.9.x band index");
        assertSame(EntityDataTypes.OPTIONAL_ITEMSTACK, entry.getType(), "Optional<ItemStack> below 1.11");
        assertEquals(Optional.of(rocket), entry.getValue());
    }

    // ---------------------------------------------------------------------
    // Detonate and destroy.
    // ---------------------------------------------------------------------

    @Test
    void detonateIsEntityStatus17() {
        WrapperPlayServerEntityStatus status = DeathFirework.detonate(373737);
        assertEquals(373737, status.getEntityId());
        assertEquals(17, status.getStatus(),
                "vanilla FireworkRocketEntity.explode() broadcasts entity event 17");
    }

    @Test
    void destroyTargetsTheRocketId() {
        WrapperPlayServerDestroyEntities destroy = DeathFirework.destroy(373737);
        assertEquals(1, destroy.getEntityIds().length);
        assertEquals(373737, destroy.getEntityIds()[0]);
    }
}
