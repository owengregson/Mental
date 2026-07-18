package me.vexmc.mental.v5.feature.loot;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.CollisionRule;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.NameTagVisibility;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.OptionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import java.util.List;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings.GlowColor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Per-player item glow — sent to the KILLER's connection ALONE. Two ingredients
 * make a coloured glow: the entity's "glowing" base-flags bit (metadata index 0,
 * {@code 0x40} — present since 1.9, so it covers the whole runtime range) makes
 * it glow at all, and scoreboard-team membership tints that glow the team's
 * named colour. Both packets go only to the killer, so everyone else sees a
 * plain, un-glowing drop they cannot pick up.
 *
 * <p>The glow outline colour is limited to the 16 named team colours (a client
 * limitation, not ours) — GOLD is the closest to the requested warm gold, with
 * YELLOW offered as the lighter option. One fixed team per colour is created on
 * the killer's client the first time they reserve loot; individual items are
 * then added and removed from it.</p>
 */
public final class GlowPackets {

    /** The entity base-flags byte (metadata index 0); bit {@code 0x40} is "glowing" (since 1.9). */
    static final byte GLOWING_FLAG = 0x40;
    /** Cleared base flags — un-glow. */
    static final byte NO_FLAGS = 0x00;

    private GlowPackets() {}

    /** The fixed per-colour team name (distinct so a colour change is a distinct team). */
    static String teamName(GlowColor color) {
        return color == GlowColor.YELLOW ? "mental_loot_y" : "mental_loot_g";
    }

    /** The named team colour that tints the glow. */
    static NamedTextColor namedColor(GlowColor color) {
        return color == GlowColor.YELLOW ? NamedTextColor.YELLOW : NamedTextColor.GOLD;
    }

    /** The glowing (or un-glowing) base-flags metadata packet for one entity. */
    static WrapperPlayServerEntityMetadata glowingMetadata(int entityId, boolean glowing) {
        byte flags = glowing ? GLOWING_FLAG : NO_FLAGS;
        EntityData<?> data = new EntityData<>(0, EntityDataTypes.BYTE, flags);
        return new WrapperPlayServerEntityMetadata(entityId, List.of(data));
    }

    /** The CREATE packet for the colour's team (empty display, no collision, its named colour). */
    static WrapperPlayServerTeams createTeam(GlowColor color) {
        ScoreBoardTeamInfo info = new ScoreBoardTeamInfo(
                Component.empty(), Component.empty(), Component.empty(),
                NameTagVisibility.ALWAYS, CollisionRule.NEVER, namedColor(color), OptionData.NONE);
        return new WrapperPlayServerTeams(teamName(color), TeamMode.CREATE, info);
    }

    /** Adds one entity UUID string to the colour's team. */
    static WrapperPlayServerTeams addToTeam(GlowColor color, String entry) {
        return new WrapperPlayServerTeams(
                teamName(color), TeamMode.ADD_ENTITIES, (ScoreBoardTeamInfo) null, entry);
    }

    /** Removes one entity UUID string from the colour's team. */
    static WrapperPlayServerTeams removeFromTeam(GlowColor color, String entry) {
        return new WrapperPlayServerTeams(
                teamName(color), TeamMode.REMOVE_ENTITIES, (ScoreBoardTeamInfo) null, entry);
    }

    /**
     * Makes {@code entityId} glow {@code color} to {@code user} alone. When
     * {@code createTeam} is true the colour's team is created on this client
     * first (once per killer per colour). Never throws — a missed cosmetic on a
     * reconfiguring connection beats a surfaced exception.
     */
    static void glow(User user, int entityId, String entityUuid, GlowColor color, boolean createTeam) {
        try {
            if (createTeam) {
                user.writePacketSilently(createTeam(color));
            }
            user.writePacketSilently(addToTeam(color, entityUuid));
            user.writePacketSilently(glowingMetadata(entityId, true));
            user.flushPackets();
        } catch (Throwable reconfiguring) {
            // A missed glow beats a surfaced exception on the send path.
        }
    }

    /** Clears the glow for {@code entityId} on {@code user} — un-glow metadata + team removal. */
    static void clear(User user, int entityId, String entityUuid, GlowColor color) {
        try {
            user.writePacketSilently(glowingMetadata(entityId, false));
            user.writePacketSilently(removeFromTeam(color, entityUuid));
            user.flushPackets();
        } catch (Throwable reconfiguring) {
            // Harmless if the entity is already gone; a missed clear self-heals on relog.
        }
    }
}
