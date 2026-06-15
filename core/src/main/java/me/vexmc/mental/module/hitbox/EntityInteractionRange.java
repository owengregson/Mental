package me.vexmc.mental.module.hitbox;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugLog;
import me.vexmc.mental.platform.Attributes;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code ENTITY_INTERACTION_RANGE} attribute lever (1.20.5+).
 *
 * <p>The era melee reach is {@code 3.0} blocks. On 1.20.5+ that reach is the
 * {@code ENTITY_INTERACTION_RANGE} attribute, whose vanilla base is already
 * {@code 3.0} — so on a clean server this lever is effectively a no-op (era IS
 * the default). Its meaningful effect is RESETTING any third-party inflation of
 * the base back to the era reach.</p>
 *
 * <h2>Why a base set/restore and not an additive modifier</h2>
 * <p>An additive {@code AttributeModifier} cannot reset an inflated BASE — it only
 * stacks on top of it, so it could not bring an inflated value back down to
 * {@code 3.0}. The only lever that achieves the stated goal ("reflect era
 * {@code 3.0}") is to set the BASE value, capturing the pre-Mental base per player
 * and restoring it on quit / disable. The restore is exact and reversible: the
 * captured original base is written straight back.</p>
 *
 * <h2>Honest limit</h2>
 * <p>The attribute sets the era reach WINDOW, but the server adds a small
 * leniency ON TOP of it (Paper's {@code clientInteractionLeniencyDistance} on
 * 1.21.2+, a hardcoded leniency on 1.20.6) that cannot be removed from this
 * surface, and the CLIENT still picks the melee target. So this enforces the era
 * reach distance; it cannot make the window byte-identical to 1.7's client
 * targeting (already close for modern melee). Below 1.20.5 the attribute does not
 * exist — this helper is a complete no-op (the gate hardcodes 6 blocks, already
 * more lenient than era, with no safe per-player lever).</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>Every attribute read/write runs inside {@code services.scheduling().runOn(
 * player, …)} (the player's region thread) — never the Bukkit global scheduler.
 * Per-player captured-base state lives in a {@link ConcurrentHashMap} keyed by
 * UUID; on disable every captured base is restored for all online players.</p>
 */
final class EntityInteractionRange {

    private final MentalServices services;
    private final DebugLog.Scoped debug;

    /** Per-player original attribute base, captured before Mental set the era value. */
    private final ConcurrentHashMap<UUID, Double> originalBase = new ConcurrentHashMap<>();

    EntityInteractionRange(@NotNull MentalServices services, @NotNull DebugLog.Scoped debug) {
        this.services = services;
        this.debug = debug;
    }

    /** Whether this server exposes the {@code ENTITY_INTERACTION_RANGE} attribute (1.20.5+). */
    boolean supported() {
        return Attributes.entityInteractionRange() != null;
    }

    /* ------------------------------------------------------------------ */
    /*  Apply / restore                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Sets {@code player}'s entity-interaction-range base to the era reach
     * ({@code 3.0}, clamped into the melee band), capturing the original base first
     * so it can be restored exactly. Idempotent: once captured, the original base is
     * never overwritten by a later apply. The attribute write runs on the player's
     * region thread.
     */
    void apply(@NotNull Player player) {
        if (!supported()) {
            return;
        }
        services.scheduling().runOn(player, () -> applyNow(player), () -> {});
    }

    private void applyNow(@NotNull Player player) {
        Attribute attribute = Attributes.entityInteractionRange();
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        double era = EraReach.clampInteractionRange(EraReach.MAX_REACH);
        // Capture the pre-Mental base exactly once, so a re-apply (reload, world
        // change) never clobbers the real original with our already-set era value.
        originalBase.putIfAbsent(player.getUniqueId(), instance.getBaseValue());
        if (instance.getBaseValue() != era) {
            instance.setBaseValue(era);
            debug.log(() -> "entity-interaction-range set to " + era + " for " + player.getName());
        }
    }

    /**
     * Restores {@code player}'s original entity-interaction-range base (if Mental
     * captured one) and forgets the player. Idempotent. The attribute write runs on
     * the player's region thread.
     */
    void restore(@NotNull Player player) {
        Double original = originalBase.remove(player.getUniqueId());
        if (original == null) {
            return;
        }
        services.scheduling().runOn(player, () -> restoreNow(player, original), () -> {});
    }

    private void restoreNow(@NotNull Player player, double original) {
        Attribute attribute = Attributes.entityInteractionRange();
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.setBaseValue(original);
        debug.log(() -> "entity-interaction-range restored to " + original + " for " + player.getName());
    }

    /**
     * Zero-touch shutdown: restore the original base for every player Mental
     * touched. Runs the write INLINE on the disabling thread (the scheduler may be
     * stopping, so a deferred task could never run) — disable runs on the main /
     * global thread, where the synchronous attribute access is safe.
     */
    void disableAll() {
        for (UUID id : List.copyOf(originalBase.keySet())) {
            Double original = originalBase.remove(id);
            if (original == null) {
                continue;
            }
            Player player = services.plugin().getServer().getPlayer(id);
            if (player == null) {
                continue; // Offline: the live attribute is gone with them; nothing to write.
            }
            Attribute attribute = Attributes.entityInteractionRange();
            if (attribute == null) {
                continue;
            }
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.setBaseValue(original);
            }
        }
    }
}
