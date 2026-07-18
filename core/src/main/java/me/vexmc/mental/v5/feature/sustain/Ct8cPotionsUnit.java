package me.vexmc.mental.v5.feature.sustain;

import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.jetbrains.annotations.NotNull;

/**
 * Combat Test 8c potion values ({@code ct8c-potions}, design spec §2.8).
 *
 * <p>Two halves, both default-OFF and zero-touch when disabled (the reconciler
 * never assembles a disabled unit):</p>
 *
 * <ul>
 *   <li><b>Instant Health</b> — a listener substitutes the CT8c value
 *       ({@code 6·2^amp}, {@link Ct8cInstantHeal}) into the {@code MAGIC}
 *       {@link EntityRegainHealthEvent}. Vanilla heals {@code 4·2^amp}; CT8c
 *       raises it to mirror Instant Damage. <b>Instant Damage is NOT
 *       intercepted</b>: vanilla harm is already {@code 6·2^amp}, so the CT8c
 *       "mirror" is a byte-identical no-op there, and touching the broad
 *       {@code MAGIC} damage cause would risk mis-scaling unrelated sources.</li>
 *   <li><b>Strength / Weakness</b> — the {@code ±20%·level} MULTIPLY_TOTAL
 *       melee factors ({@link Ct8cPotionValues}) apply in the fast-path
 *       {@code DamageShaper} composition (the CT8c damage cluster's file), gated
 *       on {@code featureActive(CT8C_POTIONS)} — so this unit is <b>presence-only</b>
 *       for that half, exactly like {@code PotionValuesUnit}. Its being assembled
 *       is the signal; it registers no resource of its own for it.</li>
 * </ul>
 *
 * <p>Tipped-arrow instantaneous effects ×1/8 (spec §2.8/§2.10) are a documented
 * gap on the Bukkit surface: an arrow-sourced instant effect reaches the victim
 * through the same amplifier-less {@code MAGIC} regain/damage event as a potion
 * one, with no way to distinguish the source or read the ×1/8 scale — the pure
 * scale lives in {@code Ct8cPotionMath.tippedArrowScale()} for a future NMS seam.</p>
 */
public final class Ct8cPotionsUnit implements FeatureUnit, Listener {

    @Override
    public Feature descriptor() {
        return Feature.CT8C_POTIONS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // Instant Health substitution. The Strength/Weakness half needs no
        // resource — DamageShaper reads featureActive(CT8C_POTIONS) on the fast
        // path (presence-only, mirroring PotionValuesUnit).
        scope.listen(this);
    }

    /**
     * Substitutes the CT8c Instant Health value into a {@code MAGIC} regain (the
     * cause vanilla uses for instant-health potions). Owning-thread only — the
     * event fires on the victim's region.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInstantHealth(@NotNull EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player && event.getRegainReason() == RegainReason.MAGIC) {
            event.setAmount(Ct8cInstantHeal.substituted(event.getAmount()));
        }
    }
}
