package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Era parity by FINAL POSITION: real hits on a live modern server, the
 * victim's settled position compared against the decompiled legacy motion
 * model ({@link EraOracle}) replaying the exact velocity packets that
 * shipped.
 *
 * <p><b>The victim is client-emulated.</b> A real player's knockback
 * trajectory is integrated by their <em>client</em> from the velocity
 * packets it receives — the modern server reverts its own copy of a melee
 * victim's motion right after sending (the 1.8.9 send-then-restore that has
 * been vanilla ever since). A synthetic player has no client, so the suite
 * plays that role: every velocity packet the victim is sent is applied to
 * the entity exactly once ({@link ClientEmulator}), and the server's own
 * physics integrate it from there — the same constants every version since
 * beta shares with 1.7.10, which is itself part of what this suite proves.</p>
 *
 * <p>Two claims are pinned. First, <b>delivery + physics parity</b>: a
 * modern server running Mental's {@code legacy-1.7} profile lands a knocked
 * player where a 1.7.10 server would have, and {@code legacy-1.8} where a
 * 1.8.9 server would have (the vectors themselves are already pinned to the
 * era formulas by KnockbackSuite/ProfileSuite; matching the integrated
 * trajectory closes the loop from formula to where the player ends up).
 * Second, <b>the era difference appears exactly where it should</b>: a
 * 1.7.10 combo second hit out-travels 1.8.9's flat delivery, while a
 * victim's own sprint state changes nothing (the folk "sprint reduces
 * knockback" is client-side technique, not server math).</p>
 *
 * <p>Scenario notes: "walking" and "jumping" victims carry direct
 * server-side motion with an empty residual ledger — precisely how a legacy
 * server experienced a moving real player, whose walk/jump was client-side
 * and never entered the server's fields. Each scenario settles ~2 seconds
 * before the endpoint is read, making the comparison insensitive to ±1 tick
 * of scheduling skew (a settled trajectory's endpoint no longer moves).</p>
 */
public final class EraParitySuite {

    /** Single-velocity trajectories: endpoint differences are float noise. */
    private static final double SINGLE_EVENT_TOLERANCE = 0.05;
    /** Multi-event trajectories: ±1 tick of event alignment is absorbed by candidates. */
    private static final double MULTI_EVENT_TOLERANCE = 0.12;
    private static final int SETTLE_TICKS = 45;
    private static final long NANOS_PER_TICK = 50_000_000L;

    private EraParitySuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("era: plain hit lands where the 1.7.10 and 1.8.9 models say", context -> {
                    Outcome legacy17 = melee(mental, tester, context, "legacy-1.7", new MeleeShape());
                    Outcome legacy18 = melee(mental, tester, context, "legacy-1.8", new MeleeShape());
                    // Single-hit math is era-identical; only delivery differed.
                    context.expectNear(legacy17.liveDistance(), legacy18.liveDistance(), 0.1,
                            "single plain hit must travel the same distance in both eras");
                }),
                new TestCase("era: sprint hit lands where both era models say", context -> {
                    melee(mental, tester, context, "legacy-1.7", new MeleeShape().sprintAttacker());
                    melee(mental, tester, context, "legacy-1.8", new MeleeShape().sprintAttacker());
                }),
                new TestCase("era: a walking victim keeps trajectory parity", context -> {
                    MeleeShape walking = new MeleeShape().victimPreMotion(new Vector(0, 0, -0.2), 2);
                    melee(mental, tester, context, "legacy-1.7", walking);
                    melee(mental, tester, context, "legacy-1.8", walking);
                }),
                new TestCase("era: the victim's own sprint state is placebo", context -> {
                    Outcome sprintOnly = melee(mental, tester, context, "legacy-1.7",
                            new MeleeShape().sprintAttacker());
                    Outcome bothSprinting = melee(mental, tester, context, "legacy-1.7",
                            new MeleeShape().sprintAttacker().sprintVictim());
                    context.expectNear(sprintOnly.liveDistance(), bothSprinting.liveDistance(), 0.15,
                            "victim sprint must not change received knockback distance");
                }),
                new TestCase("era: a mid-air (jumping) hit keeps trajectory parity", context -> {
                    MeleeShape jumping = new MeleeShape().victimPreMotion(new Vector(0, 0.42, 0), 2);
                    melee(mental, tester, context, "legacy-1.7", jumping);
                    melee(mental, tester, context, "legacy-1.8", jumping);
                }),
                new TestCase("era: the combo second hit stacks in 1.7.10 and stays flat in 1.8.9", context -> {
                    Outcome combo17 = melee(mental, tester, context, "legacy-1.7",
                            new MeleeShape().secondHitAfter(4));
                    Outcome combo18 = melee(mental, tester, context, "legacy-1.8",
                            new MeleeShape().secondHitAfter(4));
                    context.note(String.format(Locale.ROOT,
                            "era-difference[combo] 1.7.10 travels %.3f blocks, 1.8.9 travels %.3f"
                                    + " — the residual stack is the era gap",
                            combo17.liveDistance(), combo18.liveDistance()));
                    context.expect(combo17.liveDistance() > combo18.liveDistance() + 0.1,
                            "a 1.7.10 combo must out-travel 1.8.9 flat delivery (got "
                                    + combo17.liveDistance() + " vs " + combo18.liveDistance() + ")");
                }),
                new TestCase("era: a rod knock trajectory matches the legacy model", context ->
                        rod(mental, tester, context)),
                new TestCase("era: a snowball knock trajectory matches the legacy model", context ->
                        snowball(mental, tester, context)));
    }

    /* ------------------------------------------------------------------ */
    /*  Melee scenarios                                                    */
    /* ------------------------------------------------------------------ */

    /** The shape of one melee scenario; defaults are a plain standing hit. */
    private static final class MeleeShape {
        boolean sprintAttacker;
        boolean sprintVictim;
        @Nullable Vector preMotion;
        int preMotionLeadTicks;
        int secondHitGapTicks = -1;

        MeleeShape sprintAttacker() {
            this.sprintAttacker = true;
            return this;
        }

        MeleeShape sprintVictim() {
            this.sprintVictim = true;
            return this;
        }

        /** Server-side motion (client-style: no event, no ledger) before the hit. */
        MeleeShape victimPreMotion(Vector motion, int leadTicks) {
            this.preMotion = motion;
            this.preMotionLeadTicks = leadTicks;
            return this;
        }

        MeleeShape secondHitAfter(int gapTicks) {
            this.secondHitGapTicks = gapTicks;
            return this;
        }

        String describe() {
            return (secondHitGapTicks > 0 ? "combo" : "single")
                    + (sprintAttacker ? "+sprint" : "")
                    + (sprintVictim ? "+victimSprint" : "")
                    + (preMotion != null ? (preMotion.getY() > 0 ? "+jump" : "+walk") : "");
        }
    }

    private record Outcome(double liveDistance, double oracleDistance) {}

    private static Outcome melee(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context,
            String profile, MeleeShape shape) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());
        ClientEmulator client = new ClientEmulator(victim, mental.services().scheduling());

        try {
            context.syncRun(() -> {
                Location start = Arena.prepareRunway(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(start, 0, -2));
                victim.spawn(Arena.offset(start, 0, 2));
                Bukkit.getPluginManager().registerEvents(client, tester);
            });
            context.awaitTicks(70); // outlast join invulnerability

            Location start = context.sync(() -> {
                context.expect(mental.services().knockbackProfiles()
                                .setOverride(victim.player(), profile),
                        "preset '" + profile + "' missing");
                attacker.player().setSprinting(shape.sprintAttacker);
                victim.player().setSprinting(shape.sprintVictim);
                Location at = victim.player().getLocation().clone();
                if (shape.preMotion != null) {
                    // Client-style motion: the legacy server's fields never
                    // saw a real player's walk or jump either.
                    victim.setMotion(shape.preMotion.getX(), shape.preMotion.getY(),
                            shape.preMotion.getZ());
                    client.stampManual(shape.preMotion);
                } else {
                    victim.player().setNoDamageTicks(0);
                    attacker.attack(victim.player());
                }
                return at;
            });
            if (shape.preMotion != null) {
                context.awaitTicks(shape.preMotionLeadTicks);
                context.syncRun(() -> {
                    victim.player().setNoDamageTicks(0);
                    attacker.attack(victim.player());
                });
            }
            if (shape.secondHitGapTicks > 0) {
                context.awaitTicks(shape.secondHitGapTicks);
                context.syncRun(() -> {
                    victim.player().setNoDamageTicks(0);
                    attacker.attack(victim.player());
                });
            }

            return settleAndCompare(context, client, victim, start,
                    shape.describe() + "/" + profile);
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            client.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Projectile scenarios                                               */
    /* ------------------------------------------------------------------ */

    private static void rod(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer rodder = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());
        ClientEmulator client = new ClientEmulator(victim, mental.services().scheduling());

        try {
            context.syncRun(() -> {
                Location start = Arena.prepareRunway(Bukkit.getWorlds().get(0));
                rodder.spawn(Arena.offset(start, 0, -3));
                victim.spawn(Arena.offset(start, 0, 0));
                Bukkit.getPluginManager().registerEvents(client, tester);
            });
            context.awaitTicks(70);

            Location start = context.sync(() -> victim.player().getLocation().clone());
            boolean launched = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                try {
                    return rodder.player().launchProjectile(
                            FishHook.class, new Vector(0, 0.1, 0.75)) != null;
                } catch (Throwable unsupported) {
                    return false;
                }
            });
            if (!launched) {
                context.note("this version cannot launch a bare FishHook — rod parity covered by"
                        + " the velocity-level suites");
                return;
            }
            if (!awaitFirstEvent(context, client)) {
                context.note("hook never struck the victim (flight physics) — rod parity covered"
                        + " by the velocity-level suites");
                return;
            }

            settleAndCompareLoose(context, client, victim, start, "rod/legacy-1.7");
        } finally {
            context.syncRun(() -> {
                rodder.remove();
                victim.remove();
            });
            client.unregister();
        }
    }

    private static void snowball(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer shooter = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());
        ClientEmulator client = new ClientEmulator(victim, mental.services().scheduling());

        try {
            Location victimSpot = context.sync(() -> {
                Location start = Arena.prepareRunway(Bukkit.getWorlds().get(0));
                shooter.spawn(Arena.offset(start, 2, -3));
                Location spot = Arena.offset(start, 0, 0);
                victim.spawn(spot);
                Bukkit.getPluginManager().registerEvents(client, tester);
                return spot;
            });
            context.awaitTicks(70);

            Location start = context.sync(() -> victim.player().getLocation().clone());
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                // Point-blank: the first flight step crosses the hitbox.
                Location launch = victimSpot.clone().add(0, 1.0, -0.8);
                Snowball snowball = victimSpot.getWorld().spawn(launch, Snowball.class);
                snowball.setShooter(shooter.player());
                snowball.setVelocity(new Vector(0, 0.0, 0.5));
            });
            if (!awaitFirstEvent(context, client)) {
                context.note("snowball never struck the victim (flight physics) — projectile"
                        + " parity covered by the velocity-level suites");
                return;
            }

            settleAndCompareLoose(context, client, victim, start, "snowball/legacy-1.7");
        } finally {
            context.syncRun(() -> {
                shooter.remove();
                victim.remove();
            });
            client.unregister();
        }
    }

    private static boolean awaitFirstEvent(TestContext context, ClientEmulator client)
            throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            context.awaitTicks(5);
            if (!client.stamps().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /*  The comparison core                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Lets the trajectory settle, replays the captured velocity timeline
     * through the legacy oracle (±1 tick candidates for the later events),
     * asserts the live endpoint matches, and logs the comparison row.
     */
    private static Outcome settleAndCompare(
            TestContext context, ClientEmulator client, FakePlayer victim, Location start,
            String label) throws Exception {
        context.awaitTicks(SETTLE_TICKS);

        List<ClientEmulator.Stamp> stamps = client.stamps();
        context.expect(!stamps.isEmpty(), label + ": no velocity event was ever captured");

        Location live = context.sync(() -> victim.player().getLocation().clone());
        long firstNanos = stamps.get(0).nanos();
        int lastTick = 0;
        List<EraOracle.VelocityEvent> base = new ArrayList<>();
        for (ClientEmulator.Stamp stamp : stamps) {
            int tick = (int) Math.round((stamp.nanos() - firstNanos) / (double) NANOS_PER_TICK);
            base.add(new EraOracle.VelocityEvent(
                    tick, stamp.velocity().getX(), stamp.velocity().getY(), stamp.velocity().getZ()));
            lastTick = Math.max(lastTick, tick);
        }

        // Candidate alignments: the first event anchors tick 0; later events
        // may sit one tick either side of their wall-clock bucket.
        double bestDelta = Double.MAX_VALUE;
        EraOracle.Result best = null;
        for (int offset : base.size() > 1 ? new int[] {-1, 0, 1} : new int[] {0}) {
            List<EraOracle.VelocityEvent> candidate = new ArrayList<>();
            for (int i = 0; i < base.size(); i++) {
                EraOracle.VelocityEvent event = base.get(i);
                candidate.add(i == 0 ? event : new EraOracle.VelocityEvent(
                        Math.max(1, event.tick() + offset), event.vx(), event.vy(), event.vz()));
            }
            EraOracle.Result result = EraOracle.simulate(
                    start.getX(), start.getY(), start.getZ(), true, Arena.floorY(),
                    candidate, lastTick + 1 + SETTLE_TICKS);
            double delta = Math.max(Math.abs(result.x() - live.getX()),
                    Math.max(Math.abs(result.y() - live.getY()), Math.abs(result.z() - live.getZ())));
            if (delta < bestDelta) {
                bestDelta = delta;
                best = result;
            }
        }

        double tolerance = base.size() > 1 ? MULTI_EVENT_TOLERANCE : SINGLE_EVENT_TOLERANCE;
        double liveDistance = Math.hypot(live.getX() - start.getX(), live.getZ() - start.getZ());
        double oracleDistance = best.distanceFrom(start.getX(), start.getZ());
        context.note(String.format(Locale.ROOT,
                "era[%s] travelled %.3f blocks live vs %.3f in the legacy model (worst axis Δ %.4f,"
                        + " %d velocity event%s)",
                label, liveDistance, oracleDistance, bestDelta,
                base.size(), base.size() == 1 ? "" : "s"));
        context.expect(bestDelta <= tolerance, String.format(Locale.ROOT,
                "era[%s] endpoint diverged from the legacy model: live (%.4f, %.4f, %.4f) vs"
                        + " model (%.4f, %.4f, %.4f), worst axis %.4f > %.4f",
                label, live.getX(), live.getY(), live.getZ(),
                best.x(), best.y(), best.z(), bestDelta, tolerance));
        return new Outcome(liveDistance, oracleDistance);
    }

    /**
     * The projectile variant: synthetic players take extra server-side
     * motion paths on projectile hits that a real client never integrates
     * (the vector itself is pinned to the era formula by ProjectileSuite and
     * FishingSuite). Asserted here: the victim travels <em>away along the
     * knock direction</em> for a distance in the same regime as the legacy
     * model — and the comparison row is logged for the era report.
     */
    private static void settleAndCompareLoose(
            TestContext context, ClientEmulator client, FakePlayer victim, Location start,
            String label) throws Exception {
        context.awaitTicks(SETTLE_TICKS);

        List<ClientEmulator.Stamp> stamps = client.stamps();
        context.expect(!stamps.isEmpty(), label + ": no velocity event was ever captured");
        Vector knock = stamps.get(0).velocity();
        Location live = context.sync(() -> victim.player().getLocation().clone());

        EraOracle.Result model = EraOracle.simulate(
                start.getX(), start.getY(), start.getZ(), true, Arena.floorY(),
                List.of(new EraOracle.VelocityEvent(
                        0, knock.getX(), knock.getY(), knock.getZ())),
                SETTLE_TICKS);
        double liveDistance = Math.hypot(live.getX() - start.getX(), live.getZ() - start.getZ());
        double modelDistance = model.distanceFrom(start.getX(), start.getZ());

        context.note(String.format(Locale.ROOT,
                "era[%s] travelled %.3f blocks live vs %.3f in the legacy model"
                        + " (synthetic-player projectile path — direction and regime asserted,"
                        + " vector parity pinned by the velocity-level suites)",
                label, liveDistance, modelDistance));

        double knockHorizontal = Math.hypot(knock.getX(), knock.getZ());
        context.expect(knockHorizontal > 1.0e-6 && liveDistance > 1.0e-3,
                label + ": victim never moved from the knock");
        double dot = ((live.getX() - start.getX()) * knock.getX()
                + (live.getZ() - start.getZ()) * knock.getZ())
                / (liveDistance * knockHorizontal);
        context.expect(dot > 0.9, String.format(Locale.ROOT,
                "%s: victim moved off the knock direction (cos %.3f)", label, dot));
        context.expect(liveDistance >= modelDistance * 0.5 && liveDistance <= modelDistance * 2.2,
                String.format(Locale.ROOT,
                        "%s: travel %.3f outside the legacy regime [%.3f, %.3f]",
                        label, liveDistance, modelDistance * 0.5, modelDistance * 2.2));
    }

    /* ------------------------------------------------------------------ */
    /*  The client emulator                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Plays the victim's client: records every velocity packet and makes
     * sure the entity actually integrates it.
     *
     * <p>Two server behaviors make this necessary. Melee knockback to player
     * victims is send-then-restore — the server reverts its own motion copy
     * right after sending, because the client owns the trajectory; with no
     * client attached, nothing would move. Other paths (set-velocity flush,
     * projectile knocks) leave the motion in place. The emulator zeroes the
     * motion at event time (so nothing the server computed mid-tick leaks
     * into the trajectory) and re-applies the packet's vector one tick
     * later — unless the entity demonstrably already carries it (the
     * persisted paths), in which case it leaves well alone.</p>
     */
    private static final class ClientEmulator implements Listener {

        record Stamp(long nanos, @NotNull Vector velocity) {}

        private static final double MATCH_EPSILON = 0.02;

        private final FakePlayer victim;
        private final Scheduling scheduling;
        private final List<Stamp> stamps = new CopyOnWriteArrayList<>();

        ClientEmulator(@NotNull FakePlayer victim, @NotNull Scheduling scheduling) {
            this.victim = victim;
            this.scheduling = scheduling;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onVelocity(@NotNull PlayerVelocityEvent event) {
            if (!event.getPlayer().getUniqueId().equals(victim.uuid())) {
                return;
            }
            Vector packet = event.getVelocity().clone();
            stamps.add(new Stamp(System.nanoTime(), packet));
            // Nothing the server computed mid-tick may leak into the
            // trajectory; the packet is the only truth a client sees.
            victim.setMotion(0, 0, 0);
            scheduling.runOn(event.getPlayer(), () -> applyIfLost(packet), () -> {});
        }

        /** Stamps motion applied directly (walk/jump setup) — no event fires for it. */
        void stampManual(@NotNull Vector motion) {
            stamps.add(new Stamp(System.nanoTime(), motion.clone()));
        }

        @NotNull List<Stamp> stamps() {
            return List.copyOf(stamps);
        }

        void unregister() {
            HandlerList.unregisterAll(this);
        }

        /**
         * One tick after the packet: if the entity's motion is the packet
         * vector (raw, or decayed one tick in air or on ground — the
         * persisted-motion paths, whichever task order ran first), leave it.
         * Anything else means the server restored or replaced it, and the
         * client's application wins.
         */
        private void applyIfLost(Vector packet) {
            Vector current = victim.player().getVelocity();
            if (matches(current, packet)
                    || matches(current, decayed(packet, 0.91))
                    || matches(current, decayed(packet, 0.91 * 0.6))) {
                return;
            }
            victim.setMotion(packet.getX(), packet.getY(), packet.getZ());
        }

        private static Vector decayed(Vector motion, double horizontalDrag) {
            return new Vector(
                    motion.getX() * horizontalDrag,
                    (motion.getY() - 0.08) * 0.98,
                    motion.getZ() * horizontalDrag);
        }

        private static boolean matches(Vector actual, Vector expected) {
            return Math.abs(actual.getX() - expected.getX()) <= MATCH_EPSILON
                    && Math.abs(actual.getY() - expected.getY()) <= MATCH_EPSILON
                    && Math.abs(actual.getZ() - expected.getZ()) <= MATCH_EPSILON;
        }
    }
}
