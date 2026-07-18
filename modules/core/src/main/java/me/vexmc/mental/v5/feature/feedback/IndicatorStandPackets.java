package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.vexmc.mental.kernel.fx.IndicatorBallistics;
import me.vexmc.mental.kernel.fx.IndicatorPlacement;
import me.vexmc.mental.v5.text.TextPort;
import net.kyori.adventure.text.Component;

/**
 * Pure wrapper building for the {@code damage-indicators} stand — no sends, no
 * version reads (both are decided by the callers). The indicator is an
 * <b>invisible marker armor stand</b> whose custom name carries the damage text:
 * marker (0x10) because a full-size invisible stand would put a real hitbox in
 * front of the victim and intercept the attacker's crosshair mid-combo, and
 * small (0x01) so even the marker's residual client geometry stays negligible.
 *
 * <h2>The status-byte band table</h2>
 * The armor-stand status byte's metadata index is the count of Entity +
 * EntityLiving DataWatcher registrations before it, which grew over the
 * versions. The bands were verified against the actual servers (javap over the
 * cached jars, per {@code nms-archaeology}): 1.9.x → 10; 1.10 (no-gravity) →
 * 11 through 1.13.2; 1.14 (pose, bed) → 13; 1.15 (bee stingers) → 14 through
 * 1.16.5; 1.17 (ticks-frozen) → 15 onward. Keyed on the CLIENT version — on a
 * natively-joined client that IS the wire protocol.
 *
 * <h2>Name encoding</h2>
 * The custom name at index 2 is a chat component from 1.13 (PE's
 * {@code OPTIONAL_ADV_COMPONENT}) and a plain string before it — the string
 * path serializes through {@link TextPort} so the §-encoding matches every
 * other legacy text sink in the plugin.
 */
final class IndicatorStandPackets {

    /**
     * A marker stand's nameplate renders ~this far above the entity position, so
     * the DRIVEN stand rides one offset below the text the kernel math steers.
     * The one place to retune if the text sits visibly high or low.
     */
    static final double NAMEPLATE_OFFSET = 0.5;

    /** Entity flags: invisible. The name still renders — only the body hides. */
    private static final byte ENTITY_INVISIBLE = (byte) 0x20;

    /** Armor-stand status byte: marker (0x10, no hitbox) | small (0x01). */
    private static final byte STAND_MARKER_SMALL = (byte) 0x11;

    private IndicatorStandPackets() {}

    /** The armor-stand status byte's metadata index for this client (see the band table above). */
    static int standFlagsIndex(ClientVersion clientVersion) {
        if (clientVersion.isNewerThanOrEquals(ClientVersion.V_1_17)) {
            return 15;
        }
        if (clientVersion.isNewerThanOrEquals(ClientVersion.V_1_15)) {
            return 14;
        }
        if (clientVersion.isNewerThanOrEquals(ClientVersion.V_1_14)) {
            return 13;
        }
        if (clientVersion.isNewerThanOrEquals(ClientVersion.V_1_10)) {
            return 11;
        }
        return 10;
    }

    /**
     * The spawn + metadata pair for one indicator stand. {@code modernSpawn}
     * (server 1.19+, where SPAWN_LIVING_ENTITY was folded into the unified
     * spawn packet) picks the wrapper; the metadata always ships as its own
     * packet, which every version accepts.
     */
    static List<PacketWrapper<?>> spawn(
            int entityId, UUID uuid, IndicatorPlacement.Spawn spawn,
            Component name, ClientVersion clientVersion, boolean modernSpawn) {
        Vector3d position = new Vector3d(spawn.x(), spawn.y() - NAMEPLATE_OFFSET, spawn.z());
        PacketWrapper<?> spawnPacket = modernSpawn
                ? new WrapperPlayServerSpawnEntity(
                        entityId, Optional.of(uuid), EntityTypes.ARMOR_STAND, position,
                        0.0f, 0.0f, 0.0f, 0, Optional.empty())
                : new WrapperPlayServerSpawnLivingEntity(
                        entityId, uuid, EntityTypes.ARMOR_STAND, position,
                        0.0f, 0.0f, 0.0f, new Vector3d(0.0, 0.0, 0.0), new ArrayList<>());

        List<EntityData<?>> stand = new ArrayList<>(4);
        stand.add(new EntityData<>(0, EntityDataTypes.BYTE, ENTITY_INVISIBLE));
        if (clientVersion.isNewerThanOrEquals(ClientVersion.V_1_13)) {
            stand.add(new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(name)));
        } else {
            stand.add(new EntityData<>(2, EntityDataTypes.STRING, TextPort.legacy(name)));
        }
        stand.add(new EntityData<>(3, EntityDataTypes.BOOLEAN, Boolean.TRUE));
        stand.add(new EntityData<>(standFlagsIndex(clientVersion), EntityDataTypes.BYTE, STAND_MARKER_SMALL));

        return List.of(spawnPacket, new WrapperPlayServerEntityMetadata(entityId, stand));
    }

    /** One kernel step shipped as a relative move — the deltas between the two states. */
    static PacketWrapper<?> move(int entityId, IndicatorBallistics.State from, IndicatorBallistics.State to) {
        return new WrapperPlayServerEntityRelativeMove(
                entityId, to.x() - from.x(), to.y() - from.y(), to.z() - from.z(), false);
    }

    static PacketWrapper<?> destroy(int entityId) {
        return new WrapperPlayServerDestroyEntities(entityId);
    }
}
