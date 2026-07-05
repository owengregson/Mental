package me.vexmc.mental.v5.feature.cadence;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.Attributes;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The SERVER-rule half of attack-cooldown removal (mandate B5(a)): keeps the
 * vanilla attack-charge meter from ever limiting damage on the paths that run
 * {@code Player#attack} (the retired {@code module.rules.cooldown.ServerAttackSpeed}
 * on the v5 seam).
 *
 * <p>Mental's netty fast path (default ON) cancels the ATTACK packet and calls
 * {@code victim.damage(amount, attacker)} directly, so {@code Player#attack}
 * never runs and the 1.9 charge scaling is never consulted — the meter is
 * structurally defeated there (verified 4B: {@code DamageShaper} composes the
 * amount off the attribute base with no cooldown term). The VANILLA path
 * (mob melee, a non-player target on Folia, or any fast-path-OFF hit) still runs
 * {@code Player#attack}, which multiplies melee damage by
 * {@code 0.2 + getAttackStrengthScale()^2 * 0.8}. Raising the player's SERVER
 * {@code attack_speed} base makes {@code getAttackStrengthScale()} clamp to 1.0
 * for every swing (the delay rounds to ~0.02 ticks), so the meter always reads
 * full-charge — 1.9 scaling never applies on either path. Unscaling in the event
 * cannot faithfully reconstruct full damage because vanilla scales the weapon
 * base and the enchant delta by different factors, so raising the base at the
 * source is the era-exact reconstruction.</p>
 *
 * <p>Reversibility (zero-touch): the pre-Mental base is captured (sanitized: an
 * already-inflated base is not trusted as the original) and restored on quit /
 * scope close. Threading (Folia): per-player apply / restore run on the player's
 * region thread; {@link #disableAll} writes inline on the disabling thread (the
 * scheduler may be stopping, so a deferred task could never run).</p>
 */
public final class AttackChargeReset {

    /** Vanilla player base {@code attack_speed}; the value restored when the feature is off. */
    static final double VANILLA_ATTACK_SPEED = 4.0;

    /**
     * Any captured base at or above this is treated as an already-inflated
     * cooldown-removal value (a crashed-Mental {@code 1024}, OCM's {@code 40}, or
     * a third-party plugin), NOT a genuine original — the vanilla default is
     * captured instead so a later restore can never write back the spoofed base.
     */
    static final double MAX_CAPTURABLE_BASE = 16.0;

    private final Scheduling scheduling;
    private final ConcurrentHashMap<UUID, Double> originalBase = new ConcurrentHashMap<>();

    public AttackChargeReset(@NotNull Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    /** Whether this server exposes the {@code attack_speed} attribute (every supported version does). */
    public boolean supported() {
        return Attributes.attackSpeed() != null;
    }

    /** Raises every online player's base on enable (called on the converging thread). */
    public void applyOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    /**
     * Raises {@code player}'s {@code attack_speed} base to full charge, capturing
     * the sanitized original first. Idempotent — the captured original is never
     * overwritten, so a reload / respawn / world-change re-apply stays safe.
     */
    public void apply(@NotNull Player player) {
        if (!supported()) {
            return;
        }
        scheduling.runOn(player, () -> applyNow(player), () -> {});
    }

    private void applyNow(@NotNull Player player) {
        Attribute attribute = Attributes.attackSpeed();
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        originalBase.computeIfAbsent(player.getUniqueId(), id -> sanitizedOriginal(instance.getBaseValue()));
        if (instance.getBaseValue() != CooldownSpoof.FULL_CHARGE_ATTACK_SPEED) {
            instance.setBaseValue(CooldownSpoof.FULL_CHARGE_ATTACK_SPEED);
        }
    }

    /**
     * Restores {@code player}'s captured original base (if any) and forgets it.
     * INLINE when the calling thread owns the player ({@link Scheduling#ensureOn})
     * — the one call site is the quit listener, where a deferred {@code runOn}
     * dies at the next-tick validity gate AFTER the disconnect has already saved
     * the spoofed {@code 1024.0} base into the player's NBT (audit quit-path
     * conflict: a combat-log would keep no-cooldown permanently once the feature
     * is later disabled). The pre-save inline write is the
     * {@code EphemeralDecoration.onQuit} shape.
     */
    public void restore(@NotNull Player player) {
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
     * Zero-touch teardown (scope close): restore the original base for every player
     * Mental touched, INLINE on the disabling thread (the scheduler may be stopping,
     * so a deferred task could never run — disable runs on the main / global thread).
     */
    public void disableAll() {
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

    private static double sanitizedOriginal(double liveBase) {
        return liveBase >= MAX_CAPTURABLE_BASE ? VANILLA_ATTACK_SPEED : liveBase;
    }
}
