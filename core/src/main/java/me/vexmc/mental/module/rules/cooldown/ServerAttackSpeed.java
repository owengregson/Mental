package me.vexmc.mental.module.rules.cooldown;

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
 * The SERVER-side half of attack-cooldown removal: raises the player's
 * {@code attack_speed} attribute base so vanilla {@code Player#attack} computes
 * a full-charge swing every time.
 *
 * <h2>Why the client spoof alone is not enough</h2>
 * <p>{@link CooldownSpoof} rewrites only the CLIENT's {@code attack_speed} (the
 * charge-bar overlay); the server attribute was deliberately left at vanilla
 * {@code 4.0}. That is invisible for hits Mental's fast path OWNS — it cancels
 * the ATTACK packet and composes damage in {@code DamageCalculator}, which reads
 * no cooldown term. But a hit Mental does NOT own still runs vanilla
 * {@code Player#attack}, which multiplies melee damage by
 * {@code baseDamageScaleFactor() = 0.2 + getAttackStrengthScale()^2 * 0.8}.
 * Two such paths exist: a NON-PLAYER target on Folia (the fast path can't
 * resolve mobs off-region, so it lets the packet through —
 * {@code HitPacketListener} folia early-return), and ANY hit while the fast path
 * is OFF. On those paths spam-clicking holds the attack-strength ticker near
 * zero, scaling damage down toward 20% (an iron golem took ~11-12 hits instead
 * of 5). Setting the SERVER base high makes {@code getAttackStrengthScale()}
 * return {@code 1.0} for every swing at the source — exact for enchanted weapons
 * too (vanilla scales the base and the Sharpness delta by different factors, so
 * unscaling after the fact could not faithfully reconstruct full damage).</p>
 *
 * <p>On the fast path the server base is simply unused (Mental never calls
 * {@code Player#attack} there and never reads {@code attack_speed}), so this
 * write is harmless-unused on the dominant PvP path and the-fix on the vanilla
 * path — never harmful either way.</p>
 *
 * <h2>The value</h2>
 * <p>The base is set to {@link CooldownSpoof#FULL_CHARGE_ATTACK_SPEED}
 * ({@code 1024.0}, exactly the {@code attack_speed} RangedAttribute's registered
 * ceiling on every supported version — javap-verified 1.17.1-26.x, so it is
 * never clamped lower). At that base the EFFECTIVE speed stays far above the
 * full-charge floor of {@code 40} (the threshold at which a ticker-0 swing
 * already clamps to scale {@code 1.0}) for ANY held weapon, so no per-item
 * tuning is needed — see {@code CooldownDamageScalingTest}. (OCM's default base
 * of {@code 40.0} dips to ~0.94 with a sword's {@code -2.4} modifier, which is
 * why Mental does not copy it.)</p>
 *
 * <h2>Reversibility (zero-touch) and crash/OCM hardening</h2>
 * <p>The pre-Mental base is captured per player and restored on quit / disable,
 * so a disabled module leaves nothing behind. The capture is SANITIZED: a base
 * already in the cooldown-removal regime ({@code >= }{@link #MAX_CAPTURABLE_BASE})
 * is NOT trusted as the original (it is a crashed-Mental {@code 1024}, OCM's
 * {@code 40}, or another cooldown plugin) — the vanilla default {@code 4.0} is
 * captured instead. This means a re-apply while enabled (reload, respawn, world
 * change, or a rejoin after an unclean shutdown that persisted the inflated base
 * to playerdata) always restores correctly. The one residual edge — an unclean
 * shutdown followed by a restart with the module turned OFF, where no listener
 * runs to heal — re-enabling the module fixes; it is the same persistence
 * trade-off OCM's {@code ModuleAttackCooldown} carries.</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>Per-player apply / restore run on the player's region thread via
 * {@code services.scheduling().runOn(player, ...)}. {@link #disableAll} writes
 * INLINE on the disabling thread (mirrors {@code EntityInteractionRange}: a
 * deferred task could never run if the scheduler is stopping; disable runs on
 * the main / global thread).</p>
 */
final class ServerAttackSpeed {

    /** Vanilla player base {@code attack_speed}; the value restored when the module is off. */
    static final double VANILLA_ATTACK_SPEED = 4.0;

    /**
     * Any captured base at or above this is treated as an already-inflated
     * cooldown-removal value (a crashed-Mental {@code 1024}, OCM's {@code 40}, or
     * a third-party plugin), NOT a genuine original — the vanilla default is
     * captured instead so a later restore can never write back the spoofed base.
     * {@code 16.0} is 4x vanilla: far above any real per-player base (weapons use
     * MODIFIERS, not base changes) yet below every known cooldown-removal value.
     */
    static final double MAX_CAPTURABLE_BASE = 16.0;

    private final MentalServices services;
    private final DebugLog.Scoped debug;

    /** Per-player original base, captured (sanitized) before Mental raised it. */
    private final ConcurrentHashMap<UUID, Double> originalBase = new ConcurrentHashMap<>();

    ServerAttackSpeed(@NotNull MentalServices services, @NotNull DebugLog.Scoped debug) {
        this.services = services;
        this.debug = debug;
    }

    /** Whether this server exposes the {@code attack_speed} attribute (every supported version does). */
    boolean supported() {
        return Attributes.attackSpeed() != null;
    }

    /* ------------------------------------------------------------------ */
    /*  Apply / restore                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Raises {@code player}'s {@code attack_speed} base to the full-charge value,
     * capturing the (sanitized) original first. Idempotent: the captured original
     * is never overwritten by a later apply, so reload / respawn / world-change
     * re-applies stay safe. The write runs on the player's region thread.
     */
    void apply(@NotNull Player player) {
        if (!supported()) {
            return;
        }
        services.scheduling().runOn(player, () -> applyNow(player), () -> {});
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
        // Capture the pre-Mental base exactly once, sanitizing an already-inflated
        // value so a restore can never write back a spoofed base (see class doc).
        originalBase.computeIfAbsent(player.getUniqueId(), id -> sanitizedOriginal(instance.getBaseValue()));
        if (instance.getBaseValue() != CooldownSpoof.FULL_CHARGE_ATTACK_SPEED) {
            instance.setBaseValue(CooldownSpoof.FULL_CHARGE_ATTACK_SPEED);
            debug.log(() -> "attack_speed base raised to " + CooldownSpoof.FULL_CHARGE_ATTACK_SPEED
                    + " (full charge) for " + player.getName());
        }
    }

    /**
     * Restores {@code player}'s original {@code attack_speed} base (if Mental
     * captured one) and forgets the player. Idempotent. The write runs on the
     * player's region thread.
     */
    void restore(@NotNull Player player) {
        Double original = originalBase.remove(player.getUniqueId());
        if (original == null) {
            return;
        }
        services.scheduling().runOn(player, () -> restoreNow(player, original), () -> {});
    }

    private void restoreNow(@NotNull Player player, double original) {
        Attribute attribute = Attributes.attackSpeed();
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.setBaseValue(original);
        debug.log(() -> "attack_speed base restored to " + original + " for " + player.getName());
    }

    /**
     * Zero-touch shutdown: restore the original base for every player Mental
     * touched. Runs the write INLINE on the disabling thread (the scheduler may
     * be stopping, so a deferred task could never run) — disable runs on the
     * main / global thread, where synchronous attribute access is safe.
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
            Player player = services.plugin().getServer().getPlayer(id);
            if (player == null) {
                continue; // Offline: the live attribute is gone with them; nothing to write.
            }
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.setBaseValue(original);
            }
        }
    }

    /**
     * The value to capture as a player's original base: the live base, unless it
     * is already in the cooldown-removal regime ({@code >= }{@link #MAX_CAPTURABLE_BASE}),
     * in which case the vanilla default — so a crashed-Mental {@code 1024}, OCM's
     * {@code 40}, or another cooldown plugin's inflation is never trusted as the
     * original to restore.
     */
    private static double sanitizedOriginal(double liveBase) {
        return liveBase >= MAX_CAPTURABLE_BASE ? VANILLA_ATTACK_SPEED : liveBase;
    }
}
