package me.vexmc.mental.v5.feature.loadout;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.kernel.math.EraReach;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code ENTITY_INTERACTION_RANGE} attribute lever for {@code ct8c-reach}
 * (CT8c decompile, spec §2.2), the {@link EraReachAttribute} twin — but writing a
 * PER-WEAPON base (base 2.5 / sword 3.0 / hoe+trident 3.5) rather than the single
 * era 3.0, reconciled on every held-item change. The attribute is client-synced
 * from 1.20.5, so the client's own melee raycast honours the CT8c reach (no
 * phantoms), exactly the {@code combo-reach-handicap} mechanic.
 *
 * <p>Reversibility (zero-touch): the pre-Mental base is captured once per player
 * and restored exactly on quit / scope close. Below 1.20.5 the attribute does not
 * exist — the lever is a complete no-op, and the sub-floor reach enforcement is a
 * reported cross-boundary need (the fast path's rewound reach validation).</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>Per-player apply runs on the player's region thread ({@link Scheduling#runOn});
 * the quit-path restore is INLINE ({@link Scheduling#ensureOn}) pre-save; {@link
 * #disableAll} writes inline on the disabling thread.</p>
 */
final class Ct8cReachAttribute {

    private final Scheduling scheduling;

    /** Per-player pre-Mental interaction-range base, captured before the first CT8c write. */
    private final ConcurrentHashMap<UUID, Double> originalBase = new ConcurrentHashMap<>();

    Ct8cReachAttribute(@NotNull Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    /** Whether this server exposes the {@code ENTITY_INTERACTION_RANGE} attribute (1.20.5+). */
    boolean supported() {
        return Attributes.entityInteractionRange() != null;
    }

    /**
     * Sets {@code player}'s interaction-range base to {@code reach} (the CT8c reach
     * for their held weapon, clamped into the melee band), capturing the pre-Mental
     * original first. Idempotent on the capture.
     */
    void apply(@NotNull Player player, double reach) {
        if (!supported()) {
            return;
        }
        scheduling.runOn(player, () -> applyNow(player, EraReach.clampInteractionRange(reach)), () -> {});
    }

    private void applyNow(@NotNull Player player, double reach) {
        Attribute attribute = Attributes.entityInteractionRange();
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        originalBase.putIfAbsent(player.getUniqueId(), instance.getBaseValue());
        if (instance.getBaseValue() != reach) {
            instance.setBaseValue(reach);
        }
    }

    /** Restores {@code player}'s captured original base (if any) and forgets it — INLINE, pre-save. */
    void restore(@NotNull Player player) {
        Double original = originalBase.remove(player.getUniqueId());
        if (original == null) {
            return;
        }
        scheduling.ensureOn(player, () -> restoreNow(player, original), () -> {});
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

    /** Zero-touch teardown (scope close): restore every captured base INLINE on the disabling thread. */
    void disableAll() {
        Attribute attribute = Attributes.entityInteractionRange();
        if (attribute == null) {
            originalBase.clear();
            return;
        }
        for (UUID id : List.copyOf(originalBase.keySet())) {
            Double original = originalBase.remove(id);
            if (original == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.setBaseValue(original);
            }
        }
    }
}
