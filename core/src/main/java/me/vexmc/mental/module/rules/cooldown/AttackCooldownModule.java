package me.vexmc.mental.module.rules.cooldown;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.jetbrains.annotations.NotNull;

/**
 * Removes the 1.9 attack cooldown and suppresses its client-side charge
 * animation.
 *
 * <p>Era truth: 1.7.10 and 1.8.9 had no attack-speed attribute and therefore
 * no cooldown machinery. The module restores this feel on modern servers by
 * spoofing the {@code attack_speed} attribute in the outbound
 * {@code UPDATE_ATTRIBUTES} packet to a value so high the client computes
 * a cooldown delay of effectively zero — always fully charged, no greyed-out
 * swing overlay. The server attribute stays at vanilla 4.0; nothing else in
 * the gameplay layer is touched.
 *
 * <p>Design: all behaviour lives in the always-registered
 * {@link CooldownSpoofListener}, which reads the {@code attack-cooldown} flag
 * from the atomic config snapshot on every packet. This mirrors the pattern
 * used by {@code VelocityDuplicateSuppressor} and {@code GroundPacketTap}:
 * Netty-thread listeners are registered for the plugin lifetime and gate on
 * config state rather than having a per-enable/disable registration lifecycle.
 * {@code onEnable} and {@code onDisable} here are therefore minimal — the
 * module's CombatModule wiring exists so the feature appears in
 * {@code /mental module list} and can be toggled live via the command, which
 * triggers a config reload that the listener picks up automatically.
 */
public final class AttackCooldownModule extends CombatModule {

    public AttackCooldownModule(@NotNull MentalServices services) {
        super(services,
                "attack-cooldown",
                "Attack Cooldown Removal",
                "Suppresses the 1.9 attack-cooldown overlay — the UPDATE_ATTRIBUTES "
                        + "packet rewrites attack_speed client-side so every swing appears "
                        + "fully charged (era 1.7/1.8 had no cooldown at all).",
                DebugCategory.PACKETS);
    }

    @Override
    public boolean configEnabled() {
        return services.config().cooldown().enabled();
    }

    @Override
    protected void onEnable() {
        // Behaviour is driven by CooldownSpoofListener, registered for the
        // plugin lifetime in MentalPlugin. Nothing to do here.
    }

    @Override
    protected void onDisable() {
        // Listener gates on config flag; no teardown required.
    }
}
