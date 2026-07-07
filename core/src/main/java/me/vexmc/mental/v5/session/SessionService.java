package me.vexmc.mental.v5.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.kernel.combo.ComboEndReason;
import me.vexmc.mental.kernel.combo.ComboRules;
import me.vexmc.mental.kernel.combo.ComboTracker;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.delivery.Directive;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.GroundFriction;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KinematicState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.LedgerEvent;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.wire.GroundFsm;
import me.vexmc.mental.kernel.wire.PositionRing;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Pings;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.Vectors;
import me.vexmc.mental.v5.VelocityValve;
import me.vexmc.mental.v5.coexist.OcmBinding;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.feature.combo.ComboEvents;
import me.vexmc.mental.v5.rim.ConnectionDomains;
import me.vexmc.mental.v5.config.Snapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

/**
 * The D2 session domain owner (spec §2): one {@link CombatSession} per player,
 * ticked on that player's owning region thread, publishing one immutable
 * {@link PlayerView} per tick that the netty rim reads. Always-on infrastructure
 * — observation only, never a game mutation, so it holds zero-touch by
 * construction (it reads the live player and publishes frozen values; it writes
 * nothing back to the game).
 *
 * <p>Domains: {@code sessions} (owning-thread state), a frozen
 * {@code entityId → UUID} index the rim resolves victims through, and the
 * per-player tick handles. All three are concurrent maps because join/quit run
 * on the main thread while ticks run on region threads and the rim reads from
 * netty threads — but each {@link CombatSession}'s own fields are mutated only by
 * its owning thread (thread-safety is ownership).</p>
 */
public final class SessionService implements Listener, SessionAccess {

    /** Per-victim journal ring depth — kept in step with the plugin's constant. */
    private static final int JOURNAL_CAPACITY = 16;

    /** Re-seed the wire from the published server flag after this many quiet ticks. */
    private static final int SPRINT_WIRE_QUIET_TICKS = 3;

    private final Scheduling scheduling;
    private final TickClock clock;
    private final ViewBuilder viewBuilder;
    private final VelocityValve valve;
    private final OcmBinding ocmBinding;
    private final Supplier<Snapshot> snapshot;
    private final PositionRing positions;
    private final ConnectionDomains domains;
    private final ComboEvents comboEvents;

    private final ConcurrentHashMap<UUID, CombatSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> entityIdByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, UUID> playerIdByEntityId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, TaskHandle> handles = new ConcurrentHashMap<>();

    /**
     * Per-player owning-thread ground FSM — the tick-sampler that feeds the ledger
     * liftoff/landing for PACKETLESS players (synthetic test players, in-process
     * bots). Real players are fed by the connection domain's packet FSM; the
     * sampler is gated off for them via {@link ConnectionDomains#has} so their
     * ledger is never double-fed.
     */
    private final ConcurrentHashMap<UUID, GroundFsm> samplers = new ConcurrentHashMap<>();

    /** Forget hooks other subsystems register (connection domains, latency, valve). */
    private final List<Consumer<UUID>> forgetHooks = new ArrayList<>();

    /**
     * How many combo KEEPERS hold combo detection open (combo-hold §3; the 2.4.5
     * detection/servo split). BOTH the {@code ComboHoldUnit} (the pocket servo) and
     * the {@code ComboReachHandicapUnit} (the reach handicap) retain/release it on
     * assemble/close, so detection is live iff AT LEAST ONE keeper holds it — the
     * handicap rides the combo transitions even with the servo off, and the servo
     * runs even with the handicap off. This is the reconciler's scope truth, not a
     * re-read config flag: each feature has at most one open scope, so each
     * contributes 0 or 1. While the count is 0, sessions carry no tracker and the
     * per-tick sweep does nothing (zero-touch); the transition to 0 is reconciled on
     * each session's next tick (tracker dropped, one DISABLED end fired on its
     * owning thread).
     */
    private final AtomicInteger comboKeepers = new AtomicInteger();

    public SessionService(
            Scheduling scheduling, TickClock clock, ViewBuilder viewBuilder,
            VelocityValve valve, OcmBinding ocmBinding, Supplier<Snapshot> snapshot,
            PositionRing positions, ConnectionDomains domains, ComboEvents comboEvents) {
        this.scheduling = scheduling;
        this.clock = clock;
        this.viewBuilder = viewBuilder;
        this.valve = valve;
        this.ocmBinding = ocmBinding;
        this.snapshot = snapshot;
        this.positions = positions;
        this.domains = domains;
        this.comboEvents = comboEvents;
    }

    /** The per-player position ring the fast path rewinds through (reach) and reads latest (pre-send). */
    public PositionRing positions() {
        return positions;
    }

    /** Registers the join/quit/world-change listeners and adopts already-online players. */
    public void start(Plugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            join(player);
        }
    }

    /** Retires every session and unregisters the listeners (plugin disable). */
    public void shutdown() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            forget(id);
        }
        HandlerList.unregisterAll(this);
    }

    /** Another subsystem's per-player teardown, run on quit/retire (rim domains, latency, …). */
    public void addForgetHook(Consumer<UUID> hook) {
        forgetHooks.add(hook);
    }

    /**
     * A combo KEEPER (the pocket servo or the reach handicap) opens combo detection
     * (assemble); trackers install lazily per tick. Detection stays live until every
     * keeper has released — the 2.4.5 detection/servo split, so the handicap runs
     * standalone.
     */
    public void retainCombo() {
        this.comboKeepers.incrementAndGet();
    }

    /**
     * A combo keeper closes its hold on combo detection (scope close). When the LAST
     * keeper releases, each session drops its tracker on its next tick (one DISABLED
     * end fired on its owning thread).
     */
    public void releaseCombo() {
        this.comboKeepers.decrementAndGet();
    }

    /* --------------------------- rim read seams --------------------------- */

    /** The last published view for {@code id}, or null before its first tick — the rim's only read. */
    public PlayerView viewOf(UUID id) {
        CombatSession session = sessions.get(id);
        return session == null ? null : session.view();
    }

    /** Enqueue a D1 ledger event onto the victim's MPSC inbox (netty → owning thread). */
    public void enqueue(UUID id, LedgerEvent event) {
        CombatSession session = sessions.get(id);
        if (session != null) {
            session.enqueue(event);
        }
    }

    /** The victim owning entity id {@code entityId} — the frozen index the netty fast path resolves through. */
    public UUID playerIdByEntityId(int entityId) {
        return playerIdByEntityId.get(entityId);
    }

    /** The live session for {@code id}, or null — for the owning-thread routers. */
    public CombatSession sessionFor(UUID id) {
        return sessions.get(id);
    }

    /** Ticks re-seed the wire after silence — this is the quiet-tick threshold callers pass. */
    public int sprintWireQuietTicks() {
        return SPRINT_WIRE_QUIET_TICKS;
    }

    /* ----------------------------- lifecycle ----------------------------- */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // A player quitting mid-combo ends it (RETIRED) so the api events stay
        // balanced; the session (and its tracker) is then discarded by forget.
        CombatSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.comboTracker() != null) {
            comboEvents.fire(event.getPlayer(), session.comboTracker().reset(ComboEndReason.RETIRED));
        }
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // A world change is a teleport-scale discontinuity: forget the flight
        // residual so no phantom knock crosses it (the FSM resyncs on the next
        // movement packet independently).
        enqueue(event.getPlayer().getUniqueId(), new LedgerEvent.Reset(clock.current()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        // Death → respawn re-grounds the player; forget the flight residual so no
        // pre-death knock survives into the respawn.
        enqueue(event.getEntity().getUniqueId(), new LedgerEvent.Reset(clock.current()));
    }

    void join(Player player) {
        UUID id = player.getUniqueId();
        int entityId = player.getEntityId();
        double gravity = Attributes.valueOr(player, Attributes.gravity(), Decay.DEFAULT_GRAVITY);
        CombatSession session = new CombatSession(gravity, entityId, clock, JOURNAL_CAPACITY);
        sessions.put(id, session);
        entityIdByPlayer.put(id, entityId);
        playerIdByEntityId.put(entityId, id);
        samplers.put(id, new GroundFsm(clock));
        scheduleTick(player, id);
    }

    /**
     * (Re)arm the per-player session tick on the player's owning region thread.
     *
     * <p>{@link Scheduling#repeatOn} retires the task when the scheduler can no
     * longer run it. On Folia the entity scheduler fires that retirement only on a
     * genuine removal; the Bukkit emulation, though, fires it on ANY tick where
     * {@link org.bukkit.entity.Entity#isValid()} is false — and that flag is
     * transiently false for a sub-tick right after a player is added to the world
     * (and for several ticks across a respawn or a chunk reload). Coupling the
     * session's whole lifetime to that per-tick flag is a race: if the first
     * scheduled tick lands inside the just-added window, the task retires and the
     * session is forgotten forever (there is no re-create outside {@code join}),
     * so every later hit on that player is un-owned — the packetless victim's
     * knock is journalled as no SHIP and the captor sees vanilla velocity. The
     * {@link #onDeath} reset handler already documents the intent: a session
     * SURVIVES transient invalidity. So {@link #retire} re-arms while the player
     * is still online and only forgets once they are genuinely gone (the
     * {@link #onQuit} path forgets that case too, idempotently).</p>
     */
    private void scheduleTick(Player player, UUID id) {
        TaskHandle handle =
                scheduling.repeatOn(player, 1L, 1L, () -> tick(player), () -> retire(player, id));
        TaskHandle prior = handles.put(id, handle);
        if (prior != null) {
            prior.cancel();
        }
    }

    /**
     * The session tick's retirement seam. A still-online player is only transiently
     * invalid (post-add sub-tick, respawn, chunk reload): keep the session and
     * re-arm the tick so it resumes once the entity is valid again. A genuinely
     * departed player is forgotten (the quit listener covers that path too).
     */
    private void retire(Player player, UUID id) {
        if (!sessions.containsKey(id)) {
            return; // already forgotten (a real quit already ran) — idempotent
        }
        if (player.isOnline()) {
            scheduleTick(player, id);
            return;
        }
        forget(id);
    }

    void forget(UUID id) {
        TaskHandle handle = handles.remove(id);
        if (handle != null) {
            handle.cancel();
        }
        sessions.remove(id);
        samplers.remove(id);
        Integer entityId = entityIdByPlayer.remove(id);
        if (entityId != null) {
            playerIdByEntityId.remove(entityId, id);
        }
        positions.forget(id);
        for (Consumer<UUID> hook : forgetHooks) {
            try {
                hook.accept(id);
            } catch (Throwable ignored) {
                // A forget hook must never break the others' teardown.
            }
        }
    }

    /** One owning-thread session tick: build the view, run the tick step, sweep the valve. */
    private void tick(Player player) {
        CombatSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        sampleGround(player, session);
        // The combat grounded truth for this tick, resolved ONCE (D2 one-source
        // doctrine) and shared by the combo detector's grounded-run end and the
        // view's grounded-tick counter — a packetless player's flag lies airborne
        // on the 1.9/1.10 NMS, so both must read the physical fallback, not the raw
        // isOnGround() they used to read independently.
        boolean combatGrounded = combatGrounded(player);
        // Drive the combo detector BEFORE building the view, so the view publishes
        // this tick's combo state (and its time-driven ends have already fired).
        driveCombo(player, session, combatGrounded);
        PlayerView view = buildView(player, session, combatGrounded);
        // Deliver a PACKETLESS victim's region-path melee knock that no velocity
        // event resolved before the desk's sweep would drop it (below, in tickStep).
        // Run with the SAME `now` the sweep uses, immediately ahead of it, so a hit
        // that DID resolve in time is already gone (zero-touch) and only a genuinely
        // stranded one is ensured.
        ensureStrandedPacketlessMelee(player, session, view.at());
        session.tickStep(view);
        valve.clearStale(player.getUniqueId());
        // One owning-thread position sample per tick — the fast path's reach
        // rewind source and its off-region-safe pre-send position source (the
        // netty thread reads frozen samples, never the live entity).
        Location location = player.getLocation();
        positions.record(player.getUniqueId(),
                location.getX(), location.getY(), location.getZ(), System.nanoTime());
    }

    /**
     * The region-path melee safety net for PACKETLESS victims. The vanilla-melee
     * (REGISTERED) path is the one delivery path with no no-velocity-event fallback
     * of its own: unlike a fast-path pre-send, a blocked knock, or a thrown
     * projectile, it relies ENTIRELY on the victim's {@code PlayerVelocityEvent}
     * resolving the pending decision. That event is server-authoritative and prompt
     * for a real client, but for a packetless player (a synthetic test player /
     * in-process bot) it can be published late — on 1.20.6 the whole knock is
     * routed through the synchronous {@code Player.attack} restore block that resets
     * {@code hurtMarked}, so the victim's velocity event can land a tick or more
     * behind the desk's two-tick sweep. When it does, the desk drops the decision as
     * {@code no-velocity-event} and the era knock is lost — the boxer never takes it,
     * and the delivery journal records no SHIP (the F1 regression: the base-speed
     * server-sprint hit journalled nothing on 1.20.6 alone, the sole tested build in
     * the [1.20.5, 1.21) band).
     *
     * <p>So, one tick before the sweep would drop it (same {@code now}, so the
     * two-tick grace is identical), give the stranded decision the same directed
     * ensure the thrown-projectile path uses: journal the SHIP and apply the era
     * vector with {@code setVelocity} (which the packetless entity needs anyway to
     * actually move, and which lets any observer's captor still see the knock). The
     * gate is tight and provably zero-touch elsewhere: only a victim with NO live
     * connection domain ({@link ConnectionDomains#has} false — a real client is
     * skipped, its velocity event owns delivery), only a still-LIVE REGISTERED melee
     * decision that is genuinely awaiting delivery ({@link DeliveryDesk#awaitingDeliveryFor}
     * — a fast-path/pinned/blocked hit or a resolved one is already gone, and an
     * era-silent BLOCKED difference hit (partially blocked, landing
     * mid-invulnerability) was submitted UNARMED so it is excluded: vanilla knocks
     * nothing there and the era stays silent), and only once it is as old as the
     * sweep's own drop threshold. A hit that resolved in time left nothing pending,
     * so nothing fires.</p>
     */
    private void ensureStrandedPacketlessMelee(Player player, CombatSession session, TickStamp now) {
        if (domains.has(player.getUniqueId())) {
            return; // a real connection — its own velocity event (and the era mirror) delivers
        }
        DeliveryDesk desk = session.desk();
        HitContext pending = desk.pendingContext();
        if (pending == null || !isRegionMelee(pending.source())) {
            return; // nothing pending, or a non-melee/typed source that owns its own ensure
        }
        TickStamp registeredAt = pending.registeredAt();
        if (!registeredAt.known() || !now.known() || now.value() - registeredAt.value() < 2) {
            return; // give the (possibly late) velocity event the exact window the sweep does
        }
        if (!desk.awaitingDeliveryFor(pending.id())) {
            // Only ensure a decision genuinely submitted-for-delivery AND awaiting its
            // velocity event (the exact F1 stranding). Skip a resolved/withdrawn one
            // (the velocity event shipped it) AND — critically — an era-silent
            // BLOCKED difference hit (partially blocked, mid-invulnerability): the
            // knockback unit submits its vector but leaves the await UNARMED because
            // vanilla knocks nothing and fires no velocity event for it. Fabricating
            // a knock there would break the era difference-branch silence (no knock,
            // no flinch); leave it for the sweep to drop.
            return;
        }
        Directive directive = desk.ensure(pending.id());
        KnockbackVector shipped = directive.ship();
        if (shipped != null) {
            player.setVelocity(Vectors.toBukkit(shipped));
        }
    }

    /** A region-path vanilla melee ({@code ENTITY_ATTACK}) or an adopted fast-path melee. */
    private static boolean isRegionMelee(HitSource source) {
        return source instanceof HitSource.Melee
                || (source instanceof HitSource.Vanilla vanilla && "ENTITY_ATTACK".equals(vanilla.damageCause()));
    }

    /**
     * The tick-sampler ground feed for PACKETLESS players (spec §2; the retired
     * per-tick GroundStateWatcher fallback). Real players' ground transitions come
     * from the connection domain's packet FSM (fed by the rim's movement taps); a
     * synthetic player sends no packets, so its liftoff/landing would never reach
     * the ledger and its knock residual would decay at ground drag forever
     * (era-wrong: a knocked victim flies, decaying horizontals at air drag). This
     * samples the live grounded flag once per owning-thread tick and enqueues the
     * same {@link LedgerEvent}s the FSM would, gated off for connected players so
     * the ledger is never double-fed.
     */
    @SuppressWarnings("deprecation") // Player#isOnGround — the transition-relevant client flag
    private void sampleGround(Player player, CombatSession session) {
        UUID id = player.getUniqueId();
        ConnectionDomains.Domain domain = domains.peek(id);
        if (domain != null && domain.ground().hasSeenMovement()) {
            return; // real connection — the packet FSM is actively feeding the ledger
        }
        // Defense in depth (2.4.4): key the stand-down on packets ACTUALLY seen, not
        // on mere domain existence. A domain spuriously created by a read (the
        // poisoning bug's shape) has an unfed GroundFsm (hasSeenMovement()==false),
        // so the sampler stays live and the packetless ledger feed is never silenced.
        GroundFsm sampler = samplers.get(id);
        if (sampler == null) {
            return;
        }
        Location location = player.getLocation();
        double gravity = Attributes.valueOr(player, Attributes.gravity(), Decay.DEFAULT_GRAVITY);
        GroundFsm.ViewSlice slice = new GroundFsm.ViewSlice(
                Decay.JUMP_IMPULSE, jumpBoostAmplifier(player), player.isSprinting(),
                location.getYaw(), gravity);
        LedgerEvent event = sampler.onMovement(player.isOnGround(), true, location.getY(), slice);
        if (event != null) {
            session.enqueue(event);
        }
    }

    /**
     * The combat grounded truth for {@code player} this tick (the D2 one-source
     * feed for the combo detector's grounded-run end and the view's grounded-tick
     * counter). A connected client is trusted verbatim — {@code isOnGround()} is
     * the era-correct, packet-FSM-fed source. A PACKETLESS player (synthetic test
     * player / in-process bot) whose flag lies airborne — the 1.9/1.10 NMS reads
     * {@code isOnGround()==false} forever after a send-then-restore knock even while
     * it rests on the floor — falls back to the physical read the live suite pins
     * against (a solid surface under the feet with the vertical settled). The live
     * block scan is only paid on that packetless fallback path.
     */
    @SuppressWarnings("deprecation") // Player#isOnGround — the client-reported flag (era-correct for real clients)
    private boolean combatGrounded(Player player) {
        boolean onGroundFlag = player.isOnGround();
        boolean connected = domains.has(player.getUniqueId());
        if (connected || onGroundFlag) {
            return CombatGround.grounded(connected, onGroundFlag, Double.NaN, Double.NaN);
        }
        Location location = player.getLocation();
        return CombatGround.grounded(false, false,
                GroundDistance.measure(location), player.getVelocity().getY());
    }

    /**
     * Reads the live player on its owning thread and produces the frozen view.
     * Every read here is owning-thread-legal; the netty realm only ever sees the
     * published result.
     */
    private PlayerView buildView(Player player, CombatSession session, boolean combatGrounded) {
        UUID id = player.getUniqueId();
        Location location = player.getLocation();
        @SuppressWarnings("deprecation") // the client-reported flag is what knockback expectations use
        boolean grounded = player.isOnGround();
        double gravity = Attributes.valueOr(player, Attributes.gravity(), Decay.DEFAULT_GRAVITY);
        double knockbackResistance = clamp01(
                Attributes.valueOr(player, Attributes.knockbackResistance(), 0.0));
        // The attacker's walk-stance-normalized movement-speed attribute for
        // speed-conformal knockback — the sprint modifier is divided back out at
        // capture (a coherent same-thread isSprinting()+value pair), so a pre-sent
        // knock (built from this view) scales identically to the tick path and is
        // immune to wire-vs-server stance disagreement (F1). Unavailable below the
        // attribute API ⇒ the sentinel ⇒ pace factor 1.0.
        double moveSpeedAttr = Attributes.movementSpeedWalkNormalized(player);
        double slipperiness = GroundFriction.of(blockUnderFeet(player, location));
        boolean ocmOwnsMelee = !ocmBinding.mentalOwns(MechanicToken.MELEE_KNOCKBACK, id);
        KnockbackProfile profile = snapshot.get().profileFor(player.getWorld().getName());
        KinematicState kinematics = new KinematicState(
                location.getY(), GroundDistance.measure(location), grounded);

        // Pocket-servo precision inputs (combo-hold §3.2b), all frozen at this
        // publish. The measured per-tick velocity is the delta from the LAST ring
        // sample (recorded end of the previous tick — the sample is read here BEFORE
        // this tick's record()) to the live position, so the drift signal
        // (measured − ledger residual) is coherent by construction. Yaw and the
        // pose-aware eye height are packetless-safe (no connection-domain wire
        // needed); the grounded-tick run is the D2 session counter.
        PositionRing.Sample previous = positions.latest(id);
        double measuredVx = previous == null ? 0.0 : location.getX() - previous.x();
        double measuredVz = previous == null ? 0.0 : location.getZ() - previous.z();
        // The grounded-tick run is a combo/precision signal, so it advances on the
        // combat grounded truth (the packetless physical fallback) — NOT the raw
        // client flag above, which stays the delivery/era air-multiplier baseline.
        int groundedTicks = session.advanceGroundedTicks(combatGrounded);
        // The measured yaw rate (target-v2 repair #4): mean |Δyaw| over the last few
        // ticks, the V2 dynamic target's continuous turn-cost divisor. Advanced once
        // per tick here (the same owning-thread publish as measuredVx); NaN until the
        // first delta ⇒ the kernel's conservative 30°/tick floor.
        double yawRate = session.advanceYawRate(location.getYaw());

        return viewBuilder.build(
                id, player.getEntityId(),
                session.ledger().current(), grounded, slipperiness,
                gravity, Decay.JUMP_IMPULSE, jumpBoostAmplifier(player),
                player.isSprinting(), player.getGameMode() == GameMode.CREATIVE, player.getWorld().getPVP(),
                player.getNoDamageTicks(), player.getMaximumNoDamageTicks(),
                knockbackResistance, ocmOwnsMelee, profile, Pings.of(player), kinematics,
                moveSpeedAttr, session.comboAttackerId(),
                measuredVx, measuredVz, location.getYaw(), player.getEyeHeight(), groundedTicks,
                yawRate);
    }

    /**
     * The combo per-tick sweep (combo-hold §3.1) — run on the owning thread before
     * the view is built so the publish reflects this tick's state. Zero-touch when
     * NO combo keeper is active (both the pocket servo and the reach handicap off):
     * no tracker exists, no work is done. Reconciles the tracker's presence with the
     * keeper count (installing lazily once a keeper retains, dropping with one
     * DISABLED end when the last releases), then feeds the detector this tick's
     * grounded flag and pair separation and fires any transition.
     */
    private void driveCombo(Player player, CombatSession session, boolean combatGrounded) {
        ComboTracker tracker = session.comboTracker();
        if (comboKeepers.get() <= 0) {
            if (tracker != null) {
                comboEvents.fire(player, tracker.reset(ComboEndReason.DISABLED));
                session.clearComboTracker();
            }
            return;
        }
        ComboRules rules = comboRules();
        if (tracker == null) {
            tracker = session.installComboTracker(rules);
        } else if (!tracker.rules().equals(rules)) {
            // A reload changed the detector thresholds: end any active combo cleanly
            // and rebuild with the new rules (rare — a deliberate admin action).
            comboEvents.fire(player, tracker.reset(ComboEndReason.RETIRED));
            tracker = session.installComboTracker(rules);
        }
        // The grounded-run end reads the combat grounded truth (the packetless
        // physical fallback), NOT the raw isOnGround() it used to read here — a
        // packetless victim's flag lies airborne forever on the 1.9/1.10 NMS, so
        // the run never accumulated and a held combo never released.
        double separation = separationTo(player.getUniqueId(), tracker.activeAttacker());
        comboEvents.fire(player, tracker.onTick(clock.current(), combatGrounded, separation));
    }

    /**
     * Horizontal separation between the victim and {@code attackerId} from the
     * position ring (≤1 tick stale — the ring writes once per owning-thread tick),
     * or {@link Double#NaN} when the attacker is unknown or either sample is
     * absent. NaN never triggers the blowout end, so an unresolved pair simply
     * does not blow out.
     */
    private double separationTo(UUID victimId, UUID attackerId) {
        if (attackerId == null) {
            return Double.NaN;
        }
        PositionRing.Sample victim = positions.latest(victimId);
        PositionRing.Sample attacker = positions.latest(attackerId);
        if (victim == null || attacker == null) {
            return Double.NaN;
        }
        double dx = attacker.x() - victim.x();
        double dz = attacker.z() - victim.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The live combo detector thresholds from the snapshot (picked up on reload). */
    @SuppressWarnings("unchecked")
    private ComboRules comboRules() {
        ComboSettings settings = snapshot.get().settings(
                (SettingsKey<ComboSettings>) Feature.COMBO_HOLD.settingsKey());
        return settings.rules();
    }

    private static String blockUnderFeet(Player player, Location location) {
        // Fed straight into GroundFriction.of without LegacyMaterialNames.modernize on
        // purpose: the only blocks the friction table names — ICE, PACKED_ICE,
        // FROSTED_ICE, SLIME_BLOCK — carry the SAME enum-constant name on every
        // supported version (verified on 1.9.4/1.12.2), and BLUE_ICE simply does not
        // exist pre-1.13 (it falls through the table's default there, correctly). So
        // this feed is an exact identity across the flattening — normalizing it would
        // only imply block names need translation, which they do not.
        return player.getWorld().getBlockAt(
                location.getBlockX(), location.getBlockY() - 1, location.getBlockZ())
                .getType().name();
    }

    /**
     * The 0-based Jump Boost amplifier, or a negative sentinel when none is
     * active. The effect's registry spelling changed across the range
     * ({@code JUMP} → {@code JUMP_BOOST}); {@code getName()} is the accessor
     * present on every supported version, so both spellings are matched.
     */
    @SuppressWarnings("deprecation") // getName() is the cross-version-stable spelling
    private static int jumpBoostAmplifier(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            try {
                String name = effect.getType().getName();
                if ("JUMP".equalsIgnoreCase(name) || "JUMP_BOOST".equalsIgnoreCase(name)) {
                    return effect.getAmplifier();
                }
            } catch (Throwable ignored) {
                // Name resolution differs across versions; absence is the safe answer.
            }
        }
        return -1;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
