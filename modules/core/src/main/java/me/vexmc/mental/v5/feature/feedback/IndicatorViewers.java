package me.vexmc.mental.v5.feature.feedback;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Resolves WHO sees one damage/heal indicator. The stand is packet-only, so an
 * indicator has no inherent audience — this class is the whole rule.
 *
 * <h2>The rule</h2>
 * <p>Every player near the victim sees the number, EXCEPT the victim themself: you
 * never see indicators for damage happening to YOU (owner ruling — your own health
 * bar already tells you that, and self-numbers are clutter in a fight). The
 * attacker is always included even when far outside the radius, so a bow sniper
 * still sees the damage they dealt from 50 blocks out.
 *
 * <p>That one rule covers every case uniformly, with no special-casing per damage
 * source: a player hitting a mob (the attacker sees it), a mob hitting a player
 * (bystanders see it, the victim does not), a mob hitting a mob (bystanders see
 * it), and environmental damage such as fall or lava (bystanders see it, the
 * victim does not). Damage with nobody nearby resolves to an empty list and is
 * dropped before any packet work.
 *
 * <h2>Why {@code getNearbyEntities}, not {@code World#getPlayers}</h2>
 * <p>Nearby entities live in nearby chunks, which on Folia belong to the victim's
 * own region — so this read is region-legal at the damage event (the same moment
 * the ground scan and ring placement are taken). A world-wide player sweep would
 * touch players on other region threads.
 *
 * <p>The resolved set is FROZEN into the {@code IndicatorWindowBook.Ship} exactly
 * like the spawn geometry and ground plane, so a held window's later ship performs
 * no world reads at all and stays region-thread-agnostic.
 */
final class IndicatorViewers {

    /**
     * How far from the victim an indicator is drawn, in blocks. Deliberately a
     * constant rather than a knob: it is sized to where floating damage text is
     * still legible, and every extra viewer is real per-tick packet cost, so this
     * is a bound rather than a preference. A mob farm out of everyone's sight
     * therefore costs nothing — the resolve returns empty and the hit is dropped.
     */
    static final double VIEW_RADIUS = 24.0;

    private IndicatorViewers() {
    }

    /**
     * The players who should see an indicator for damage to {@code victim}, never
     * including the victim. {@code attacker} (nullable — environmental damage and
     * mob-dealt damage have none) is always included when it is not the victim.
     */
    static List<UUID> resolve(LivingEntity victim, Player attacker) {
        List<UUID> nearby = new ArrayList<>(4);
        for (Entity candidate : victim.getNearbyEntities(VIEW_RADIUS, VIEW_RADIUS, VIEW_RADIUS)) {
            if (candidate instanceof Player player) {
                nearby.add(player.getUniqueId());
            }
        }
        return select(victim.getUniqueId(), attacker == null ? null : attacker.getUniqueId(), nearby);
    }

    /**
     * The rule itself, over plain ids — pure, so it is unit-pinned exhaustively
     * without a live server while {@link #resolve} stays a thin gathering shell.
     *
     * <p>{@code attackerId} is included FIRST and unconditionally (it is the one
     * viewer whose distance does not matter — a bow sniper), the victim is always
     * excluded even when nearby or when they damaged themselves, and duplicates
     * collapse so an attacker who is also in the nearby sweep is drawn once.
     */
    static List<UUID> select(UUID victimId, UUID attackerId, List<UUID> nearbyPlayers) {
        List<UUID> viewers = new ArrayList<>(4);
        if (attackerId != null && !attackerId.equals(victimId)) {
            viewers.add(attackerId);
        }
        for (UUID id : nearbyPlayers) {
            if (id.equals(victimId) || viewers.contains(id)) {
                continue; // never the victim; never the attacker twice
            }
            viewers.add(id);
        }
        return viewers;
    }
}
