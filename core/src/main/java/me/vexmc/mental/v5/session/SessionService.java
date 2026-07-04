package me.vexmc.mental.v5.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.GroundFriction;
import me.vexmc.mental.kernel.model.KinematicState;
import me.vexmc.mental.kernel.model.LedgerEvent;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.wire.GroundFsm;
import me.vexmc.mental.kernel.wire.PositionRing;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Pings;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.VelocityValve;
import me.vexmc.mental.v5.coexist.OcmBinding;
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

    public SessionService(
            Scheduling scheduling, TickClock clock, ViewBuilder viewBuilder,
            VelocityValve valve, OcmBinding ocmBinding, Supplier<Snapshot> snapshot,
            PositionRing positions, ConnectionDomains domains) {
        this.scheduling = scheduling;
        this.clock = clock;
        this.viewBuilder = viewBuilder;
        this.valve = valve;
        this.ocmBinding = ocmBinding;
        this.snapshot = snapshot;
        this.positions = positions;
        this.domains = domains;
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
        TaskHandle handle = scheduling.repeatOn(player, 1L, 1L, () -> tick(player), () -> forget(id));
        TaskHandle prior = handles.put(id, handle);
        if (prior != null) {
            prior.cancel();
        }
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
        PlayerView view = buildView(player, session);
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
        if (domains.has(id)) {
            return; // real connection — the packet FSM feeds the ledger
        }
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
     * Reads the live player on its owning thread and produces the frozen view.
     * Every read here is owning-thread-legal; the netty realm only ever sees the
     * published result.
     */
    private PlayerView buildView(Player player, CombatSession session) {
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

        return viewBuilder.build(
                id, player.getEntityId(),
                session.ledger().current(), grounded, slipperiness,
                gravity, Decay.JUMP_IMPULSE, jumpBoostAmplifier(player),
                player.isSprinting(), player.getGameMode() == GameMode.CREATIVE, player.getWorld().getPVP(),
                player.getNoDamageTicks(), player.getMaximumNoDamageTicks(),
                knockbackResistance, ocmOwnsMelee, profile, Pings.of(player), kinematics,
                moveSpeedAttr);
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
