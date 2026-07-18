package me.vexmc.mental.v5.feature.cadence;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The ATTACK_SPEED attribute lever for the {@code weapon-attack-speeds} module
 * (CT8c decompile, spec §2.2) — the {@link AttackChargeReset} structural twin,
 * but writing a PER-WEAPON base rather than a fixed spoof: on every held-item
 * change the base becomes {@code attacksPerSecond + 1.5} for the newly-held CT8c
 * weapon class, so a sword reads 4.5, an axe 3.5, the bare hand 4.0.
 *
 * <p>Reversibility (zero-touch): the pre-Mental base is captured once per player
 * (never overwritten by a later re-apply) and restored exactly on quit / scope
 * close. The attribute exists on every supported version (the attribute API lands
 * 1.9), so the lever works down to the 1.9.4 legacy floor with no version work.</p>
 *
 * <h2>Known limit (documented, not a defect)</h2>
 * <p>This writes the player attribute BASE. On modern servers a held weapon also
 * carries its own vanilla ATTACK_SPEED modifier, which composes on top of the base
 * — so the EFFECTIVE speed is the CT8c base plus that modifier, not exactly {@code
 * attacksPerSecond + 1.5}. Fully neutralising the per-item modifier is an
 * item-component job (the CT8c-reach component seam's sibling) and is reported as a
 * cross-boundary need; the base lever is the {@code AttackChargeReset}-template
 * approach the plan specifies and is era-adjacent on the legacy tier where weapons
 * carry no ATTACK_SPEED modifier at all.</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>Per-player apply / restore run on the player's region thread ({@link
 * Scheduling#runOn}); the quit-path restore is INLINE ({@link Scheduling#ensureOn})
 * so the captured base is written back before the disconnect save (the
 * {@code AttackChargeReset} quit-path shape); {@link #disableAll} writes inline on
 * the disabling thread (the scheduler may be stopping).</p>
 */
final class Ct8cWeaponSpeedAttribute {

    private final Scheduling scheduling;

    /** Per-player pre-Mental ATTACK_SPEED base, captured before the first CT8c write. */
    private final ConcurrentHashMap<UUID, Double> originalBase = new ConcurrentHashMap<>();

    Ct8cWeaponSpeedAttribute(@NotNull Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    /** Whether this server exposes the {@code attack_speed} attribute (every supported version does). */
    boolean supported() {
        return Attributes.attackSpeed() != null;
    }

    /**
     * Sets {@code player}'s ATTACK_SPEED base to {@code targetBase} (the CT8c value
     * for their held weapon), capturing the pre-Mental original first. Idempotent on
     * the capture — a re-apply for a new weapon overwrites only the live base, never
     * the stored original.
     */
    void apply(@NotNull Player player, double targetBase) {
        if (!supported()) {
            return;
        }
        scheduling.runOn(player, () -> applyNow(player, targetBase), () -> {});
    }

    private void applyNow(@NotNull Player player, double targetBase) {
        Attribute attribute = Attributes.attackSpeed();
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        originalBase.putIfAbsent(player.getUniqueId(), instance.getBaseValue());
        if (instance.getBaseValue() != targetBase) {
            instance.setBaseValue(targetBase);
        }
    }

    /**
     * Restores {@code player}'s captured original base (if any) and forgets it —
     * INLINE (pre-save) when the calling thread owns the player, the quit-path shape
     * that keeps a Mental base from persisting into the disconnect save.
     */
    void restore(@NotNull Player player) {
        Double original = originalBase.remove(player.getUniqueId());
        if (original == null) {
            return;
        }
        scheduling.ensureOn(player, () -> restoreNow(player, original), () -> {});
    }

    private void restoreNow(@NotNull Player player, double original) {
        Attribute attribute = Attributes.attackSpeed();
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(original);
        }
    }

    /**
     * Zero-touch teardown (scope close): restore every captured original base INLINE
     * on the disabling thread (the main / global thread, where synchronous attribute
     * access is safe and the scheduler may already be stopping).
     */
    void disableAll() {
        Attribute attribute = Attributes.attackSpeed();
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
                continue; // offline: the live attribute is gone with them
            }
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.setBaseValue(original);
            }
        }
    }
}
