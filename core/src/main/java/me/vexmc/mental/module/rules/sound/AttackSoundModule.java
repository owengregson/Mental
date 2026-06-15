package me.vexmc.mental.module.rules.sound;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.jetbrains.annotations.NotNull;

/**
 * Suppresses the 1.9 swing-result sounds for every hit.
 *
 * <p>Era truth: 1.7.10 and 1.8.9 had no {@code entity.player.attack.*} sounds.
 * The family ({@code crit}, {@code knockback}, {@code nodamage}, {@code strong},
 * {@code sweep}, {@code weak}) was added in 1.9 together with the attack
 * cooldown. Cancelling the outbound sound packets restores the era's silence
 * on every swing; it is purely cosmetic — no velocity, damage, or game state
 * is altered.</p>
 *
 * <p>Design: all behaviour lives in the always-registered
 * {@link AttackSoundListener}, which reads the {@code disable-attack-sounds}
 * flag from the atomic config snapshot on every packet. This mirrors the
 * pattern used by {@code CooldownSpoofListener}, {@code VelocityDuplicateSuppressor},
 * and {@code GroundPacketTap}: Netty-thread listeners are registered for the
 * plugin lifetime and gate on config state rather than having a
 * per-enable/disable registration lifecycle. {@code onEnable} and
 * {@code onDisable} here are therefore no-ops — the module's CombatModule
 * wiring exists so the feature appears in {@code /mental module list} and can
 * be toggled live via the command, which triggers a config reload that the
 * listener picks up automatically.</p>
 */
public final class AttackSoundModule extends CombatModule {

    public AttackSoundModule(@NotNull MentalServices services) {
        super(services,
                "disable-attack-sounds",
                "Attack Sound Suppression",
                "Cancels the 1.9 swing-result sound packets (entity.player.attack.* family) "
                        + "so combat is silent on swing, as it was in 1.7/1.8 — cosmetic only.",
                DebugCategory.PACKETS);
    }

    @Override
    public boolean configEnabled() {
        return services.config().attackSound().enabled();
    }

    @Override
    protected void onEnable() {
        // Behaviour is driven by AttackSoundListener, registered for the
        // plugin lifetime in MentalPlugin. Nothing to do here.
    }

    @Override
    protected void onDisable() {
        // Listener gates on config flag; no teardown required.
    }
}
