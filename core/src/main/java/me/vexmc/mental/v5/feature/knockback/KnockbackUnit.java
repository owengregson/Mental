package me.vexmc.mental.v5.feature.knockback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.debug.DebugLog;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.kernel.combo.ComboTracker;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.delivery.ValvePayload;
import me.vexmc.mental.kernel.math.HurtYaw;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.math.PocketServoConfig;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.wire.CompensationQuery;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.VelocityValve;
import me.vexmc.mental.v5.Vectors;
import me.vexmc.mental.v5.coexist.OcmBinding;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.delivery.HitIds;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.feature.combo.ComboEvents;
import me.vexmc.mental.v5.rim.BurstSender;
import me.vexmc.mental.v5.rim.ConnectionDomains;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 1.7.10 melee knockback (the retired {@code KnockbackModule} on the v5 seams).
 * A pure vector computer: it reads the event's transaction from the
 * {@code DamageRouter} slot, computes the engine vector from the victim's
 * {@link me.vexmc.mental.kernel.ledger.MotionLedger} residual, and submits it to
 * the victim's {@link DeliveryDesk} — which swaps it into the velocity event and
 * records what shipped (spec §3.4–§3.5). A fast-path hit that already carried a
 * pre-delivered vector is <em>adopted</em>, not recomputed (the era servers
 * stamped once).
 *
 * <p>Dispatch is on the typed {@link HitSource}: a {@code RodPull} is never
 * treated as melee (B6); a server-side melee that never hit the fast path arrives
 * as {@code Vanilla(ENTITY_ATTACK)} and is knocked. Accepted sprint-bonus hits run
 * the vanilla obligations the cancelled attack left behind — the attacker's
 * {@code setSprinting(false)}, its ledger {@code ×0.6} self-slow, and the wire
 * sprint clear.</p>
 */
public final class KnockbackUnit implements FeatureUnit, Listener {

    private final SessionService sessions;
    private final ConnectionDomains domains;
    private final OcmBinding ocmBinding;
    private final LatencyModel latency;
    private final Scheduling scheduling;
    private final Supplier<Snapshot> snapshot;
    private final HitIds ids;
    private final TickClock clock;
    private final VelocityValve valve;
    private final DebugLog.Scoped debug;

    /**
     * The burst sender reused for the blocked-hit hurt presentation (ships
     * VELOCITY + HURT exactly as the fast path would). Lazily constructed on the
     * first blocked delivery so its {@code PacketEvents} version read happens at
     * runtime (post-init), never eagerly at reconciler registration.
     */
    private volatile BurstSender blockBurst;

    public KnockbackUnit(
            SessionService sessions, ConnectionDomains domains, OcmBinding ocmBinding,
            LatencyModel latency, Scheduling scheduling, Supplier<Snapshot> snapshot,
            HitIds ids, TickClock clock, VelocityValve valve, DebugLog.Scoped debug) {
        this.sessions = sessions;
        this.domains = domains;
        this.ocmBinding = ocmBinding;
        this.latency = latency;
        this.scheduling = scheduling;
        this.snapshot = snapshot;
        this.ids = ids;
        this.clock = clock;
        this.valve = valve;
        this.debug = debug;
    }

    @Override
    public Feature descriptor() {
        return Feature.KNOCKBACK;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // DamageModifier is the only shield-absorption signal Bukkit has
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof LivingEntity attacker)
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session == null) {
            return;
        }
        HitTransaction tx = session.currentEventTransaction();
        if (tx == null) {
            return;
        }
        HitSource source = tx.context().source();
        if (source instanceof HitSource.RodPull) {
            return; // a rod deals ENTITY_ATTACK through victim.damage(rodder) — not melee (B6)
        }
        boolean melee = source instanceof HitSource.Melee
                || (source instanceof HitSource.Vanilla vanilla && "ENTITY_ATTACK".equals(vanilla.damageCause()));
        if (!melee) {
            return;
        }

        DeliveryDesk desk = session.desk();
        // A cross-region attacker (Folia boundary straddle / pearl in the dispatch
        // tick): skip before any attacker read — the swallowed-throw trap.
        if (!scheduling.isOwnedByCurrentRegion(attacker)) {
            desk.withdraw(tx.context().id());
            // DROPPED (logged skip). UUIDs only — the attacker sits off this
            // region, so a live getName() would throw on Folia.
            debug.log(() -> "melee knock DROPPED (logged skip): attacker " + tx.context().attackerId()
                    + " is off victim " + victim.getUniqueId() + "'s region");
            return;
        }
        KnockbackProfile profile = profileFor(session, victim);
        boolean blockModifier = event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0;
        // Era rule: blocking shaped DAMAGE only (knockBack ran first), so a
        // blocking victim was knocked in FULL — cancel only for a FULL block
        // (final damage zero, the modern shield vanilla itself never knocks
        // through); a partial reduction knocks like the era.
        if (profile.shieldBlockingCancels() && blockModifier && event.getFinalDamage() <= 0.0) {
            desk.withdraw(tx.context().id());
            return;
        }
        // OCM owns melee knockback for this attacker's modeset — yield entirely.
        UUID decider = attacker instanceof Player ? attacker.getUniqueId() : victim.getUniqueId();
        if (!ocmBinding.mentalOwns(MechanicToken.MELEE_KNOCKBACK, decider)) {
            desk.withdraw(tx.context().id());
            return;
        }

        boolean sprinting = attackerSprinting(source, tx, attacker);
        // A PARTIAL native block (a negative BLOCKING modifier that still lands
        // damage — the BLOCKS_ATTACKS component tier on 1.21.5+). Vanilla runs its
        // blocked-damage pipeline but SKIPS markHurt, so no ENTITY_VELOCITY is
        // broadcast and no PlayerVelocityEvent fires — the desk's await would be
        // swept as "no-velocity-event" and the era knock lost. The era knocks a
        // partial block in FULL, so deliver the vector directly. Only a FRESH hit
        // (not a mid-invulnerability difference hit) knocks: the difference branch
        // is era-silent and must stay so (compendium: stronger mid-invuln hit deals
        // difference damage with NO knock and no flinch).
        boolean freshPartialBlock = blockModifier && event.getFinalDamage() > 0.0
                && !victimImmune(session, victim);

        HitTransaction.State state = tx.state();
        if (state == HitTransaction.State.PRE_SENT || state == HitTransaction.State.PINNED) {
            if (freshPartialBlock) {
                deliverBlockedKnock(
                        session, victim, attacker, source, tx, tx.carried(),
                        state == HitTransaction.State.PRE_SENT, sprinting, tx.paceFactor(),
                        tx.comboFactor());
                return;
            }
            // Adopt the pre-delivered vector — the era stamped once.
            desk.awaitVelocityEvent(tx);
            applyAttackerObligations(attacker, sprinting, tx.context().sprint().at());
            return;
        }

        Double compensationY = compensationFor(source, tx, session, victim);
        EntityState victimState = EntityStates.captureVictim(victim, session.ledger());
        EntityState attackerState = EntityStates.capture(attacker, sprinting);
        boolean freshSprint = source instanceof HitSource.Melee && sprinting
                && tx.context().sprint().fresh() != null && tx.context().sprint().fresh();
        // The pocket servo (combo-hold §3.2): active only when THIS attacker holds
        // the victim's active combo, read from the victim's frozen view — one truth
        // shared with the netty pre-send. INACTIVE ⇒ σ = 1.0 (byte-identical).
        PocketServoConfig servo = comboServoFor(session.view(), attacker.getUniqueId());
        KnockbackEngine.Paced paced = KnockbackEngine.computePaced(
                attackerState, victimState, profile, compensationY,
                ThreadLocalRandom.current(), freshSprint, servo);
        KnockbackVector vector = paced.vector();
        tx.paceFactor(paced.paceFactor()); // journal the factor actually applied (D-6)
        tx.comboFactor(paced.comboFactor());

        if (freshPartialBlock) {
            deliverBlockedKnock(session, victim, attacker, source, tx, vector, false, sprinting,
                    tx.paceFactor(), tx.comboFactor());
            return;
        }

        desk.submit(tx, vector);
        desk.awaitVelocityEvent(tx);
        applyAttackerObligations(attacker, sprinting, tx.context().sprint().at());
    }

    /** A protection plugin cancelling the melee hit withdraws the queued knock. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMeleeCancelled(EntityDamageByEntityEvent event) {
        if (!event.isCancelled()
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        CombatSession session = sessions.sessionFor(victim.getUniqueId());
        if (session == null) {
            return;
        }
        HitTransaction tx = session.currentEventTransaction();
        if (tx != null && !(tx.context().source() instanceof HitSource.RodPull)) {
            session.desk().withdraw(tx.context().id());
        }
    }

    private boolean attackerSprinting(HitSource source, HitTransaction tx, LivingEntity attacker) {
        if (source instanceof HitSource.Melee) {
            SprintVerdict verdict = tx.context().sprint();
            return verdict != null && verdict.sprinting();
        }
        return attacker instanceof Player player && player.isSprinting();
    }

    private Double compensationFor(
            HitSource source, HitTransaction tx, CombatSession session, Player victim) {
        if (source instanceof HitSource.Melee) {
            return tx.context().compensationY(); // compute-once from the fast path
        }
        if (!snapshot.get().enabled(Feature.LATENCY_COMPENSATION)) {
            return null;
        }
        PlayerView view = session.view();
        if (view == null) {
            return null;
        }
        Double ping = latency.forPlayer(victim.getUniqueId()).pingMillis();
        int rtt = ping == null ? 0 : (int) Math.round(ping);
        return CompensationQuery.verticalFor(view, rtt, view.motion().vy());
    }

    /**
     * The vanilla obligations the cancelled attack left behind, for an accepted
     * sprint-bonus hit: the attacker's ledger {@code ×0.6} self-slow, the wire
     * sprint clear, and {@code setSprinting(false)} (a no-op when vanilla's own
     * attack already cleared it on the server-side melee path).
     *
     * <p>Both clears are guarded by the hit's verdict stamp ({@code asOf}). Vanilla
     * cleared sprint synchronously inside {@code attack}, so it could never eat a
     * re-engage that arrived afterwards; Mental's clears run 1–2 ticks late at the
     * deferred EDBEE, so a w-tap START in that window must survive. The wire clear
     * no-ops under a newer wire write ({@link SprintWire#onServerClear(TickStamp)}),
     * and the deferred {@code setSprinting(false)} is skipped when the wire has
     * re-armed to sprinting by execution time (F2).</p>
     */
    private void applyAttackerObligations(LivingEntity attacker, boolean sprinting, TickStamp asOf) {
        if (!(attacker instanceof Player player)) {
            return;
        }
        UUID attackerId = player.getUniqueId();
        CombatSession attackerSession = sessions.sessionFor(attackerId);
        // Combo-hold retaliation (combo-hold §3.1): the player who just landed this
        // accepted melee is the combo VICTIM if they were being juggled — landing
        // any hit ends the combo held against them. This resolves the ATTACKER's
        // own session (same region as the victim for melee), so it stamps the
        // right tracker regardless of whether the hit carried a sprint bonus.
        if (attackerSession != null && attackerSession.comboTracker() != null) {
            ComboEvents.fire(player, attackerSession.comboTracker().onOwnHitLanded(clock.current()));
        }
        boolean bonus = sprinting || heldKnockbackLevel(player) > 0;
        if (!bonus) {
            return;
        }
        if (attackerSession != null) {
            attackerSession.ledger().scaleHorizontal(0.6);
        }
        domains.domainFor(attackerId).sprint().onServerClear(asOf);
        if (player.isSprinting()) {
            scheduling.runOn(player, () -> {
                // At execution time (the attacker's own thread), skip the clear when
                // the wire re-armed to sprinting after the hit — a re-engage newer
                // than asOf that vanilla's synchronous clear would never have eaten.
                if (domains.domainFor(attackerId).sprint().verdictAt(clock.current()).sprinting()) {
                    return;
                }
                player.setSprinting(false);
            }, () -> {});
        }
    }

    /**
     * Deliver a fresh partial-block knock when no vanilla velocity event will
     * resolve it — the desk's no-velocity-event path (the same mechanism the
     * thrown-projectile ensure uses). On the {@code BLOCKS_ATTACKS} component tier
     * (1.21.5+) vanilla reduces a blocked hit natively but SKIPS {@code markHurt},
     * so it broadcasts no {@code ENTITY_VELOCITY} and fires no {@code
     * PlayerVelocityEvent}; the desk's await would be swept as "no-velocity-event"
     * and the era knock lost. The era knocks a partial block in FULL
     * (compendium: blocking halves damage AFTER knockBack ran), so we ship it.
     *
     * <p>The original transaction is withdrawn — and JOURNALED
     * ({@code blocked-redeliver} → the fresh id) so the sweep never records a
     * false drop and the blocked hit stays correlatable — and a fresh one is
     * submitted carrying the ORIGINAL sprint verdict (not a misleading
     * {@code SprintVerdict(false)}). {@code setVelocity} triggers the tracker's
     * velocity event, which the desk resolves to the full stamp (undecayed: a
     * REGISTERED resolve ships the submitted vector, ignoring the physics-decayed
     * api value) and journals a SHIP. Delivery runs INLINE when the current thread
     * already owns the victim (the EDBEE does), so the knock ships the SAME tick —
     * era-consistent (vanilla knockback applies during attack processing, not a
     * tick later) — falling back to {@code runOn} otherwise; a retired victim
     * journals a {@code victim-retired} drop rather than vanishing silently.</p>
     *
     * <p>Presentation mirrors the fast path: a client with no wire pre-send gets a
     * VELOCITY + HURT burst, and the valve is armed whenever a wire copy exists so
     * the authoritative tracker re-emission is consumed once, never doubled. The
     * VICTIM'S OWN HURT SOUND is played to the victim alone in BOTH branches:
     * vanilla's {@code playHurtSound} broadcast excludes exactly the victim, and
     * the victim's client derives its own hurt sound from the
     * {@code ClientboundDamageEventPacket} that the blocked branch replaces with a
     * no-op {@code onBlocked} (blockSound empty) — so the one missing audience is
     * the victim (F4; the 2.4.0 record wrongly assumed vanilla still served it).
     * Mental's HURT_ANIMATION burst is soundless by design. A world broadcast
     * would double the sound for the bystanders vanilla already served. This path
     * only triggers on the BLOCKS_ATTACKS tier (1.21.5+), so the direct Bukkit
     * constant needs no legacy-name fallback (the ToolWear precedent).</p>
     */
    private void deliverBlockedKnock(
            CombatSession session, Player victim, LivingEntity attacker, HitSource source,
            HitTransaction original, KnockbackVector era, boolean wirePreSent, boolean sprinting,
            double paceFactor, double comboFactor) {
        applyAttackerObligations(attacker, sprinting, original.context().sprint().at());
        if (era == null) {
            return; // nothing carried/computed — leave vanilla's (absent) knock
        }
        DeliveryDesk desk = session.desk();
        UUID victimId = victim.getUniqueId();
        UUID attackerId = attacker.getUniqueId();
        // Mint the fresh redelivery up front so the withdraw can name its
        // superseding id and a retired delivery can journal a correlated drop. It
        // carries the ORIGINAL sprint verdict — the journal context stays honest.
        HitTransaction fresh = mint(source, attackerId, victimId, original.context().sprint(),
                paceFactor, comboFactor);
        // Withdraw + JOURNAL the original (a PRE_SENT/PINNED submitFromWire) so the
        // tick sweep cannot record it as a false "no-velocity-event"; a REGISTERED
        // region-path original was never on the desk, so this is a no-op there.
        desk.withdrawSuperseded(original.context().id(), "blocked-redeliver", fresh.context().id());
        PlayerView view = session.view();
        int entityId = view != null ? view.entityId() : victim.getEntityId();
        float hurtYaw = hurtYawFor(attacker, victim);
        boolean bundle = bundleFeedback();
        Runnable delivery = () -> {
            desk.submit(fresh, era);
            desk.awaitVelocityEvent(fresh);
            boolean wireCopy = wirePreSent;
            if (!wirePreSent) {
                User user = PacketEvents.getAPI().getPlayerManager().getUser(victim);
                if (user != null) {
                    // No registration burst reached the wire (server-side melee, or a
                    // paced-out velocity): ship VELOCITY + HURT now, exactly as the
                    // fast path would have — the client sees the era knock and flinch.
                    burstSender().ship(user, entityId, era, hurtYaw, bundle);
                    wireCopy = true;
                }
            }
            if (wireCopy) {
                // A wire copy already carries the value; arm the valve so the
                // authoritative tracker re-emission below (and any late vanilla
                // velocity for this hit) is consumed once, never stacked on it.
                valve.arm(victimId, ValvePayload.of(entityId, era));
            }
            // The victim's own hurt sound — the one client vanilla's blocked branch
            // leaves silent (see the javadoc). Play it to the victim alone; pitch
            // mirrors vanilla LivingEntity.handleDamageEvent (1 + (r1 − r2) × 0.2).
            float pitch = 1.0f + (ThreadLocalRandom.current().nextFloat()
                    - ThreadLocalRandom.current().nextFloat()) * 0.2f;
            victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1.0f, pitch);
            // The authoritative motion: overwrites vanilla's blocked deltaMovement,
            // triggers the velocity event (→ desk SHIP journal), and moves the server
            // entity (server-authoritative for anticheats and clientless fakes).
            victim.setVelocity(Vectors.toBukkit(era));
        };
        // Same-tick ship: the EDBEE already runs on the victim's owning thread, so
        // run inline when we own it; a retired victim journals a drop, not silence.
        scheduling.ensureOn(victim, delivery, () -> desk.journalDrop(fresh, "victim-retired"));
    }

    private HitTransaction mint(
            HitSource source, UUID attackerId, UUID victimId, SprintVerdict verdict,
            double paceFactor, double comboFactor) {
        HitContext context = new HitContext(
                ids.next(), source, attackerId, victimId, verdict, false, false, null, clock.current());
        HitTransaction fresh = new HitTransaction(context);
        fresh.paceFactor(paceFactor); // carry the factors the original applied (D-6)
        fresh.comboFactor(comboFactor);
        return fresh;
    }

    /** True when the victim was mid-invulnerability at hit time (the era-silent difference branch). */
    private boolean victimImmune(CombatSession session, Player victim) {
        PlayerView view = session.view();
        if (view != null) {
            return view.damageImmune();
        }
        return victim.getNoDamageTicks() > victim.getMaximumNoDamageTicks() / 2.0;
    }

    private static float hurtYawFor(LivingEntity attacker, Player victim) {
        Location a = attacker.getLocation();
        Location v = victim.getLocation();
        return HurtYaw.hurtYaw(a.getX(), a.getZ(), v.getX(), v.getZ(), v.getYaw());
    }

    @SuppressWarnings("unchecked")
    private boolean bundleFeedback() {
        HitRegSettings settings = snapshot.get().settings(
                (SettingsKey<HitRegSettings>) Feature.HIT_REGISTRATION.settingsKey());
        return settings.bundleFeedback();
    }

    /** Lazily constructed on the first blocked delivery — {@code PacketEvents} read at runtime, not at boot. */
    private BurstSender burstSender() {
        BurstSender local = blockBurst;
        if (local == null) {
            local = new BurstSender();
            blockBurst = local;
        }
        return local;
    }

    private KnockbackProfile profileFor(CombatSession session, Player victim) {
        PlayerView view = session.view();
        return view != null ? view.profile() : snapshot.get().profileFor(victim.getWorld().getName());
    }

    /**
     * The pocket-servo config for a hit from {@code attackerId} to the victim
     * whose frozen {@code view} is given (combo-hold §3.2). Active only when the
     * module is on AND this attacker is the one the view says holds the victim's
     * active combo — the same gate the netty pre-send uses, off one frozen truth.
     * Otherwise {@link PocketServoConfig#INACTIVE} (σ = 1.0, byte-identical).
     */
    @SuppressWarnings("unchecked")
    private PocketServoConfig comboServoFor(PlayerView view, UUID attackerId) {
        if (view == null || attackerId == null || !snapshot.get().enabled(Feature.COMBO_HOLD)) {
            return PocketServoConfig.INACTIVE;
        }
        if (!attackerId.equals(view.comboAttackerId())) {
            return PocketServoConfig.INACTIVE;
        }
        ComboSettings settings = snapshot.get().settings(
                (SettingsKey<ComboSettings>) Feature.COMBO_HOLD.settingsKey());
        return settings.servo();
    }

    private static int heldKnockbackLevel(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && main.getType() != Material.AIR) {
            return main.getEnchantmentLevel(Enchantments.knockback());
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off == null || off.getType() == Material.AIR) {
            return 0;
        }
        return off.getEnchantmentLevel(Enchantments.knockback());
    }
}
