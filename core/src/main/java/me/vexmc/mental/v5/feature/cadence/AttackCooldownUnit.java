package me.vexmc.mental.v5.feature.cadence;

import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.SweepCauses;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.platform.WeaponTooltipAdapter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Attack-cooldown removal as ONE complete contract (mandate B5): every facet lives
 * in this unit's scope, so toggling the feature off kills every half at once —
 * there is no split-brain packet listener that survives the feature (the retired
 * {@code AttackCooldownModule} + {@code CooldownSpoofListener} +
 * {@code WeaponAttributeTooltipHider} on the v5 seam, plus the sweep re-disable).
 *
 * <ul>
 *   <li><b>(a) server rule</b> — {@link AttackChargeReset} raises the SERVER
 *       {@code attack_speed} base so the vanilla attack-charge meter always reads
 *       full-charge (1.9 scaling never applies on the {@code Player#attack} path);
 *       the fast path already defeats the meter by bypassing {@code Player#attack}
 *       (verified 4B). Applied to online players on enable and on join / respawn /
 *       world-change; restored on quit and on scope close (zero-touch).</li>
 *   <li><b>(b) client presentation</b> — {@link CooldownSpoofListener} rewrites the
 *       receiver's own {@code attack_speed} in UPDATE_ATTRIBUTES (packet-local, B10)
 *       so the client shows no charge overlay even on the first sync.</li>
 *   <li><b>(c) tooltip hider</b> — {@link CooldownTooltipListener} strips the
 *       spoofed attack-speed line via the boot-probed {@link WeaponTooltipAdapter}
 *       (packet-local item copies, loud-fail on a mapping break).</li>
 *   <li><b>(d) sweep re-disable</b> — a full charge satisfies vanilla's
 *       {@code scale > 0.9} sweep gate, so 1.9 sweep becomes eligible on the
 *       vanilla path; {@link SweepDamageListener} + {@link SweepParticleListener}
 *       re-disable it here (mandate B5(d)).</li>
 * </ul>
 */
public final class AttackCooldownUnit implements FeatureUnit, Listener {

    private final Plugin plugin;
    private final WeaponTooltipAdapter tooltip;
    private final AttackChargeReset chargeReset;

    public AttackCooldownUnit(
            @NotNull Plugin plugin, @NotNull Scheduling scheduling, @NotNull WeaponTooltipAdapter tooltip) {
        this.plugin = plugin;
        this.tooltip = tooltip;
        this.chargeReset = new AttackChargeReset(scheduling);
    }

    @Override
    public Feature descriptor() {
        return Feature.ATTACK_COOLDOWN;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        // (a) server rule: lifecycle re-apply/restore + apply-to-online now,
        // restore-all on scope close (zero-touch).
        scope.listen(this);
        scope.task(() -> {
            chargeReset.applyOnline();
            return chargeReset::disableAll;
        });
        // (b) client presentation + (c) tooltip hider — the packet halves.
        scope.packets(new CooldownSpoofListener());
        scope.packets(new CooldownTooltipListener(tooltip));
        // (d) sweep re-disable — the full charge restores 1.9 sweep on the vanilla path.
        // Below 1.11 the ENTITY_SWEEP_ATTACK cause does not exist (a direct constant
        // reference is a sticky per-event NoSuchFieldError the bus swallows — the 2.4.1
        // GAP-2 finding) and sweep splash arrives as plain ENTITY_ATTACK, so the
        // re-suppression cannot discriminate it. SweepCauses decides ONCE at assemble:
        // neither half registers (a particle-only cancel would land sweep damage
        // invisibly) and the degrade line prints. Vanilla sword sweep remains.
        if (SweepCauses.present()) {
            scope.listen(new SweepDamageListener());
            scope.packets(new SweepParticleListener());
        } else {
            plugin.getLogger().info("attack-cooldown: the sweep re-suppression (B5(d)) is a documented "
                    + "no-op on this version — the ENTITY_SWEEP_ATTACK damage cause is absent (lands "
                    + "1.11); sweep splash arrives as plain ENTITY_ATTACK and cannot be discriminated; "
                    + "vanilla sword sweep remains.");
        }
    }

    /* ------------------------------- server-rule lifecycle ------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        chargeReset.apply(event.getPlayer());
    }

    /** A respawn resets the attribute map to vanilla defaults — re-apply or the cooldown returns. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(@NotNull PlayerRespawnEvent event) {
        chargeReset.apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        chargeReset.apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        chargeReset.restore(event.getPlayer());
    }
}
