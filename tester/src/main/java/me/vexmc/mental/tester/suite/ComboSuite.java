package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import me.vexmc.mental.api.event.ComboEndEvent;
import me.vexmc.mental.api.event.ComboStartEvent;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.math.PocketServoConfig;
import me.vexmc.mental.kernel.math.PredictorInputs;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.feature.combo.ComboPredictor;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * The pocket servo end to end on a live server (combo-hold §5). Every assertion
 * is journal-based: the desk records the {@code comboFactor} it applied on every
 * ship, so the servo's effect is read from the "what did we ship" seam rather
 * than a wire capture. The signature preset is selected and combo-hold enabled
 * through the real overlay path; each scenario uses fresh players (the residual
 * ledger contaminates across hits) and restores the defaults in its finally.
 *
 * <p><b>Clientless-fake reality (the load-bearing caveat).</b> A fake victim is
 * clientless: a melee knock is send-then-restore, so it never moves, and under the
 * honest combat-ground feed a stationary fake reads grounded EVERY tick on every
 * tier (the {@code CombatGround} authority resolves a motionless on-the-floor fake
 * as grounded whether or not its raw {@code isOnGround()} flag agrees). Two
 * consequences the scenarios design around: (1) the chain scenarios widen {@code
 * grounded-run-ticks}/{@code max-gap-ticks} via the overlay so a stationary victim
 * does not ground-/gap-out mid-chain — the servo value is what we measure, not the
 * end conditions; (2) the grounded-end scenario keeps the REAL threshold and instead
 * stages the ground state physically — it floats the victim (a per-tick upward
 * server-side motion) through the build-up so the run cannot accrue while the combo
 * forms, then lands it and idles so the honest feed accumulates the genuine
 * touchdown. The servo VALUE itself is unit-pinned exactly in PocketServoTest; a
 * combo's real FEEL is field-judged (SimpleBoxer), per §5's known limit.</p>
 *
 * <p><b>One-hit publish latency.</b> The detector is fed at the delivery fold and
 * the servo reads the frozen view, so a hit's σ uses the combo state published on
 * the PREVIOUS tick. The chain therefore activates on the third shipped hit and
 * the first servo-valued hit is the next one computed after that publish. The
 * scenarios assert the opener hits are 1.0 and that an established-combo hit is
 * servo-valued and matches the same solve the engine runs — they do not pin a
 * single ordinal, which the latency makes brittle.</p>
 */
public final class ComboSuite {

    /** Tolerance for a journaled σ against the re-run engine solve (same inputs, deterministic). */
    private static final double SIGMA_EPSILON = 1.0e-6;
    /** Tolerance for a byte-identity vector compare (era stamps are exact; float slack only). */
    private static final double VECTOR_EPSILON = 1.0e-9;
    private static final double SERVO_MIN = 0.8;
    private static final double SERVO_MAX = 1.2;
    private static final String DEFAULT_PROFILE = "legacy-1.7";
    /** Ticks between chain hits — below the default grounded-run so a stationary fake never grounds out. */
    private static final int CADENCE_TICKS = 8;

    private ComboSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("combo: a same-attacker chain scales the fresh knock from the active hit",
                        context -> runChainScenario(mental, tester, context)),
                new TestCase("combo: the victim retaliating flips the next knock back to 1.0",
                        context -> runRetaliationScenario(mental, tester, context)),
                new TestCase("combo: a grounded run flips the next knock back to 1.0",
                        context -> runGroundedScenario(mental, tester, context)),
                new TestCase("combo: ZERO-TOUCH — module off ships every knock at comboFactor 1.0, era-identical",
                        context -> runZeroTouchScenario(mental, tester, context)),
                new TestCase("combo: the api start/end events fire with reasons",
                        context -> runApiEventScenario(mental, tester, context)));
    }

    /* --------------------------- scenario 1: the chain --------------------------- */

    private static void runChainScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            setUp(mental, context, attacker, victim, /*holdOpenChain=*/ true);

            // Drive the openers; each ship's comboFactor is read from the journal.
            List<Double> factors = new ArrayList<>();
            for (int hit = 0; hit < 4; hit++) {
                factors.add(driveHitFactor(mental, context, attacker, victim));
                context.awaitTicks(CADENCE_TICKS);
            }
            context.expectNear(1.0, factors.get(0), SIGMA_EPSILON, "opener hit 1 must ship at comboFactor 1.0");
            context.expectNear(1.0, factors.get(1), SIGMA_EPSILON, "opener hit 2 must ship at comboFactor 1.0");

            // The combo is active and published now — the next hit is servo-valued.
            // Derive the expected σ from the SAME engine solve over the SAME precision
            // inputs the region path builds (combo-hold §3.2b) — captured in the same
            // sync tick as the attack, so the ring/view state is identical. The
            // stationary clientless fakes carry ~0 measured drift/chase and no probed
            // RTT, so a fresh LatencyModel matches the production model for them.
            double[] expected = new double[1];
            int shipsBefore = context.sync(() -> {
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                PlayerView view = session.view();
                context.expect(view != null && attacker.uuid().equals(view.comboAttackerId()),
                        "the combo must be active on the attacker before the measured hit");
                KnockbackProfile profile = KnockbackSuite.profileFor(mental, victim);
                EntityState attackerState = EntityStates.capture(attacker.player());
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                PocketServoConfig servo = comboSettings(mental).servo();
                PredictorInputs inputs = ComboPredictor.build(
                        attacker.uuid(), victim.uuid(),
                        attackerState.x(), attackerState.z(), victimState.x(), victimState.z(),
                        view, mental.sessions().viewOf(attacker.uuid()),
                        mental.sessions().positions(), new LatencyModel());
                // D-8: the java.util.Random overload keeps a jvmdowngrader RandomGenerator
                // stub type out of this cross-jar call's descriptor (a RandomGenerator descriptor
                // resolves a mismatched per-plugin stub and NoSuchMethodErrors on the v52 tier).
                // The seed is fixed so the resistance roll is deterministic against the desk's solve.
                expected[0] = KnockbackEngine.computePaced(
                        attackerState, victimState, profile, null, new Random(0L), false, servo, inputs)
                        .comboFactor();
                victim.player().setNoDamageTicks(0);
                int before = countShips(mental, victim);
                attacker.attack(victim.player());
                return before;
            });
            JournalEntry ship = awaitNewShip(context, mental, victim, shipsBefore);
            context.expect(ship != null && ship.shipped() != null, "the active-combo hit journaled no SHIP");
            double sigma = ship.comboFactor();
            context.expect(sigma >= SERVO_MIN - 1e-9 && sigma <= SERVO_MAX + 1e-9,
                    "the servo factor must sit inside the [0.8, 1.2] honesty clamps (got " + sigma + ")");
            context.expect(Math.abs(sigma - 1.0) > 1e-6,
                    "an active-combo hit must be servo-shaped, not 1.0 (got " + sigma + ")");
            context.expectNear(expected[0], sigma, SIGMA_EPSILON,
                    "the journaled σ must equal the production solve on the same inputs");
        } finally {
            teardown(mental, context, attacker, victim);
        }
    }

    /* ----------------------- scenario 2: retaliation ends it ---------------------- */

    private static void runRetaliationScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            setUp(mental, context, attacker, victim, true);
            buildActiveCombo(mental, context, attacker, victim);

            // The victim lands a hit of their own — retaliation ends the combo.
            context.syncRun(() -> {
                attacker.player().setNoDamageTicks(0);
                victim.attack(attacker.player());
            });
            context.awaitTicks(CADENCE_TICKS);

            // The next attacker knock must ship back at 1.0 (combo gone).
            double factor = driveHitFactor(mental, context, attacker, victim);
            context.expectNear(1.0, factor, SIGMA_EPSILON,
                    "after the victim retaliates the next knock must be era-plain (1.0)");
        } finally {
            teardown(mental, context, attacker, victim);
        }
    }

    /* ------------------------ scenario 3: a grounded run ends it ------------------- */

    /**
     * The genuine touchdown end (combo-hold §3.1): after a real grounded run of
     * {@code groundedRunTicks} the held combo releases and the next knock is
     * era-plain again. This case exercises the REAL threshold (10, un-widened) —
     * that is its whole point — which forces it to stage the victim's ground state
     * physically rather than lean on a widened window.
     *
     * <p><b>Why the physical staging.</b> A clientless fake never moves from a melee
     * knock (send-then-restore), so under the honest combat-ground feed a stationary
     * victim reads grounded EVERY tick — and the real 10-tick run (< the 3× build-up
     * cadence would let it survive to) would fire the grounded end mid-build-up, so
     * the combo would never publish active. Production semantics are correct here: a
     * real victim is launched airborne by each knock, resetting the run; only the
     * motionless fake sits grounded forever. So the scenario reproduces the real
     * physics: it floats the victim clear of the platform for the build-up (the run
     * cannot accrue while airborne), confirms the combo activates, THEN lands it and
     * idles past the threshold so the honest feed accumulates the run and the
     * {@code GROUNDED} end fires — the same assertion the case always made.</p>
     */
    private static void runGroundedScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            // The REAL grounded-run (10), NOT widened — the touchdown is what we
            // exercise. Keep only the gap window wide so the gap never expires first.
            setUp(mental, context, attacker, victim, /*holdOpenChain=*/ false);
            configureRules(mental, context, /*groundedRun=*/ 10, /*maxGap=*/ 400);

            // The victim's settled on-platform position — the anchor we ground it back
            // onto after the airborne build-up.
            Location groundLoc = context.sync(() -> victim.player().getLocation().clone());

            // ── build-up airborne ────────────────────────────────────────────────
            // Physically float the victim so the honest feed reads airborne every tick
            // (no support within the foot epsilon; a rising fake's raw isOnGround()
            // flag is false on modern too, so BOTH tiers take the same honest path).
            // A per-tick upward server-side motion is the era suite's own owning-thread
            // client stand-in (Folia-safe — no teleport-in-region). While airborne the
            // grounded run never accrues, so the combo builds and STAYS active.
            context.syncRun(() -> victim.preTick(() -> victim.setMotion(0.0, 0.42, 0.0)));
            context.awaitTicks(3); // clear the floor before the first hit

            buildActiveCombo(mental, context, attacker, victim);

            // ── touchdown ────────────────────────────────────────────────────────
            // Land it: stop lifting, pin the vertical to a settled rest (kills the
            // upward residual so it settles at once), and place it back on the
            // platform. From here the honest feed reads grounded every tick, so the
            // detector's grounded-run counter accrues to the real threshold.
            context.syncRun(() -> {
                victim.preTick(() -> victim.setMotion(0.0, 0.0, 0.0));
                teleportOnOwningThread(mental, victim, groundLoc);
            });
            // Idle well past grounded-run-ticks (10) so the touchdown end fires.
            context.awaitTicks(20);

            // The grounded run must have RELEASED the combo before the measured hit —
            // the direct proof that the touchdown, not some other end, did the work.
            boolean released = context.sync(() -> {
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                PlayerView view = session == null ? null : session.view();
                return view != null && view.comboAttackerId() == null;
            });
            context.expect(released, "the grounded run must have ended the combo before the next hit");

            double factor = driveHitFactor(mental, context, attacker, victim);
            context.expectNear(1.0, factor, SIGMA_EPSILON,
                    "after a grounded run the next knock must be era-plain (1.0)");
        } finally {
            context.syncRun(() -> victim.preTick(null));
            teardown(mental, context, attacker, victim);
        }
    }

    /**
     * Teleports {@code fake} on its owning thread — the sync teleport on Paper (the
     * main thread owns the entity, and it is the form that exists down to 1.9.4),
     * the async teleport on Folia (a region-thread sync teleport is banned). Mirrors
     * {@code FakePlayer.spawn}; call inside a global/owning-thread hop.
     */
    private static void teleportOnOwningThread(MentalPluginV5 mental, FakePlayer fake, Location location) {
        if ("folia".equals(mental.scheduling().describe())) {
            fake.player().teleportAsync(location);
        } else {
            fake.player().teleport(location);
        }
    }

    /* --------------------------- scenario 4: zero-touch --------------------------- */

    private static void runZeroTouchScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            // Signature profile, module OFF (never enabled here).
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            context.syncRun(() -> {
                mental.overlaySet("modules.combo-hold", false);
                mental.reloadAll();
                context.expect(mental.management().setGlobalProfile("signature"), "signature preset missing");
                context.expect(!mental.featureActive(Feature.COMBO_HOLD), "combo-hold must be OFF for zero-touch");
            });
            context.awaitTicks(3);

            // A full chain: every ship's comboFactor is exactly 1.0 AND the shipped
            // vector equals the era stamp the engine computes with no servo (the
            // "byte-identical to the chain without the module" contract).
            for (int hit = 0; hit < 5; hit++) {
                KnockbackVector[] era = new KnockbackVector[1];
                int shipsBefore = context.sync(() -> {
                    CombatSession session = mental.sessions().sessionFor(victim.uuid());
                    KnockbackProfile profile = KnockbackSuite.profileFor(mental, victim);
                    EntityState attackerState = EntityStates.capture(attacker.player());
                    EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                    // The era stamp with NO servo (the module-off truth).
                    KnockbackVector vector = KnockbackEngine.compute(attackerState, victimState, profile, null);
                    era[0] = SuiteDelivery.melee(vector, profile, victimState.grounded());
                    victim.player().setNoDamageTicks(0);
                    int before = countShips(mental, victim);
                    attacker.attack(victim.player());
                    return before;
                });
                JournalEntry ship = awaitNewShip(context, mental, victim, shipsBefore);
                context.expect(ship != null && ship.shipped() != null, "zero-touch hit journaled no SHIP");
                context.expectNear(1.0, ship.comboFactor(), VECTOR_EPSILON,
                        "module off: every comboFactor must be exactly 1.0 (zero-touch)");
                context.expect(era[0] != null, "engine produced no era stamp");
                context.expectNear(era[0].x(), ship.shipped().x(), VECTOR_EPSILON, "zero-touch shipped x = era stamp");
                context.expectNear(era[0].y(), ship.shipped().y(), VECTOR_EPSILON, "zero-touch shipped y = era stamp");
                context.expectNear(era[0].z(), ship.shipped().z(), VECTOR_EPSILON, "zero-touch shipped z = era stamp");
                context.awaitTicks(CADENCE_TICKS);
            }
        } finally {
            teardown(mental, context, attacker, victim);
        }
    }

    /* ---------------------------- scenario 5: api events -------------------------- */

    private static void runApiEventScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        ComboEventCaptor captor = new ComboEventCaptor();
        try {
            setUp(mental, context, attacker, victim, true);
            context.syncRun(() ->
                    Bukkit.getPluginManager().registerEvents(captor, tester));

            buildActiveCombo(mental, context, attacker, victim);
            context.awaitUntil(() -> !captor.starts.isEmpty(), 40, "a ComboStartEvent");
            context.expect(!captor.starts.isEmpty(), "no ComboStartEvent fired for an active combo");
            context.expect(captor.starts.get(0).getHits() >= 3, "the start event must carry the chain length");
            context.expect(victim.uuid().equals(captor.starts.get(0).getVictim().getUniqueId()),
                    "the start event names the victim");

            // End it by retaliation, then assert the balanced end fired with a reason.
            context.syncRun(() -> {
                attacker.player().setNoDamageTicks(0);
                victim.attack(attacker.player());
            });
            context.awaitUntil(() -> !captor.ends.isEmpty(), 40, "a ComboEndEvent");
            context.expect(!captor.ends.isEmpty(), "no ComboEndEvent fired when the combo ended");
            context.expect(captor.ends.get(0).getReason() == ComboEndEvent.Reason.RETALIATION,
                    "the end reason must be RETALIATION (got " + captor.ends.get(0).getReason() + ")");
        } finally {
            context.syncRun(() -> HandlerList.unregisterAll(captor));
            teardown(mental, context, attacker, victim);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /** Spawns the pair, selects signature, enables combo-hold (widening the chain-hold rules when asked). */
    private static void setUp(
            MentalPluginV5 mental, TestContext context, FakePlayer attacker, FakePlayer victim,
            boolean holdOpenChain) throws Exception {
        context.syncRun(() -> {
            Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
            attacker.spawn(Arena.offset(centre, 0, -2));
            victim.spawn(Arena.offset(centre, 0, 2));
        });
        context.awaitTicks(5);
        context.syncRun(() -> {
            mental.overlaySet("modules.combo-hold", true);
            if (holdOpenChain) {
                // A stationary clientless fake reads grounded every tick, so widen the
                // grounded-run and gap windows well past the chain so neither ends it —
                // we are measuring the servo VALUE, not the end conditions here.
                mental.overlaySet("combo-hold.grounded-run-ticks", 400);
                mental.overlaySet("combo-hold.max-gap-ticks", 400);
            }
            mental.reloadAll();
            context.expect(mental.management().setGlobalProfile("signature"), "signature preset missing");
            context.expect(mental.featureActive(Feature.COMBO_HOLD), "combo-hold must be active");
        });
        context.awaitTicks(3);
    }

    /** Configures explicit detector rules (grounded-run / max-gap) and reloads. */
    private static void configureRules(
            MentalPluginV5 mental, TestContext context, int groundedRun, int maxGap) throws Exception {
        context.syncRun(() -> {
            mental.overlaySet("combo-hold.grounded-run-ticks", groundedRun);
            mental.overlaySet("combo-hold.max-gap-ticks", maxGap);
            mental.reloadAll();
        });
        context.awaitTicks(2);
    }

    /** Drives three same-attacker hits and waits until the view publishes the active combo. */
    private static void buildActiveCombo(
            MentalPluginV5 mental, TestContext context, FakePlayer attacker, FakePlayer victim) throws Exception {
        for (int hit = 0; hit < 3; hit++) {
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitTicks(CADENCE_TICKS);
        }
        context.awaitUntil(() -> {
            try {
                return context.sync(() -> {
                    CombatSession session = mental.sessions().sessionFor(victim.uuid());
                    PlayerView view = session == null ? null : session.view();
                    return view != null && attacker.uuid().equals(view.comboAttackerId());
                });
            } catch (Exception failure) {
                return false;
            }
        }, 40, "the view to publish the active combo");
    }

    /** Drives one attack and returns the comboFactor the desk journaled for its SHIP. */
    private static double driveHitFactor(
            MentalPluginV5 mental, TestContext context, FakePlayer attacker, FakePlayer victim) throws Exception {
        int shipsBefore = context.sync(() -> {
            int before = countShips(mental, victim);
            victim.player().setNoDamageTicks(0);
            attacker.attack(victim.player());
            return before;
        });
        JournalEntry ship = awaitNewShip(context, mental, victim, shipsBefore);
        context.expect(ship != null && ship.shipped() != null, "a staged hit journaled no SHIP");
        return ship.comboFactor();
    }

    @SuppressWarnings("unchecked")
    private static ComboSettings comboSettings(MentalPluginV5 mental) {
        return mental.snapshot().settings(
                (SettingsKey<ComboSettings>) Feature.COMBO_HOLD.settingsKey());
    }

    private static int countShips(MentalPluginV5 mental, FakePlayer victim) {
        CombatSession session = mental.sessions().sessionFor(victim.uuid());
        if (session == null) {
            return 0;
        }
        int ships = 0;
        for (JournalEntry entry : session.desk().journal()) {
            if (entry.shipped() != null) {
                ships++;
            }
        }
        return ships;
    }

    private static JournalEntry awaitNewShip(
            TestContext context, MentalPluginV5 mental, FakePlayer victim, int shipsBefore) throws Exception {
        for (int round = 0; round < 12; round++) {
            JournalEntry ship = context.sync(() -> {
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                if (session == null) {
                    return null;
                }
                int ships = 0;
                JournalEntry lastShip = null;
                for (JournalEntry entry : session.desk().journal()) {
                    if (entry.shipped() != null) {
                        ships++;
                        lastShip = entry;
                    }
                }
                return ships > shipsBefore ? lastShip : null;
            });
            if (ship != null) {
                return ship;
            }
            context.awaitTicks(2);
        }
        return null;
    }

    private static void teardown(
            MentalPluginV5 mental, TestContext context, FakePlayer attacker, FakePlayer victim) throws Exception {
        context.syncRun(() -> {
            mental.overlaySet("modules.combo-hold", false);
            mental.overlaySet("combo-hold.grounded-run-ticks", 10);
            mental.overlaySet("combo-hold.max-gap-ticks", 20);
            mental.reloadAll();
            mental.management().setGlobalProfile(DEFAULT_PROFILE);
            attacker.remove();
            victim.remove();
        });
    }

    /** Captures the api events (MONITOR) for scenario 5. */
    private static final class ComboEventCaptor implements Listener {
        final List<ComboStartEvent> starts = new CopyOnWriteArrayList<>();
        final List<ComboEndEvent> ends = new CopyOnWriteArrayList<>();

        @EventHandler
        public void onStart(ComboStartEvent event) {
            starts.add(event);
        }

        @EventHandler
        public void onEnd(ComboEndEvent event) {
            ends.add(event);
        }
    }
}
