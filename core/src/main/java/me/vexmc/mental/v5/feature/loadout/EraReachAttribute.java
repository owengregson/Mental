package me.vexmc.mental.v5.feature.loadout;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.kernel.math.EraReach;
import me.vexmc.mental.platform.Attributes;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code ENTITY_INTERACTION_RANGE} attribute lever (1.20.5+; the retired
 * {@code module.hitbox.EntityInteractionRange} on the v5 seam) — the era melee
 * reach where the running version exposes the attribute.
 *
 * <p>The era survival reach is {@link EraReach#MAX_REACH} (3.0) blocks. On 1.20.5+
 * that reach is this attribute, whose vanilla base is already 3.0 — so on a clean
 * server the lever is effectively a no-op (era IS the default); its meaningful
 * effect is RESETTING any third-party inflation of the base back to era. A base
 * set/restore (not an additive modifier) is the only lever that can pull an
 * inflated base back down: the pre-Mental base is captured once per player and
 * restored exactly on quit / disable.</p>
 *
 * <h2>Honest limit</h2>
 * <p>The attribute sets the era reach WINDOW, but the server adds a small leniency
 * on top (Paper's {@code clientInteractionLeniencyDistance} on 1.21.2+, a
 * hardcoded leniency on 1.20.6) that this surface cannot remove, and the CLIENT
 * still picks the melee target. This enforces the era reach distance; it cannot
 * make the window byte-identical to 1.7's client targeting. Below 1.20.5 the
 * attribute does not exist — this helper is a complete no-op (the vanilla gate is
 * a hardcoded ~6 blocks with no safe per-player lever).</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>Every attribute write runs inside {@link Scheduling#runOn} on the player's
 * region thread. Per-player captured bases live in a {@link ConcurrentHashMap}
 * keyed by UUID; on disable every captured base is restored inline (the scheduler
 * may already be stopping, so a deferred write could never run — the disable path
 * runs on the main / global thread where synchronous attribute access is safe).</p>
 */
final class EraReachAttribute {

    private final Plugin plugin;
    private final Scheduling scheduling;

    /** Per-player original attribute base, captured before Mental set the era value. */
    private final ConcurrentHashMap<UUID, Double> originalBase = new ConcurrentHashMap<>();

    EraReachAttribute(@NotNull Plugin plugin, @NotNull Scheduling scheduling) {
        this.plugin = plugin;
        this.scheduling = scheduling;
    }

    /** Whether this server exposes the {@code ENTITY_INTERACTION_RANGE} attribute (1.20.5+). */
    boolean supported() {
        return Attributes.entityInteractionRange() != null;
    }

    /**
     * Sets {@code player}'s interaction-range base to the era reach (clamped into
     * the melee band), capturing the original base first so it can be restored
     * exactly. Idempotent — once captured, the original base is never overwritten.
     */
    void apply(@NotNull Player player) {
        if (!supported()) {
            return;
        }
        scheduling.runOn(player, () -> applyNow(player), () -> {});
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
        // Capture the pre-Mental base once, so a re-apply (reload/world change)
        // never clobbers the real original with our already-set era value.
        originalBase.putIfAbsent(player.getUniqueId(), instance.getBaseValue());
        if (instance.getBaseValue() != era) {
            instance.setBaseValue(era);
        }
    }

    /** Restores {@code player}'s original base (if captured) and forgets the player. Idempotent. */
    void restore(@NotNull Player player) {
        Double original = originalBase.remove(player.getUniqueId());
        if (original == null) {
            return;
        }
        scheduling.runOn(player, () -> restoreNow(player, original), () -> {});
    }

    private void restoreNow(@NotNull Player player, double original) {
        Attribute attribute = Attributes.entityInteractionRange();
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(original);
        }
    }

    /**
     * Zero-touch shutdown: restore the original base for every player Mental
     * touched, INLINE (the disabling thread is the main / global thread, where the
     * synchronous attribute access is safe, and the scheduler may be stopping).
     */
    void disableAll() {
        for (UUID id : List.copyOf(originalBase.keySet())) {
            Double original = originalBase.remove(id);
            if (original == null) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(id);
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
