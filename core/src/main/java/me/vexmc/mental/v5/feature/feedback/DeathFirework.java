package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
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
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The death-effects colored firework blast, built the way vanilla builds it.
 * The {@code minecraft:firework} PARTICLE carries no color field on the wire
 * (a {@code SimpleParticleType} — verified via javap on the 26.1.2 server), so
 * a colored blast cannot ride the particle packet at all. Vanilla's own route
 * ({@code FireworkRocketEntity.explode()} in the 1.21.11 decompile) is just
 * {@code level.broadcastEntityEvent(this, (byte) 17)} — the CLIENT renders the
 * colored blast from the rocket's fireworks ITEM data. So this ships a
 * packet-only rocket: spawn, the fireworks-item metadata, entity status 17,
 * destroy — one client frame, no server entity, no damage, no block change.
 *
 * <h2>The fireworks-item index band table</h2>
 * The rocket's fireworks-item metadata index is the count of Entity base
 * DataWatcher registrations before it (the firework's superclass chain —
 * IProjectile/Projectile — defines nothing, and FIREWORK_ITEM is that class's
 * first define in every version). Verified against the actual servers (javap
 * over the cached jars, per {@code nms-archaeology}): 1.9.x → 5; 1.10
 * (no-gravity) → 6 through 1.13.2; 1.14 (pose) → 7 through 1.16.5; 1.17
 * (ticks-frozen) → 8 onward, stable through 26.1.2. Keyed on the CLIENT
 * version — on a natively-joined client that IS the wire protocol (the same
 * posture as {@link IndicatorStandPackets#standFlagsIndex}).
 *
 * <h2>The item-data flavor, decided once at construction</h2>
 * On 1.20.5+ the item's blast rides the {@code minecraft:fireworks} component;
 * below, the classic {@code Fireworks:{Explosions:[{Type:4b,Colors:[I;...]}]}}
 * NBT — PacketEvents serializes whichever the server's protocol speaks, so the
 * rocket is built ONCE here in the matching flavor and reused for every death.
 * One more javap fact rides the send: on 1.9/1.10 the metadata slot is a
 * {@code DataWatcherObject<Optional<ItemStack>>} (PE's {@code optional_itemstack}
 * serializer, which has no id on those versions under the plain type), so the
 * entry flavor forks at 1.11 alongside the index band.
 *
 * <h2>The inherent blast pop</h2>
 * The client SELF-PLAYS the firework blast sound when it renders the explosion
 * particles — that is vanilla's firework, not a Mental sound send. The
 * configured sound list stays the only server-sent audio.
 */
final class DeathFirework {

    /** Vanilla's BURST explosion type byte in the legacy {@code Fireworks} NBT. */
    private static final byte SHAPE_BURST = 4;

    /** Vanilla's "firework exploded" entity event — {@code FireworkRocketEntity.explode()}. */
    private static final int STATUS_DETONATE = 17;

    /** The prebuilt rocket in this server's item-data flavor; null when no colors are configured. */
    private final ItemStack rocket;

    /** Server below 1.11: the fireworks-item slot serializes as {@code Optional<ItemStack>}. */
    private final boolean optionalItemFlavor;

    /** Server 1.19.4+: the four packets land in one client frame between bundle delimiters. */
    private final boolean bundleSupported;

    DeathFirework(List<Integer> colors, ServerVersion server) {
        this.rocket = colors.isEmpty()
                ? null
                : rocket(colors, server.isNewerThanOrEquals(ServerVersion.V_1_20_5));
        this.optionalItemFlavor = server.isOlderThan(ServerVersion.V_1_11);
        this.bundleSupported = server.isNewerThanOrEquals(ServerVersion.V_1_19_4);
    }

    /** Whether a blast is configured at all — false keeps the module's zero-cost no-op path. */
    boolean hasBlast() {
        return rocket != null;
    }

    /**
     * Ships one blast to one viewer: spawn → fireworks-item metadata → status 17
     * → destroy, silent writes inside bundle delimiters on 1.19.4+ so the rocket
     * never renders a frame as an undetonated entity. No flush — the caller owns
     * the per-viewer flush (the death-effects emit loop). Catch-Throwable because
     * a viewer mid-(re)configuration throws inside PacketEvents, and a missed
     * cosmetic beats a surfaced pipeline exception (the sibling classes' posture).
     */
    void send(User user, int entityId, Vector3d at) {
        try {
            List<EntityData<?>> data = List.of(fireworksEntry(
                    fireworksItemIndex(user.getClientVersion()), rocket, optionalItemFlavor));
            if (bundleSupported) {
                user.writePacketSilently(new WrapperPlayServerBundle());
            }
            user.writePacketSilently(spawn(entityId, UUID.randomUUID(), at));
            user.writePacketSilently(new WrapperPlayServerEntityMetadata(entityId, data));
            user.writePacketSilently(detonate(entityId));
            user.writePacketSilently(destroy(entityId));
            if (bundleSupported) {
                user.writePacketSilently(new WrapperPlayServerBundle());
            }
        } catch (Throwable reconfiguring) {
            // A missed cosmetic beats a surfaced exception on the send path.
        }
    }

    // ------------------------------------------------------------------------
    // Packet shapes (package-private statics — pinned by DeathFireworkPacketsTest).
    // ------------------------------------------------------------------------

    /** The fireworks-item metadata index for this client (see the band table above). */
    static int fireworksItemIndex(ClientVersion clientVersion) {
        if (clientVersion.isNewerThanOrEquals(ClientVersion.V_1_17)) {
            return 8;
        }
        if (clientVersion.isNewerThanOrEquals(ClientVersion.V_1_14)) {
            return 7;
        }
        if (clientVersion.isNewerThanOrEquals(ClientVersion.V_1_10)) {
            return 6;
        }
        return 5;
    }

    /**
     * The rocket spawn: a client-only render at the blast anchor with no object
     * data. The velocity is an EXPLICIT zero — the unified wrapper's
     * empty-velocity fallback writes {@code -1,-1,-1} on the always-written
     * 1.9+ velocity shorts. Below server 1.14 the same wrapper encodes the
     * legacy spawn-object packet (fireworks were object entities pre-1.19),
     * writing FIREWORK_ROCKET's legacy object id byte — verified in the shaded
     * PacketEvents wrapper and its {@code legacy_spawn_entity_type} mapping.
     */
    static WrapperPlayServerSpawnEntity spawn(int entityId, UUID uuid, Vector3d at) {
        return new WrapperPlayServerSpawnEntity(
                entityId, Optional.of(uuid), EntityTypes.FIREWORK_ROCKET, at,
                0.0f, 0.0f, 0.0f, 0, Optional.of(new Vector3d(0.0, 0.0, 0.0)));
    }

    /** The fireworks-item metadata entry — plain ItemStack from 1.11, Optional below. */
    static EntityData<?> fireworksEntry(int index, ItemStack rocket, boolean optionalFlavor) {
        return optionalFlavor
                ? new EntityData<>(index, EntityDataTypes.OPTIONAL_ITEMSTACK, Optional.of(rocket))
                : new EntityData<>(index, EntityDataTypes.ITEMSTACK, rocket);
    }

    /** Vanilla's blast trigger: entity event {@value #STATUS_DETONATE} on the rocket. */
    static WrapperPlayServerEntityStatus detonate(int entityId) {
        return new WrapperPlayServerEntityStatus(entityId, STATUS_DETONATE);
    }

    /** The immediate un-render — vanilla discards the rocket the same tick it detonates. */
    static WrapperPlayServerDestroyEntities destroy(int entityId) {
        return new WrapperPlayServerDestroyEntities(entityId);
    }

    /**
     * One BURST explosion in the configured colors — no fade, no trail, no
     * twinkle, zero flight (status 17 detonates immediately; the fuse never
     * runs). {@code modernComponents} picks the 1.20.5+ {@code fireworks}
     * component; otherwise the classic NBT, with the pre-1.13 damage short
     * pinned to 0 so the builder's -1 default never rides the wire.
     */
    static ItemStack rocket(List<Integer> colors, boolean modernComponents) {
        if (modernComponents) {
            FireworkExplosion explosion = new FireworkExplosion(
                    FireworkExplosion.Shape.BURST, List.copyOf(colors), List.of(), false, false);
            return ItemStack.builder()
                    .type(ItemTypes.FIREWORK_ROCKET)
                    .amount(1)
                    .component(ComponentTypes.FIREWORKS, new ItemFireworks(0, List.of(explosion)))
                    .build();
        }
        int[] wire = new int[colors.size()];
        for (int i = 0; i < wire.length; i++) {
            wire[i] = colors.get(i);
        }
        NBTCompound explosion = new NBTCompound();
        explosion.setTag("Type", new NBTByte(SHAPE_BURST));
        explosion.setTag("Colors", new NBTIntArray(wire));
        NBTList<NBTCompound> explosions = NBTList.createCompoundList();
        explosions.addTag(explosion);
        NBTCompound fireworks = new NBTCompound();
        fireworks.setTag("Explosions", explosions);
        NBTCompound tag = new NBTCompound();
        tag.setTag("Fireworks", fireworks);
        return ItemStack.builder()
                .type(ItemTypes.FIREWORK_ROCKET)
                .amount(1)
                .nbt(tag)
                .legacyData(0)
                .build();
    }
}
