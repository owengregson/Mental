package me.vexmc.mental.v5.feature.cadence;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.ChargedAttackSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Combat Test 8c charged attacks ({@code charged-attacks}, CT8c decompile, spec
 * §2.1) — the server-side charge gate. It owns a per-player {@link Ct8cChargeLedger}
 * (D2 state, region-thread-fed): a melee primary is REJECTED below full charge
 * (bar the lenient 4-tick miss-recovery lane), and every landed melee PUBLISHES
 * its scale (and the ≥195% charged-reach flag) into {@link Ct8cChargeView} for the
 * cluster's sweep and reach consumers.
 *
 * <p>The gate rides {@code EntityDamageByEntityEvent} (cause {@code ENTITY_ATTACK})
 * — the seam the existing rule features cancel at, so it covers BOTH the vanilla
 * path and the fast path (whose {@code victim.damage(amount, attacker)} fires the
 * same event). The charge scale is read from the attacker's effective ATTACK_SPEED
 * attribute (so {@code weapon-attack-speeds}, when enabled, feeds the delay) and
 * the {@link TickClock}.</p>
 *
 * <p>Zero-touch: the module defaults OFF; enabled, it registers a gate + an
 * air-swing tap and clears every ledger/view entry on the scope close. Settings
 * are baked, so the unit opts into {@link #rebuildOnSettingsChange}.</p>
 *
 * <h2>Reported cross-boundary needs</h2>
 * <ul>
 *   <li>The ≥195% charged reach bonus is PUBLISHED here but consumed for actual
 *       hit validation only if the fast-path reach validator reads it — that feed
 *       lives in {@code HitRegistrationUnit}/{@code ReachValidator} (kernel /
 *       delivery), outside this cluster's ownership. {@code Ct8cReachUnit} reads
 *       the published flag for the attribute lever; the rewound-reach-validation
 *       feed is a reported need.</li>
 *   <li>The ideal charge read is on the netty thread in packet program-order via
 *       the parse rim's attack/animation taps; the rim exposes no such tap today,
 *       so this uses Bukkit events. On the fast path the deferred damage can read
 *       the charge a tick late — a small, documented skew (the gate stays
 *       correct; only the exact boundary-tick scale can differ).</li>
 * </ul>
 */
public final class ChargedAttackUnit implements FeatureUnit, Listener {

    private final Ct8cChargeLedger ledger = new Ct8cChargeLedger();
    private final Ct8cChargeView view = Ct8cChargeView.INSTANCE;

    /** Per-player tick of the last landed melee — the best-effort air-swing discriminator. */
    private final Map<UUID, Long> lastHitTick = new ConcurrentHashMap<>();

    private TickClock clock;
    private ChargedAttackSettings settings;

    @Override
    public Feature descriptor() {
        return Feature.CHARGED_ATTACKS;
    }

    /** Settings (the gate knobs) are baked at assemble — a reload must re-assemble. */
    @Override
    public boolean rebuildOnSettingsChange() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void assemble(Scope scope, Snapshot snapshot) {
        MentalPluginV5 plugin = JavaPlugin.getPlugin(MentalPluginV5.class);
        this.clock = plugin.clock();
        this.settings = snapshot.settings(
                (SettingsKey<ChargedAttackSettings>) Feature.CHARGED_ATTACKS.settingsKey());

        scope.listen(this);
        scope.task(() -> () -> {
            ledger.clear();
            lastHitTick.clear();
            view.reset();
        });
    }

    /* ------------------------------- the charge gate ------------------------------- */

    /**
     * The primary melee gate: reject a hit below full charge (bar the miss-recovery
     * lane), and publish the landed hit's scale for the sweep / reach consumers. LOW
     * priority so the reject lands before knockback / feedback consumers read the
     * event; the fast path's pre-sent burst is unavoidable (any EDBEE-cancelling
     * rule shares that, e.g. sword-blocking) and the transaction records the reason.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMelee(@NotNull EntityDamageByEntityEvent event) {
        if (event.getCause() != DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        long now = tick();
        if (now == Long.MIN_VALUE) {
            return; // clock not started (a fresh Folia counter) — fail open, never wrongly reject
        }
        UUID id = attacker.getUniqueId();
        double attackSpeed = Attributes.valueOr(attacker, Attributes.attackSpeed(), 4.0);
        Ct8cChargeLedger.Decision decision =
                ledger.onAttack(id, now, attackSpeed, settings.requireFullCharge());

        boolean charged = decision.scale() > settings.chargedThreshold()
                && !(settings.denyBonusWhileCrouching() && attacker.isSneaking());
        view.publish(id, decision.scale(), charged);

        if (!decision.allowed()) {
            event.setCancelled(true);
            return;
        }
        lastHitTick.put(id, now);
    }

    /**
     * Air-swing tap: a swing that connected with nothing arms the miss-recovery
     * lane. Best-effort discrimination — a swing within a tick of a landed hit is
     * that hit's own animation, not a whiff. A spurious arm only LOOSENS the gate
     * (an early hit allowed 4 ticks after a reset), never wrongly rejects.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(@NotNull PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        long now = tick();
        if (now == Long.MIN_VALUE) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        Long hitAt = lastHitTick.get(id);
        if (hitAt != null && now - hitAt <= 1) {
            return; // this swing accompanies the landed hit — not a whiff
        }
        ledger.onAirSwing(id, now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        ledger.forget(id);
        lastHitTick.remove(id);
        view.clear(id);
    }

    /* -------------------------------- helpers -------------------------------- */

    /** The current tick as a long, or {@link Long#MIN_VALUE} when the clock has no real tick. */
    private long tick() {
        TickStamp stamp = clock.current();
        return stamp.known() ? stamp.value() : Long.MIN_VALUE;
    }
}
