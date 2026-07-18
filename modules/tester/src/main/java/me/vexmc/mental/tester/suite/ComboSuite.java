package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import me.vexmc.mental.api.ComboView;
import me.vexmc.mental.api.Mental;
import me.vexmc.mental.api.MentalCombat;
import me.vexmc.mental.api.event.ComboChainAbortEvent;
import me.vexmc.mental.api.event.ComboChainEvent;
import me.vexmc.mental.api.event.ComboEndEvent;
import me.vexmc.mental.api.event.ComboHitEvent;
import me.vexmc.mental.api.event.ComboStartEvent;
import me.vexmc.mental.api.event.KnockbackApplyEvent;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.math.PocketServo;
import me.vexmc.mental.kernel.math.PocketServoConfig;
import me.vexmc.mental.kernel.math.PredictorInputs;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.PlayerView;
import me.vexmc.mental.kernel.model.ResetModel;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.kernel.wire.LatencyModel;
import me.vexmc.mental.kernel.wire.PositionRing;
import me.vexmc.mental.platform.AttributeModifiers;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.config.settings.ReachHandicapSettings;
import me.vexmc.mental.v5.config.settings.HitRegSettings;
import me.vexmc.mental.v5.feature.combo.ComboPredictor;
import me.vexmc.mental.v5.feature.delivery.ReachClamp;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.platform.AttackRangeAdapter;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;
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
 * the PREVIOUS tick. The chain therefore activates on the second shipped hit
 * (minHits 2, the 2.4.5 retune) and the first servo-valued hit is the next one
 * computed after that publish. The
 * scenarios assert the opener hits are 1.0 and that an established-combo hit is
 * servo-valued and matches the same solve the engine runs — they do not pin a
 * single ordinal, which the latency makes brittle.</p>
 */
public final class ComboSuite {

    /** Tolerance for a journaled σ against the re-run engine solve (same inputs, deterministic). */
    private static final double SIGMA_EPSILON = 1.0e-6;
    /** Tolerance for a byte-identity vector compare (era stamps are exact; float slack only). */
    private static final double VECTOR_EPSILON = 1.0e-9;
    private static final double SERVO_MIN = 0.93;
    private static final double SERVO_MAX = 1.35;
    private static final String DEFAULT_PROFILE = "legacy-1.7";
    /** Ticks between chain hits — below the default grounded-run so a stationary fake never grounds out. */
    private static final int CADENCE_TICKS = 8;

    private ComboSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("combo: a same-attacker chain scales the fresh knock from the active hit",
                        context -> runChainScenario(mental, tester, context)),
                new TestCase("combo: a strafing (45°) attacker's servo hit prices the measured alignment",
                        context -> runStrafeAlignmentScenario(mental, tester, context)),
                new TestCase("combo: the victim retaliating flips the next knock back to 1.0",
                        context -> runRetaliationScenario(mental, tester, context)),
                new TestCase("combo: a grounded run flips the next knock back to 1.0",
                        context -> runGroundedScenario(mental, tester, context)),
                new TestCase("combo: ZERO-TOUCH — module off ships every knock at comboFactor 1.0, era-identical",
                        context -> runZeroTouchScenario(mental, tester, context)),
                new TestCase("combo: the api start/end events fire with reasons",
                        context -> runApiEventScenario(mental, tester, context)),
                new TestCase("combo: reach-handicap shortens interaction range for a held combo, restored on end (1.20.5+)",
                        context -> runReachHandicapScenario(mental, tester, context)),
                new TestCase("combo: reach-handicap join-sweep clears a crash-leaked modifier (1.20.5+)",
                        context -> runReachSweepScenario(mental, tester, context)),
                new TestCase("combo: reach-handicap OFF leaves interaction range untouched through a combo (1.20.5+)",
                        context -> runReachZeroTouchScenario(mental, tester, context)),
                new TestCase("combo: reach-handicap backstop rejects a comboed victim's over-reach answer, accepts the honest cap (1.20.5+)",
                        context -> runReachBackstopScenario(mental, tester, context)),
                new TestCase("combo: reach-handicap wins over the HITBOX ATTACK_RANGE weapon component (1.21.5+)",
                        context -> runReachHandicapComponentScenario(mental, tester, context)),
                new TestCase("combo: gen3 lifecycle Chain→Start→Hit→End",
                        context -> runGen3LifecycleScenario(mental, tester, context)),
                new TestCase("combo: comboOn observes the pre-hit state (§7)",
                        context -> runPreHitStateScenario(mental, tester, context)),
                new TestCase("combo: query surface answers (§6/§11.4)",
                        context -> runQuerySurfaceScenario(mental, tester, context)),
                new TestCase("combo: hurtWindowClear pins the §6 expression (§11.3)",
                        context -> runHurtWindowScenario(mental, tester, context)),
                new TestCase("combo: a foreign velocity cancel advances nothing (D1/§11.6)",
                        context -> runForeignCancelScenario(mental, tester, context)),
                new TestCase("combo: suppress ships zero and outcomes read back (§8)",
                        context -> runOutcomeMachineScenario(mental, tester, context)),
                new TestCase("combo: developing aborts surface (§5.2)",
                        context -> runDevelopingAbortsScenario(mental, tester, context)),
                new TestCase("combo: module toggle-off fires terminals and defuncts handles (§11.5)",
                        context -> runModuleToggleTerminalsScenario(mental, tester, context)),
                new TestCase("combo: natively blocked cadence forms a combo (D2/§11.6)",
                        context -> runBlockedComboScenario(mental, tester, context)));
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
            PocketServo.Solution[] solution = new PocketServo.Solution[1];
            int shipsBefore = context.sync(() -> {
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                PlayerView view = session.view();
                context.expect(view != null && attacker.uuid().equals(view.comboAttackerId()),
                        "the combo must be active on the attacker before the measured hit");
                KnockbackProfile profile = KnockbackSuite.profileFor(mental, victim);
                EntityState attackerState = EntityStates.capture(attacker.player());
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                PocketServoConfig servo = comboSettings(mental).servo();
                // The same tick the region solve will read inside this sync block —
                // the post-hit chase window's gap arithmetic must match it, and
                // build() is a pure read (the window commits only at the delivery
                // seams), so this re-derivation consumes exactly the memory state
                // the production solve consumes.
                PredictorInputs inputs = ComboPredictor.build(
                        attacker.uuid(), victim.uuid(),
                        attackerState.x(), attackerState.z(), victimState.x(), victimState.z(),
                        view, mental.sessions().viewOf(attacker.uuid()),
                        mental.sessions().positions(), new LatencyModel(), mental.clock().current(),
                        // Clientless attackers dispatch past the rim (no domain), so production
                        // reads ResetModel.UNKNOWN and keeps the measured-ring chase — match it.
                        ResetModel.UNKNOWN);
                // D-8: the java.util.Random overload keeps a jvmdowngrader RandomGenerator
                // stub type out of this cross-jar call's descriptor (a RandomGenerator descriptor
                // resolves a mismatched per-plugin stub and NoSuchMethodErrors on the v52 tier).
                // The seed is fixed so the resistance roll is deterministic against the desk's solve.
                solution[0] = KnockbackEngine.explainServo(
                        attackerState, victimState, profile, null, false, servo, inputs);
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
                    "the servo factor must sit inside the [0.93, 1.35] honesty clamps (got " + sigma + ")");
            context.expect(Math.abs(sigma - 1.0) > 1e-6
                            || solution[0].sigmaStar() < PocketServo.saturationFloor(SERVO_MIN),
                    "an active-combo hit must be servo-shaped or honestly saturation-declined (σ=" + sigma
                            + " σ*=" + solution[0].sigmaStar() + ")");
            context.expectNear(expected[0], sigma, SIGMA_EPSILON,
                    "the journaled σ must equal the production solve on the same inputs");
        } finally {
            teardown(mental, context, attacker, victim);
        }
    }

    /* -------------------- scenario 1b: the strafing attacker -------------------- */

    /**
     * The 2.4.7 strafe fix, end to end: with the combo active, the attacker
     * "strafes" — stepped diagonally (45° off the live attacker→victim axis,
     * closing component positive) one small teleport per tick, so the position
     * ring carries a genuine strafe heading — and the next servo hit must
     * (a) build inputs whose measured alignment reads cos45-class, (b) journal a σ
     * equal to the re-derived solve over those same inputs, and (c) stay inside
     * the honesty clamps. The stationary chain scenario covers the NaN-alignment
     * (byte-identical) side of the same seam.
     */
    private static void runStrafeAlignmentScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            setUp(mental, context, attacker, victim, true);
            buildActiveCombo(mental, context, attacker, victim);

            // Stage the strafe: five 0.20-block steps, one per tick, each at 45°
            // off the CURRENT attacker→victim axis with a positive closing
            // component — the ring's recent(5) then reads a heading whose axis dot
            // is ≈ cos 45°. Steps ride the owning thread (Folia-safe teleports);
            // total gap stays far under the combo's maxGapTicks, and the ~0.7
            // blocks of diagonal close keeps the pair inside attack reach.
            for (int step = 0; step < 5; step++) {
                context.syncRun(() -> {
                    Location a = attacker.player().getLocation();
                    Location v = victim.player().getLocation();
                    double dx = v.getX() - a.getX();
                    double dz = v.getZ() - a.getZ();
                    double len = Math.sqrt(dx * dx + dz * dz);
                    double ux = dx / len;
                    double uz = dz / len;
                    // Rotate the closing axis +45°: (ux,uz) → ((ux−uz)/√2, (ux+uz)/√2).
                    double hx = (ux - uz) * Math.sqrt(0.5);
                    double hz = (ux + uz) * Math.sqrt(0.5);
                    teleportOnOwningThread(mental, attacker,
                            a.clone().add(hx * 0.20, 0.0, hz * 0.20));
                });
                context.awaitTicks(1);
            }

            // Re-derive the expected σ from the SAME build + solve in the SAME sync
            // tick as the attack (the chain scenario's pattern) and capture the
            // measured alignment the inputs carried.
            double[] expected = new double[1];
            double[] alignment = new double[1];
            int shipsBefore = context.sync(() -> {
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                PlayerView view = session.view();
                context.expect(view != null && attacker.uuid().equals(view.comboAttackerId()),
                        "the combo must still be active after the strafe staging");
                KnockbackProfile profile = KnockbackSuite.profileFor(mental, victim);
                EntityState attackerState = EntityStates.capture(attacker.player());
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                PocketServoConfig servo = comboSettings(mental).servo();
                PredictorInputs inputs = ComboPredictor.build(
                        attacker.uuid(), victim.uuid(),
                        attackerState.x(), attackerState.z(), victimState.x(), victimState.z(),
                        view, mental.sessions().viewOf(attacker.uuid()),
                        mental.sessions().positions(), new LatencyModel(), mental.clock().current(),
                        ResetModel.UNKNOWN);
                alignment[0] = inputs.chaseAlignment();
                expected[0] = KnockbackEngine.computePaced(
                        attackerState, victimState, profile, null, new Random(0L), false, servo, inputs)
                        .comboFactor();
                victim.player().setNoDamageTicks(0);
                int before = countShips(mental, victim);
                attacker.attack(victim.player());
                return before;
            });
            JournalEntry ship = awaitNewShip(context, mental, victim, shipsBefore);
            context.expect(ship != null && ship.shipped() != null,
                    "the strafing-combo hit journaled no SHIP");
            context.expect(alignment[0] > 0.55 && alignment[0] < 0.85,
                    "a 45° strafe heading must read a cos45-class alignment (got " + alignment[0] + ")");
            double sigma = ship.comboFactor();
            context.expect(sigma >= SERVO_MIN - 1e-9 && sigma <= SERVO_MAX + 1e-9,
                    "the strafing servo factor must sit inside the [0.93, 1.35] clamps (got " + sigma + ")");
            context.expectNear(expected[0], sigma, SIGMA_EPSILON,
                    "the journaled σ must equal the production solve on the same strafe inputs");
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
     * The genuine touchdown end (combo-hold §3.1): after a real grounded run the
     * held combo releases and the next knock is era-plain again. This case
     * exercises the REAL configured threshold (10, un-widened) — that is its whole
     * point — which forces it to stage the victim's ground state physically rather
     * than lean on a widened window. Since the servo-solve round the effective
     * threshold rides the chain's OBSERVED cadence (configured floor + observed
     * gap + 2 jitter ticks), so the touchdown idle below waits on the released
     * view rather than a fixed tick count.
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
            // Idle until the touchdown end fires. The threshold is no longer the
            // flat configured 10: since the servo-solve round it scales with the
            // chain's OBSERVED cadence (observed + 2 jitter ticks, capped by
            // max-gap — here 400, so the cap never binds), and the build-up's real
            // gaps carry scheduling overhead beyond CADENCE_TICKS. Waiting on the
            // released view instead of a fixed tick count keeps the case honest
            // for whatever cadence the run actually measured; the cap (60t) is
            // comfortably past any build-up cadence this staging can produce.
            context.awaitUntil(() -> {
                try {
                    return context.sync(() -> {
                        CombatSession session = mental.sessions().sessionFor(victim.uuid());
                        PlayerView view = session == null ? null : session.view();
                        return view != null && view.comboAttackerId() == null;
                    });
                } catch (Exception failure) {
                    return false;
                }
            }, 60, "the grounded run to release the combo");

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
        ComboEventCaptor captor = new ComboEventCaptor(victim.uuid());
        try {
            setUp(mental, context, attacker, victim, true);
            context.syncRun(() ->
                    Bukkit.getPluginManager().registerEvents(captor, tester));

            buildActiveCombo(mental, context, attacker, victim);
            context.awaitUntil(() -> !captor.starts.isEmpty(), 40, "a ComboStartEvent");
            context.expect(!captor.starts.isEmpty(), "no ComboStartEvent fired for an active combo");
            context.expect(captor.starts.get(0).getHits() >= 2, "the start event must carry the chain length (minHits 2, the 2.4.5 retune)");
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

    /* ------------------- scenario 6: reach-handicap apply/restore ------------------ */

    /**
     * The reach handicap (design §1): while a combo is held the victim's
     * interaction-range attribute is scaled by {@code reach-scale} (0.8) via an
     * additive modifier, applied on combo start and removed on EVERY end reason.
     * 1.20.5+ ONLY — below that the attribute is absent and the sub-feature is a
     * documented no-op, so the case skips with that note (the platform-probe
     * doctrine). Removal is reason-agnostic (one {@code removeMatching} for every
     * end), so pinning two representative ends — RETALIATION and the module DISABLE
     * (leg 3) — proves the whole family restores exactly.
     */
    private static void runReachHandicapScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        if (!reachAttributeSupported()) {
            context.skip("entity-interaction-range attribute absent (below 1.20.5) — reach handicap is a "
                    + "documented no-op here");
            return;
        }
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            setUpReach(mental, context, attacker, victim, /*handicap=*/ true);
            double base = reachValue(context, victim);
            context.expect(base > 0, "interaction-range must read a positive base before any combo");

            // Apply on start: a held combo scales the reach by 0.8.
            buildActiveCombo(mental, context, attacker, victim);
            context.awaitTicks(3); // the apply is scheduled on the owning thread
            context.expectNear(base * 0.8, reachValue(context, victim), 1e-6,
                    "a held combo must scale the victim's interaction range by reach-scale (0.8)");

            // Restore on RETALIATION.
            context.syncRun(() -> {
                attacker.player().setNoDamageTicks(0);
                victim.attack(attacker.player());
            });
            context.awaitTicks(5);
            context.expectNear(base, reachValue(context, victim), 1e-9,
                    "the RETALIATION end must restore the interaction range exactly");

            // Re-apply, then restore on the module DISABLE mid-combo (leg 3).
            buildActiveCombo(mental, context, attacker, victim);
            context.awaitTicks(3);
            context.expectNear(base * 0.8, reachValue(context, victim), 1e-6,
                    "the handicap must re-apply on a fresh combo");
            context.syncRun(() -> {
                // Disable the reach-handicap's OWN module — it is standalone since the
                // 2.4.5 detection/servo split (892da05), so combo-hold no longer owns it.
                mental.overlaySet("modules.combo-reach-handicap", false);
                mental.reloadAll();
            });
            context.awaitTicks(3);
            context.expectNear(base, reachValue(context, victim), 1e-9,
                    "disabling the reach-handicap module mid-combo must restore the interaction range (leg 3)");
        } finally {
            teardownReach(mental, context, attacker, victim);
        }
    }

    /* ------------------- scenario 7: the crash-leak join sweep --------------------- */

    /**
     * Player attribute modifiers persist to NBT, so a crash mid-combo could leak the
     * handicap into the save. The join-sweep (leg 2) strips any {@code
     * mental:combo-reach} modifier on sight — this plants one synthetically (the
     * crash-leak stand-in) and fires the join event, then asserts it is gone.
     * Reason-agnostic and enabled-agnostic: the sweep clears a leak whether or not a
     * combo is live.
     */
    private static void runReachSweepScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        if (!reachAttributeSupported()) {
            context.skip("entity-interaction-range attribute absent (below 1.20.5) — reach handicap is a "
                    + "documented no-op here");
            return;
        }
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            setUpReach(mental, context, attacker, victim, /*handicap=*/ true);
            double base = reachValue(context, victim);

            // Plant a stale modifier straight onto the live attribute (the crash-leak
            // stand-in), on the owning thread, and confirm it took effect.
            context.syncRun(() -> {
                Attribute attr = Attributes.entityInteractionRange();
                AttributeInstance instance = victim.player().getAttribute(attr);
                instance.addModifier(AttributeModifiers.comboReach(0.5));
            });
            context.expectNear(base * 0.5, reachValue(context, victim), 1e-6,
                    "the planted stale modifier must shorten the reach before the sweep");

            // Fire the join event — the handicap's onJoin listener sweeps the leak.
            fireJoin(context, victim);
            context.awaitTicks(5); // the sweep is scheduled on the owning thread
            context.expectNear(base, reachValue(context, victim), 1e-9,
                    "the join-sweep must strip the stale modifier and restore the reach exactly");
        } finally {
            teardownReach(mental, context, attacker, victim);
        }
    }

    /* ------------------- scenario 8: reach-handicap zero-touch --------------------- */

    /**
     * Zero-touch: with the sub-feature OFF (its default), no modifier is ever
     * constructed, so the interaction range is byte-identical through a full combo —
     * the module can be on and the servo shaping knocks while the reach lever stays
     * inert.
     */
    private static void runReachZeroTouchScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        if (!reachAttributeSupported()) {
            context.skip("entity-interaction-range attribute absent (below 1.20.5) — reach handicap is a "
                    + "documented no-op here");
            return;
        }
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            // Module ON, reach-handicap OFF (the sub-feature default).
            setUpReach(mental, context, attacker, victim, /*handicap=*/ false);
            double base = reachValue(context, victim);

            buildActiveCombo(mental, context, attacker, victim);
            context.awaitTicks(3);
            context.expectNear(base, reachValue(context, victim), 1e-9,
                    "with the sub-feature off a held combo must NOT touch the interaction range");
            // Drive a few more hits and keep checking — no modifier ever appears.
            for (int hit = 0; hit < 3; hit++) {
                driveHitFactor(mental, context, attacker, victim);
                context.awaitTicks(CADENCE_TICKS);
            }
            context.expectNear(base, reachValue(context, victim), 1e-9,
                    "reach must stay untouched across the whole combo (zero-touch)");
        } finally {
            teardownReach(mental, context, attacker, victim);
        }
    }

    /* --------------- scenario 8b: the reach-validation backstop bite --------------- */

    /**
     * The server-side backstop (servo-lab 243 S2): while a combo is held against a
     * victim AND {@code hit-registration.reach-validation} is enabled, that victim's
     * OWN swings (they are the attacker of this hit) must be clamped to the handicap —
     * the fast path cancelled vanilla's melee gate, so it re-enforces the shortened
     * reach itself. The audit's first cut clamped eye-to-BOX at {@code scale·maxReach +
     * leniency} ({@code 2.8} at scale 0.8), which sat ABOVE everything an
     * attribute-blind (center-gated) client could send — nil margin. The fix scales
     * BOTH the reach and the leniency and measures eye-to-CENTRE.
     *
     * <p>This pins the production decision ({@link ReachClamp#passes}) against the
     * LIVE shipped config on a real server. It does not drive a fake melee: a
     * clientless {@code FakePlayer} attacks through NMS {@code Player#attack}, which
     * never traverses the PacketEvents INTERACT_ENTITY path where {@code passesReach}
     * lives — so the backstop can only be exercised honestly at its pure seam, fed the
     * config the reconciler actually resolved (handicap scale, reach window). The two
     * staged separations are derived from the same geometry helper production uses
     * ({@code ReachClamp.distanceToCenter}) at config-derived centre targets — no bare
     * distance literals: the attribute-blind client's OWN gate edge (centre =
     * {@code maxReach}) is denied, the honest handicapped cap (centre =
     * {@code scale·maxReach}) is accepted, and the same over-reach passes plainly with
     * the handicap off. 1.20.5+ only (the handicap lever is a documented no-op below).</p>
     */
    private static void runReachBackstopScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        if (!reachAttributeSupported()) {
            context.skip("entity-interaction-range attribute absent (below 1.20.5) — reach handicap is a "
                    + "documented no-op here");
            return;
        }
        try {
            // Handicap module on at 0.8 AND reach-validation enabled, through the real overlay.
            context.syncRun(() -> {
                mental.overlaySet("modules.combo-hold", true);
                mental.overlaySet("modules.combo-reach-handicap", true);
                mental.overlaySet("combo-reach-handicap.reach-scale", 0.8);
                mental.overlaySet("hit-registration.reach-validation.enabled", true);
                mental.reloadAll();
            });
            context.awaitTicks(3);

            // The live config the production clamp consults — the values under test.
            double scale = context.sync(() -> reachHandicapSettings(mental).scale());
            HitRegSettings.ReachValidation reach = context.sync(() -> hitReg(mental).reachValidation());
            context.expect(reach.enabled(), "reach-validation must be live for the backstop pin");
            double maxReach = reach.maxReach();
            double leniency = reach.leniency();

            // Stage head-on along +z on flat ground; place the attacker eye at a
            // config-derived eye-to-CENTRE target via the production geometry helper.
            double vGap = ReachClamp.EYE_HEIGHT - ReachClamp.CENTER_HEIGHT;
            double farZ = Math.sqrt(maxReach * maxReach - vGap * vGap);              // centre = maxReach (blind client's own edge)
            double nearZ = Math.sqrt(scale * maxReach * (scale * maxReach) - vGap * vGap); // centre = scale·maxReach (honest cap)
            List<PositionRing.Sample> none = List.of();

            // FAR, handicap ON → rejected: the backstop denies the over-reach.
            context.expect(
                    !ReachClamp.passes(0, ReachClamp.EYE_HEIGHT, farZ, none, 0, 0, 0, maxReach, leniency, scale),
                    "the handicapped backstop must reject an answer at the blind client's reach edge "
                            + "(centre = maxReach = " + maxReach + ")");
            // FAR, handicap OFF → accepted: the plain eye-to-box window is byte-identical.
            context.expect(
                    ReachClamp.passes(0, ReachClamp.EYE_HEIGHT, farZ, none, 0, 0, 0, maxReach, leniency, null),
                    "the same over-reach must pass with the handicap off (the plain window is unchanged)");
            // NEAR, handicap ON → accepted: the honest handicapped cap, comfortable margin.
            context.expect(
                    ReachClamp.passes(0, ReachClamp.EYE_HEIGHT, nearZ, none, 0, 0, 0, maxReach, leniency, scale),
                    "the handicapped backstop must accept the honest handicapped cap "
                            + "(centre = scale*maxReach = " + (scale * maxReach) + ")");
        } finally {
            context.syncRun(() -> {
                mental.overlaySet("modules.combo-reach-handicap", false);
                mental.overlaySet("modules.combo-hold", false);
                mental.overlaySet("hit-registration.reach-validation.enabled", false);
                mental.reloadAll();
            });
        }
    }

    /* --------------- scenario 9: handicap vs the ATTACK_RANGE component ------------ */

    /**
     * The interaction-audit conflict: on 1.21.5+ the HITBOX unit stamps every held
     * weapon with the explicit era {@code ATTACK_RANGE} component, and an explicit
     * component supplies the WHOLE attack window — the handicap's shortened
     * {@code ENTITY_INTERACTION_RANGE} attribute is then consulted by nothing, so
     * the sub-feature was silently void for any armed victim. The fix strips the
     * comboed victim's held weapon on combo START (the window falls back to the
     * attribute-honouring default) and re-stamps it on the end. This asserts the
     * component is absent exactly for the combo's span while the synced attribute
     * carries the 0.8 handicap — the server-side window truth an armed victim's
     * swing is gated by.
     */
    private static void runReachHandicapComponentScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        if (!reachAttributeSupported()) {
            context.skip("entity-interaction-range attribute absent (below 1.20.5) — reach handicap is a "
                    + "documented no-op here");
            return;
        }
        AttackRangeAdapter component = mental.platformProfile().attackRange();
        if (!component.supported()) {
            context.skip("ATTACK_RANGE component absent (below 1.21.5) — the component tier cannot "
                    + "override the handicapped attribute here");
            return;
        }
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            setUpReach(mental, context, attacker, victim, /*handicap=*/ true);
            context.syncRun(() -> {
                mental.overlaySet("modules.old-hitboxes", true);
                mental.reloadAll();
                context.expect(mental.featureActive(Feature.HITBOX), "old-hitboxes must be active");
                victim.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
                // The enable pass ran before the sword landed in the hand — fire the
                // held-change reconcile the unit listens to, exactly as a client would.
                Bukkit.getPluginManager().callEvent(new PlayerItemHeldEvent(victim.player(), 0, 0));
            });
            context.awaitTicks(3);
            boolean stamped = context.sync(() ->
                    component.carries(victim.player().getInventory().getItemInMainHand()));
            context.expect(stamped,
                    "HITBOX must stamp the held weapon with the era ATTACK_RANGE component pre-combo");
            double base = reachValue(context, victim);

            // A held combo: the component must yield (stripped) and the synced
            // attribute must carry the handicap — the armed victim's real window.
            buildActiveCombo(mental, context, attacker, victim);
            context.awaitTicks(3);
            context.expectNear(base * 0.8, reachValue(context, victim), 1e-6,
                    "a held combo must scale the armed victim's interaction range by reach-scale (0.8)");
            boolean strippedMidCombo = context.sync(() ->
                    !component.carries(victim.player().getInventory().getItemInMainHand()));
            context.expect(strippedMidCombo,
                    "the held weapon must NOT carry the ATTACK_RANGE component mid-combo — an explicit "
                            + "component supplies the whole window and would void the handicap");

            // A mid-combo held-change reconcile must not re-stamp the override.
            context.syncRun(() -> Bukkit.getPluginManager().callEvent(
                    new PlayerItemHeldEvent(victim.player(), 0, 0)));
            context.awaitTicks(2);
            boolean stillStripped = context.sync(() ->
                    !component.carries(victim.player().getInventory().getItemInMainHand()));
            context.expect(stillStripped,
                    "a mid-combo held-change reconcile re-stamped the component — the handicap gate on "
                            + "applyToHeld must hold for the combo's whole span");

            // End by retaliation: the attribute restores AND the component returns.
            context.syncRun(() -> {
                attacker.player().setNoDamageTicks(0);
                victim.attack(attacker.player());
            });
            context.awaitTicks(5);
            context.expectNear(base, reachValue(context, victim), 1e-9,
                    "the RETALIATION end must restore the interaction range exactly");
            boolean restamped = context.sync(() ->
                    component.carries(victim.player().getInventory().getItemInMainHand()));
            context.expect(restamped,
                    "the combo end must re-stamp the era ATTACK_RANGE component on the held weapon");
        } finally {
            context.syncRun(() -> {
                mental.overlaySet("modules.old-hitboxes", false);
                mental.reloadAll();
            });
            teardownReach(mental, context, attacker, victim);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Gen-3 acceptance (§5-§11) — the api integration surface, live       */
    /* ------------------------------------------------------------------ */

    /**
     * §5.1/§5.4/§5.6 (case 1): the full lifecycle on one confirmed-ship chain —
     * hit 1 opens a developing {@link ComboChainEvent} (hits 1), hit 2 promotes to
     * {@link ComboStartEvent} ALONE (no CHAIN_ADVANCED at min-hits 2, no
     * {@link ComboHitEvent} for that knock), hits 3/4 are active
     * {@link ComboHitEvent}s carrying the effective max-gap window, and the victim's
     * retaliation closes it with the balanced {@link ComboEndEvent}. The captor is
     * scoped to the victim so the retaliation's own developing chain (on the
     * attacker) never contaminates the counts.
     */
    private static void runGen3LifecycleScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        ComboEventCaptor captor = new ComboEventCaptor(victim.uuid());
        try {
            setUp(mental, context, attacker, victim, /*holdOpenChain=*/ true);
            int effGap = effectiveMaxGap(mental, context);
            context.syncRun(() -> Bukkit.getPluginManager().registerEvents(captor, tester));

            for (int hit = 0; hit < 4; hit++) {
                stageAttack(context, attacker, victim);
                context.awaitTicks(CADENCE_TICKS);
            }
            // Retaliation ends the active combo (RETALIATION). Once its terminal is
            // captured every prior chain/start/hit for this combo has already fired.
            context.syncRun(() -> {
                attacker.player().setNoDamageTicks(0);
                victim.attack(attacker.player());
            });
            context.awaitUntil(() -> !captor.ends.isEmpty(), 40, "the ComboEndEvent terminal");

            context.expect(captor.chains.size() == 1,
                    "expected exactly one developing ComboChainEvent (the opener), got " + captor.chains.size());
            ComboChainEvent opener = captor.chains.get(0);
            context.expect(opener.getHits() == 1,
                    "the ComboChainEvent must be the chain opening (hits==1), got " + opener.getHits());
            context.expect(attacker.uuid().equals(opener.getAttackerId()),
                    "the chain opener must name the attacker UUID");
            context.expect(opener.getGapDeadlineTick() != MentalCombat.NO_TICK,
                    "the chain opener must carry a real gap deadline");

            context.expect(captor.starts.size() == 1,
                    "expected exactly one ComboStartEvent, got " + captor.starts.size());
            ComboStartEvent start = captor.starts.get(0);
            context.expect(start.getHits() == 2,
                    "the start must fire at min-hits 2, got " + start.getHits());
            context.expect(start.getAttackerId() != null && attacker.uuid().equals(start.getAttackerId()),
                    "the start must name the attacker UUID");
            context.expect(start.getStartedTick() != MentalCombat.NO_TICK,
                    "the start must carry a real started tick");

            for (ComboHitEvent hit : captor.hits) {
                context.expect(hit.getHits() != 2,
                        "the promotion knock (hits==2) must be Start alone, never a ComboHitEvent");
            }
            context.expect(captor.hits.size() == 2,
                    "expected two active ComboHitEvents (hits 3,4), got " + captor.hits.size());
            int prev = 2;
            for (ComboHitEvent hit : captor.hits) {
                context.expect(hit.getHits() > prev,
                        "ComboHitEvent hits must strictly increase (saw " + hit.getHits() + " after " + prev + ")");
                prev = hit.getHits();
                long window = hit.getGapDeadlineTick() - hit.getTick();
                context.expect(window == effGap,
                        "a ComboHitEvent gap window (" + window + ") must equal the effective max-gap " + effGap);
            }

            ComboEndEvent end = captor.ends.get(0);
            context.expect(end.getReason() == ComboEndEvent.Reason.RETALIATION,
                    "the end reason must be RETALIATION, got " + end.getReason());
            context.expect(end.getHits() == 4,
                    "the end must carry the final chain length 4, got " + end.getHits());
            context.expect(end.getAttackerId() != null && attacker.uuid().equals(end.getAttackerId()),
                    "the end must name the attacker UUID");
            context.expect(end.getEndedTick() != MentalCombat.NO_TICK,
                    "the end must carry a real ended tick");
        } finally {
            context.syncRun(() -> HandlerList.unregisterAll(captor));
            teardown(mental, context, attacker, victim);
        }
    }

    /**
     * §7 (case 2): a query made from inside an {@code EntityDamageByEntityEvent}
     * handler observes the PRE-hit combo state, because the chain is fed only at the
     * later MONITOR confirm of the victim's velocity event. Records
     * {@code comboOn(victim)} at damage time for three cadence hits: hit 1 → NONE,
     * hit 2 → DEVELOPING/1 (the §7 fold-eligibility arm — the promotion hit's pre-hit
     * state), hit 3 → ACTIVE/2.
     */
    private static void runPreHitStateScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        PreHitObserver observer = new PreHitObserver(victim.uuid());
        try {
            setUp(mental, context, attacker, victim, /*holdOpenChain=*/ true);
            context.syncRun(() -> Bukkit.getPluginManager().registerEvents(observer, tester));

            for (int hit = 0; hit < 3; hit++) {
                stageAttack(context, attacker, victim);
                context.awaitTicks(CADENCE_TICKS);
            }
            // Three damage events recorded — one per staged hit.
            context.awaitUntil(() -> observer.states.size() >= 3, 40, "three pre-hit state samples");
            context.expect(observer.states.size() == 3,
                    "expected exactly 3 pre-hit samples, got " + observer.states.size());
            context.expect(observer.states.get(0) == ComboView.State.NONE && observer.hits.get(0) == 0,
                    "hit 1 pre-hit state must be NONE/0, got " + observer.states.get(0) + "/" + observer.hits.get(0));
            context.expect(observer.states.get(1) == ComboView.State.DEVELOPING && observer.hits.get(1) == 1,
                    "hit 2 pre-hit state must be DEVELOPING/1 (the fold-eligibility arm), got "
                            + observer.states.get(1) + "/" + observer.hits.get(1));
            context.expect(observer.states.get(2) == ComboView.State.ACTIVE && observer.hits.get(2) == 2,
                    "hit 3 pre-hit state must be ACTIVE/2, got "
                            + observer.states.get(2) + "/" + observer.hits.get(2));
        } finally {
            context.syncRun(() -> HandlerList.unregisterAll(observer));
            teardown(mental, context, attacker, victim);
        }
    }

    /**
     * §6/§11.4 (case 3): the query surface answers while a combo is active —
     * {@code combat()} non-null, an unknown victim reads NONE with NO_TICK ticks, a
     * captured {@link ComboHitEvent}'s tick is not in the future of the frame read
     * inside its own handler, and an OFF-thread {@code comboOn} returns a non-torn
     * ACTIVE view whose gap window equals the effective max-gap.
     */
    private static void runQuerySurfaceScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        ComboEventCaptor captor = new ComboEventCaptor(victim.uuid());
        try {
            setUp(mental, context, attacker, victim, /*holdOpenChain=*/ true);
            int effGap = effectiveMaxGap(mental, context);
            context.syncRun(() -> Bukkit.getPluginManager().registerEvents(captor, tester));

            buildActiveCombo(mental, context, attacker, victim);
            // buildActiveCombo drives three hits, so hit 3 fires an active ComboHitEvent.
            context.awaitUntil(() -> !captor.hits.isEmpty(), 40, "an active ComboHitEvent");

            // combat() is non-null while detection is live; an unknown victim is NONE.
            context.expect(context.sync(() -> Mental.get().combat() != null),
                    "combat() must be non-null while a combo module is running");
            ComboView unknown = context.sync(() -> Mental.get().combat().comboOn(new UUID(0, 0)));
            context.expect(unknown.state() == ComboView.State.NONE,
                    "an unknown victim must read state NONE, got " + unknown.state());
            context.expect(unknown.attackerId() == null
                            && unknown.lastKnockTick() == MentalCombat.NO_TICK
                            && unknown.gapDeadlineTick() == MentalCombat.NO_TICK,
                    "an unknown victim's view must carry null attacker + NO_TICK ticks");

            // The tick a ComboHitEvent carries is never ahead of the frame sampled
            // inside its own handler (index-aligned capture).
            ComboHitEvent hit = captor.hits.get(0);
            long sampledCurrent = captor.hitCurrentTicks.get(0);
            context.expect(hit.getTick() <= sampledCurrent,
                    "a ComboHitEvent tick (" + hit.getTick() + ") must be <= currentTick() ("
                            + sampledCurrent + ") sampled in its handler");

            // Off-thread (driver-thread) read: a non-torn ACTIVE view.
            ComboView view = Mental.get().combat().comboOn(victim.uuid());
            context.expect(view.state() == ComboView.State.ACTIVE,
                    "an off-thread comboOn must read ACTIVE, got " + view.state());
            context.expect(attacker.uuid().equals(view.attackerId()),
                    "the ACTIVE view must name the attacker");
            context.expect(view.hits() >= 2, "the ACTIVE view must carry hits >= 2, got " + view.hits());
            long window = view.gapDeadlineTick() - view.lastKnockTick();
            context.expect(window == effGap,
                    "the ACTIVE view's gap window (" + window + ") must equal the effective max-gap " + effGap);
        } finally {
            context.syncRun(() -> HandlerList.unregisterAll(captor));
            teardown(mental, context, attacker, victim);
        }
    }

    /**
     * §6/§11.3 (case 4): the pinned hurt-window admit expression
     * {@code ndt <= maxNdt / 2}, read on the region thread. Cell (11,20)→false is
     * the divergence cell versus Mental's internal "+1 staleness" fast-path read.
     */
    private static void runHurtWindowScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            setUp(mental, context, attacker, victim, /*holdOpenChain=*/ true);
            context.syncRun(() -> {
                MentalCombat combat = Mental.get().combat();
                context.expect(combat != null, "combat() must be non-null with combo-hold on");
                victim.player().setMaximumNoDamageTicks(20);
                int[][] cells = {{0, 1}, {10, 1}, {11, 0}, {20, 0}};
                for (int[] cell : cells) {
                    victim.player().setNoDamageTicks(cell[0]);
                    boolean want = cell[1] == 1;
                    context.expect(combat.hurtWindowClear(victim.player()) == want,
                            "hurtWindowClear(ndt=" + cell[0] + ", maxNdt=20) must be " + want);
                }
            });
        } finally {
            teardown(mental, context, attacker, victim);
        }
    }

    /**
     * D1/§11.6 (case 5): a foreign plugin cancelling the victim's velocity event at
     * HIGHEST advances no chain — the feed gates on {@code !isCancelled()} at the
     * MONITOR confirm. After the canceller is removed the chain opens normally
     * (recovery), proving the gate is the only thing that was in the way.
     */
    private static void runForeignCancelScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        ComboEventCaptor captor = new ComboEventCaptor(victim.uuid());
        VelocityCanceller canceller = new VelocityCanceller(victim.uuid());
        try {
            setUp(mental, context, attacker, victim, /*holdOpenChain=*/ true);
            context.syncRun(() -> {
                Bukkit.getPluginManager().registerEvents(captor, tester);
                Bukkit.getPluginManager().registerEvents(canceller, tester);
            });

            stageAttack(context, attacker, victim);
            context.awaitTicks(3);
            context.expect(captor.chains.isEmpty(),
                    "a foreign-cancelled velocity must advance no chain, got " + captor.chains.size()
                            + " ComboChainEvents");
            context.expect(context.sync(() -> Mental.get().combat().comboOn(victim.uuid()).state())
                            == ComboView.State.NONE,
                    "a foreign-cancelled hit must leave the victim's combo state NONE");

            // Remove the canceller and re-stage: the chain now opens (recovery).
            context.syncRun(() -> HandlerList.unregisterAll(canceller));
            captor.reset();
            context.syncRun(() -> victim.player().setNoDamageTicks(0));
            stageAttack(context, attacker, victim);
            context.awaitUntil(() -> !captor.chains.isEmpty(), 40, "the chain to open after the cancel is lifted");
            context.expect(captor.chains.get(0).getHits() == 1,
                    "recovery must open a fresh developing chain (hits==1), got " + captor.chains.get(0).getHits());
        } finally {
            context.syncRun(() -> {
                HandlerList.unregisterAll(captor);
                HandlerList.unregisterAll(canceller);
            });
            teardown(mental, context, attacker, victim);
        }
    }

    /**
     * §8 (case 6): the {@link KnockbackApplyEvent} last-writer-wins outcome machine,
     * live. The machine RESOLUTION (suppress → SUPPRESSED, velocity → SHIP, cancel →
     * YIELDED) is asserted on every path from the MONITOR read-back, and the one
     * outcome that changes delivery on ALL desk branches — YIELD (an early
     * stand-down, no ship) — is asserted directly.
     *
     * <p><b>Delivery-path caveat (clientless honesty).</b> The desk consults the
     * event's mutated velocity ONLY on its PRE_SENT branch (resolution
     * {@code ship-corrected}/{@code ship-valve}); on the PINNED and region
     * ({@code ship-formula}) branches it ships the pinned/formula vector and does not
     * re-read the api mutation (only YIELD's early return is honored there). A
     * clientless {@link FakePlayer} has no wire to pre-send a burst, so it always
     * resolves via the pinned/region path — the suppress→zero and modify→exact-vector
     * VALUES therefore only assert when the running path honors the mutation, and
     * note the clientless limitation otherwise. Those value semantics are unit-pinned
     * in {@code KnockbackApplyOutcomeTest}.</p>
     */
    private static void runOutcomeMachineScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        OutcomeCaptor outcomes = new OutcomeCaptor(victim.uuid());
        try {
            setUp(mental, context, attacker, victim, /*holdOpenChain=*/ true);
            context.syncRun(() -> Bukkit.getPluginManager().registerEvents(outcomes, tester));

            // Baseline — a plain (unmutated) melee ship: the event fires with the right
            // provenance, and its journal resolution tells us whether THIS server's
            // delivery path re-reads the api mutation (PRE_SENT) or not (pinned/region).
            outcomes.mode = OutcomeCaptor.Mode.NONE;
            outcomes.recorded = null;
            int before0 = countShips(mental, victim);
            stageAttack(context, attacker, victim);
            JournalEntry baseline = awaitNewShip(context, mental, victim, before0);
            context.expect(baseline != null && baseline.shipped() != null,
                    "a plain melee must journal a SHIP (Mental owns the knock)");
            context.awaitUntil(() -> outcomes.recorded != null, 40, "the MONITOR outcome capture");
            context.expect(outcomes.recorded == KnockbackApplyEvent.Outcome.SHIP,
                    "an unmutated melee resolves SHIP, got " + outcomes.recorded);
            context.expect(attacker.uuid().equals(outcomes.recordedAttacker),
                    "the outcome capture must carry the attacker UUID");
            context.expect(outcomes.recordedSource == KnockbackApplyEvent.Source.MELEE,
                    "the melee ship's source must be MELEE, got " + outcomes.recordedSource);
            boolean apiHonored = apiHonoredPath(baseline);
            if (!apiHonored) {
                context.note("clientless delivery resolved via the pinned/region path (resolution="
                        + resolutionOf(baseline) + "); the desk ships the pinned/formula vector there and "
                        + "does not re-read the api mutation — the suppress→zero and modify→exact VALUES are "
                        + "unit-pinned in KnockbackApplyOutcomeTest and asserted here only on the PRE_SENT path");
            }

            // Leg 1 — SUPPRESS: the machine resolves SUPPRESSED; on the PRE_SENT path it
            // ships an explicit zero vector.
            context.awaitTicks(CADENCE_TICKS);
            outcomes.mode = OutcomeCaptor.Mode.SUPPRESS;
            outcomes.recorded = null;
            int before1 = countShips(mental, victim);
            context.syncRun(() -> victim.player().setNoDamageTicks(0));
            stageAttack(context, attacker, victim);
            JournalEntry suppressed = awaitNewShip(context, mental, victim, before1);
            context.expect(suppressed != null && suppressed.shipped() != null,
                    "a SUPPRESSED melee must still journal a SHIP");
            context.awaitUntil(() -> outcomes.recorded != null, 40, "the MONITOR SUPPRESS capture");
            context.expect(outcomes.recorded == KnockbackApplyEvent.Outcome.SUPPRESSED,
                    "the recorded outcome must be SUPPRESSED, got " + outcomes.recorded);
            if (apiHonored) {
                context.expectNear(0.0, suppressed.shipped().x(), VECTOR_EPSILON, "suppressed ship x");
                context.expectNear(0.0, suppressed.shipped().y(), VECTOR_EPSILON, "suppressed ship y");
                context.expectNear(0.0, suppressed.shipped().z(), VECTOR_EPSILON, "suppressed ship z");
            }

            // Leg 2 — cancel → YIELDED → no ship lands (path-independent early stand-down).
            context.awaitTicks(CADENCE_TICKS);
            outcomes.mode = OutcomeCaptor.Mode.YIELD;
            outcomes.recorded = null;
            int before2 = countShips(mental, victim);
            context.syncRun(() -> victim.player().setNoDamageTicks(0));
            stageAttack(context, attacker, victim);
            context.awaitTicks(4);
            context.expect(countShips(mental, victim) == before2,
                    "a YIELDED knock must journal no new ship (count " + before2 + " changed)");
            context.awaitUntil(() -> outcomes.recorded != null, 40, "the MONITOR YIELD capture");
            context.expect(outcomes.recorded == KnockbackApplyEvent.Outcome.YIELDED,
                    "the recorded outcome must be YIELDED, got " + outcomes.recorded);

            // Leg 3 — cancel then velocity → the machine resolves SHIP; on the PRE_SENT
            // path it ships the last-written vector.
            context.awaitTicks(CADENCE_TICKS);
            outcomes.mode = OutcomeCaptor.Mode.MODIFY;
            outcomes.recorded = null;
            int before3 = countShips(mental, victim);
            context.syncRun(() -> victim.player().setNoDamageTicks(0));
            stageAttack(context, attacker, victim);
            JournalEntry modified = awaitNewShip(context, mental, victim, before3);
            context.expect(modified != null && modified.shipped() != null,
                    "a cancel-then-velocity knock must journal a SHIP");
            context.awaitUntil(() -> outcomes.recorded != null, 40, "the MONITOR SHIP capture");
            context.expect(outcomes.recorded == KnockbackApplyEvent.Outcome.SHIP,
                    "the recorded outcome must be SHIP (velocity clears a prior cancel), got " + outcomes.recorded);
            if (apiHonored) {
                context.expectNear(0.1, modified.shipped().x(), VECTOR_EPSILON, "last-writer ship x");
                context.expectNear(0.2, modified.shipped().y(), VECTOR_EPSILON, "last-writer ship y");
                context.expectNear(0.3, modified.shipped().z(), VECTOR_EPSILON, "last-writer ship z");
            }
        } finally {
            context.syncRun(() -> HandlerList.unregisterAll(outcomes));
            teardown(mental, context, attacker, victim);
        }
    }

    /** Whether the desk branch that journaled {@code entry} re-reads the api-mutated velocity (PRE_SENT). */
    private static boolean apiHonoredPath(JournalEntry entry) {
        String resolution = resolutionOf(entry);
        return "ship-corrected".equals(resolution) || "ship-valve".equals(resolution);
    }

    /** The desk resolution note the journal recorded, or "unknown" when the F9 capture is absent. */
    private static String resolutionOf(JournalEntry entry) {
        if (entry == null || entry.capture() == null || entry.capture().resolution() == null) {
            return "unknown";
        }
        return entry.capture().resolution();
    }

    /**
     * §5.2 (case 7): the three developing-chain abort vocabularies, each on a chain
     * that never activated. (a) gap expiry on the sweep → EXPIRED; (b) an in-window
     * attacker switch → SWITCHED (naming the OLD attacker), followed by the new
     * chain's opener; (c) victim retaliation → RETALIATION. Uses the REAL (un-widened)
     * gap so the live sweep expires the chain by construction — the SWITCHED-wins
     * coincidence is kernel-pinned, so leg (b) stays inside the gap.
     */
    private static void runDevelopingAbortsScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer other = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        ComboEventCaptor captor = new ComboEventCaptor(victim.uuid());
        try {
            setUp(mental, context, attacker, victim, /*holdOpenChain=*/ false);
            // The real gap (un-widened): a one-hit developing chain must expire on the
            // sweep. Grounded-run is active-only, so it never touches a developing chain.
            configureRules(mental, context, /*groundedRun=*/ 10, /*maxGap=*/ 20);
            context.syncRun(() -> {
                Location centre = victim.player().getLocation();
                other.spawn(Arena.offset(centre, -3, 0));
            });
            context.awaitTicks(5);
            int effGap = effectiveMaxGap(mental, context);
            context.syncRun(() -> Bukkit.getPluginManager().registerEvents(captor, tester));

            // (a) gap expiry → EXPIRED.
            stageAttack(context, attacker, victim);
            context.awaitUntil(() -> !captor.aborts.isEmpty(), effGap + 20,
                    "a developing chain to expire (EXPIRED)");
            context.expect(captor.aborts.size() == 1,
                    "gap expiry must fire exactly one ComboChainAbortEvent, got " + captor.aborts.size());
            context.expect(captor.aborts.get(0).getReason() == ComboChainAbortEvent.Reason.EXPIRED,
                    "the abort reason must be EXPIRED, got " + captor.aborts.get(0).getReason());
            context.expect(captor.aborts.get(0).getHits() == 1,
                    "the expired developing chain length must be 1, got " + captor.aborts.get(0).getHits());

            // (b) in-window attacker switch → SWITCHED (names the OLD attacker) + a new opener.
            captor.reset();
            context.syncRun(() -> victim.player().setNoDamageTicks(0));
            stageAttack(context, attacker, victim);
            context.awaitUntil(() -> !captor.chains.isEmpty(), 40, "attacker A's chain to open");
            context.syncRun(() -> victim.player().setNoDamageTicks(0));
            stageAttack(context, other, victim);
            context.awaitUntil(() -> !captor.aborts.isEmpty(), 40, "the switch abort");
            context.expect(captor.aborts.get(0).getReason() == ComboChainAbortEvent.Reason.SWITCHED,
                    "an in-window switch must abort SWITCHED, got " + captor.aborts.get(0).getReason());
            context.expect(attacker.uuid().equals(captor.aborts.get(0).getAttackerId()),
                    "the switch abort must name the OLD attacker (A)");
            context.expect(captor.chains.stream().anyMatch(c -> other.uuid().equals(c.getAttackerId())),
                    "the switch must open a fresh chain for the new attacker (B)");

            // (c) retaliation on a fresh developing chain → RETALIATION. Let attacker
            // B's chain settle to NONE FIRST (its gap-expiry EXPIRED abort would land
            // in the captor otherwise), THEN reset and open a fresh chain.
            context.awaitUntil(() -> {
                try {
                    return context.sync(() -> Mental.get().combat().comboOn(victim.uuid()).state())
                            == ComboView.State.NONE;
                } catch (Exception failure) {
                    return false;
                }
            }, 40, "the victim's chain to settle to NONE before leg (c)");
            captor.reset();
            context.syncRun(() -> victim.player().setNoDamageTicks(0));
            stageAttack(context, attacker, victim);
            context.awaitUntil(() -> !captor.chains.isEmpty(), 40, "a fresh developing chain for leg (c)");
            context.syncRun(() -> {
                attacker.player().setNoDamageTicks(0);
                victim.attack(attacker.player());
            });
            context.awaitUntil(() -> !captor.aborts.isEmpty(), 40, "the retaliation abort");
            context.expect(captor.aborts.get(0).getReason() == ComboChainAbortEvent.Reason.RETALIATION,
                    "a developing-chain retaliation must abort RETALIATION, got " + captor.aborts.get(0).getReason());
        } finally {
            context.syncRun(() -> HandlerList.unregisterAll(captor));
            context.syncRun(other::remove);
            teardown(mental, context, attacker, victim);
        }
    }

    /**
     * §11.5 (case 8, module-toggle half): turning combo-hold off fires the balanced
     * DISABLED terminals — a {@link ComboEndEvent} for an active combo, a
     * {@link ComboChainAbortEvent} for a developing one — and a held
     * {@link MentalCombat} handle degrades to the NONE/false shapes while the facade's
     * {@code combat()} answers null (§4). (The plugin-disable half is enforced by
     * construction — the tester cannot disable Mental mid-run without killing itself.)
     */
    private static void runModuleToggleTerminalsScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        ComboEventCaptor captor = new ComboEventCaptor(victim.uuid());
        try {
            setUp(mental, context, attacker, victim, /*holdOpenChain=*/ true);
            context.syncRun(() -> Bukkit.getPluginManager().registerEvents(captor, tester));

            MentalCombat held = context.sync(() -> Mental.get().combat());
            context.expect(held != null, "combat() must be non-null with combo-hold on");

            buildActiveCombo(mental, context, attacker, victim);

            // Toggle the module off through the same overlay+converge path setUp used.
            context.syncRun(() -> {
                mental.overlaySet("modules.combo-hold", false);
                mental.reloadAll();
            });
            context.awaitUntil(() -> captor.ends.stream()
                            .anyMatch(e -> e.getReason() == ComboEndEvent.Reason.DISABLED), 40,
                    "a DISABLED ComboEndEvent on module toggle-off");

            // The held handle degrades; the facade signals null.
            context.expect(context.sync(() -> held.comboOn(victim.uuid()).state()) == ComboView.State.NONE,
                    "a held handle must read NONE after the module is disabled");
            context.syncRun(() -> context.expect(!held.hurtWindowClear(victim.player()),
                    "a held handle's hurtWindowClear must read false after disable (the §4 defunct shape)"));
            context.expect(context.sync(() -> Mental.get().combat()) == null,
                    "combat() must be null once combo detection stops");

            // Toggle back on, build a 1-hit DEVELOPING chain, toggle off → DISABLED abort.
            context.syncRun(() -> {
                mental.overlaySet("modules.combo-hold", true);
                mental.overlaySet("combo-hold.grounded-run-ticks", 400);
                mental.overlaySet("combo-hold.max-gap-ticks", 400);
                mental.reloadAll();
            });
            context.awaitTicks(3);
            captor.reset();
            context.syncRun(() -> victim.player().setNoDamageTicks(0));
            stageAttack(context, attacker, victim);
            context.awaitUntil(() -> !captor.chains.isEmpty(), 40, "a developing chain before the second toggle");
            context.syncRun(() -> {
                mental.overlaySet("modules.combo-hold", false);
                mental.reloadAll();
            });
            context.awaitUntil(() -> captor.aborts.stream()
                            .anyMatch(a -> a.getReason() == ComboChainAbortEvent.Reason.DISABLED), 40,
                    "a DISABLED ComboChainAbortEvent — the terminal the old code never fired");
        } finally {
            context.syncRun(() -> HandlerList.unregisterAll(captor));
            teardown(mental, context, attacker, victim);
        }
    }

    /**
     * D2/§11.6 (case 9): a natively blocked cadence forms a combo. The blocked knock
     * re-delivers through {@code KnockbackUnit.deliverBlockedKnock}'s authoritative
     * {@code setVelocity}, which re-enters the desk's velocity seam and feeds the
     * tracker exactly like an unblocked ship — so blocked hits advance chains. Gated
     * on the BLOCKS_ATTACKS tier (skipped where absent); reuses the extracted
     * {@link NativeBlockStaging} to force the clientless native block state.
     */
    private static void runBlockedComboScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        if (!NativeBlockStaging.blocksAttacksPresent()) {
            context.skip("BLOCKS_ATTACKS tier absent — D2 pinned by the desk re-entry funnel");
            return;
        }
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer blocker = new FakePlayer(tester, mental.scheduling());
        ComboEventCaptor captor = new ComboEventCaptor(blocker.uuid());
        try {
            setUp(mental, context, attacker, blocker, /*holdOpenChain=*/ true);
            context.syncRun(() -> {
                mental.management().setModuleEnabled(
                        Feature.byModuleId("sword-blocking").orElseThrow(), true);
                blocker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            });
            context.awaitTicks(3);
            context.expect(mental.featureActive(Feature.SWORD_BLOCKING),
                    "sword-blocking must be active for the D2 blocked-combo case");
            context.syncRun(() -> Bukkit.getPluginManager().registerEvents(captor, tester));

            boolean blocking = NativeBlockStaging.forceNativeBlock(context, blocker);
            if (!blocking) {
                context.skip("the clientless fake never entered the native block state — the D2 funnel is "
                        + "otherwise pinned by BlockingSuite's silent-block-knock case");
                return;
            }

            // Drive a cadence of blocked hits — re-forcing the block state before each,
            // since a clientless use-item may lapse between hits.
            for (int hit = 0; hit < 3; hit++) {
                NativeBlockStaging.forceNativeBlock(context, blocker);
                context.syncRun(() -> blocker.player().setNoDamageTicks(0));
                stageAttack(context, attacker, blocker);
                context.awaitTicks(CADENCE_TICKS);
            }
            context.awaitUntil(() -> {
                try {
                    return context.sync(() -> Mental.get().combat().comboOn(blocker.uuid()).state())
                            == ComboView.State.ACTIVE;
                } catch (Exception failure) {
                    return false;
                }
            }, 40, "the blocked cadence to promote to an ACTIVE combo");
            context.expect(context.sync(() -> Mental.get().combat().comboOn(blocker.uuid()).state())
                            == ComboView.State.ACTIVE,
                    "a natively blocked cadence must form an ACTIVE combo (D2)");
            context.expect(!captor.starts.isEmpty(),
                    "the blocked delivery path must have fired a ComboStartEvent");
        } finally {
            context.syncRun(() -> {
                HandlerList.unregisterAll(captor);
                mental.management().setModuleEnabled(
                        Feature.byModuleId("sword-blocking").orElseThrow(), false);
            });
            teardown(mental, context, attacker, blocker);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /** The effective inter-hit max-gap the tracker is running under (the setUp overlay widens it). */
    private static int effectiveMaxGap(MentalPluginV5 mental, TestContext context) throws Exception {
        return context.sync(() -> comboSettings(mental).rules().maxGapTicks());
    }

    /** Stages one melee attack on the victim (clears its damage window first). */
    private static void stageAttack(TestContext context, FakePlayer attacker, FakePlayer victim) throws Exception {
        context.syncRun(() -> {
            victim.player().setNoDamageTicks(0);
            attacker.attack(victim.player());
        });
    }

    /** Records {@code comboOn(victim)} at EntityDamageByEntityEvent MONITOR — the §7 pre-hit read. */
    private static final class PreHitObserver implements Listener {
        private final UUID victimId;
        final List<ComboView.State> states = new CopyOnWriteArrayList<>();
        final List<Integer> hits = new CopyOnWriteArrayList<>();

        PreHitObserver(UUID victimId) {
            this.victimId = victimId;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageByEntityEvent event) {
            if (!victimId.equals(event.getEntity().getUniqueId())) {
                return;
            }
            MentalCombat combat = Mental.get().combat();
            if (combat == null) {
                return;
            }
            ComboView view = combat.comboOn(victimId);
            states.add(view.state());
            hits.add(view.hits());
        }
    }

    /** Cancels the victim's velocity event at HIGHEST — the D1 foreign-cancel stand-in. */
    private static final class VelocityCanceller implements Listener {
        private final UUID victimId;

        VelocityCanceller(UUID victimId) {
            this.victimId = victimId;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onVelocity(PlayerVelocityEvent event) {
            if (victimId.equals(event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Drives one {@link KnockbackApplyEvent} outcome at NORMAL (per {@link #mode}) and
     * records the final accumulated outcome + provenance at MONITOR — the §8 machine's
     * live probe. Last-writer-wins: {@code MODIFY} cancels then re-writes a vector in
     * one handler, so the final outcome is SHIP.
     */
    private static final class OutcomeCaptor implements Listener {
        enum Mode { NONE, SUPPRESS, YIELD, MODIFY }

        private final UUID victimId;
        volatile Mode mode = Mode.NONE;
        volatile KnockbackApplyEvent.Outcome recorded;
        volatile UUID recordedAttacker;
        volatile KnockbackApplyEvent.Source recordedSource;

        OutcomeCaptor(UUID victimId) {
            this.victimId = victimId;
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void act(KnockbackApplyEvent event) {
            if (!victimId.equals(event.getVictim().getUniqueId())) {
                return;
            }
            switch (mode) {
                case SUPPRESS -> event.suppress();
                case YIELD -> event.setCancelled(true);
                case MODIFY -> {
                    event.setCancelled(true);
                    event.velocity(new Vector(0.1, 0.2, 0.3));
                }
                case NONE -> { }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void record(KnockbackApplyEvent event) {
            if (!victimId.equals(event.getVictim().getUniqueId())) {
                return;
            }
            recorded = event.getOutcome();
            recordedAttacker = event.getAttackerId();
            recordedSource = event.getSource();
        }
    }

    /** Whether this server exposes the interaction-range attribute lever (1.20.5+). */
    private static boolean reachAttributeSupported() {
        return Attributes.entityInteractionRange() != null && AttributeModifiers.supported();
    }

    /** The victim's live interaction-range attribute VALUE (base × modifiers), read on the owning thread. */
    private static double reachValue(TestContext context, FakePlayer victim) throws Exception {
        return context.sync(() -> {
            Attribute attr = Attributes.entityInteractionRange();
            AttributeInstance instance = victim.player().getAttribute(attr);
            return instance == null ? Double.NaN : instance.getValue();
        });
    }

    /** setUp (module + widened chain) plus the reach-handicap sub-feature toggled + scale pinned to 0.8. */
    private static void setUpReach(
            MentalPluginV5 mental, TestContext context, FakePlayer attacker, FakePlayer victim,
            boolean handicap) throws Exception {
        setUp(mental, context, attacker, victim, /*holdOpenChain=*/ true);
        context.syncRun(() -> {
            mental.overlaySet("modules.combo-reach-handicap", handicap);
            mental.overlaySet("combo-reach-handicap.reach-scale", 0.8);
            mental.reloadAll();
        });
        context.awaitTicks(3);
    }

    /** Restores every reach-handicap + combo overlay and removes the pair. */
    private static void teardownReach(
            MentalPluginV5 mental, TestContext context, FakePlayer attacker, FakePlayer victim) throws Exception {
        context.syncRun(() -> {
            mental.overlaySet("modules.combo-reach-handicap", false);
            mental.overlaySet("modules.combo-hold", false);
            mental.overlaySet("combo-hold.grounded-run-ticks", 10);
            mental.overlaySet("combo-hold.max-gap-ticks", 20);
            mental.reloadAll();
            mental.management().setGlobalProfile(DEFAULT_PROFILE);
            attacker.remove();
            victim.remove();
        });
    }

    /** Fires the fake's join event so the handicap's onJoin sweep runs (the FakePlayer.spawn form). */
    @SuppressWarnings("deprecation") // the (Player, String) ctor is the cross-version-stable form
    private static void fireJoin(TestContext context, FakePlayer victim) throws Exception {
        context.syncRun(() ->
                Bukkit.getPluginManager().callEvent(new PlayerJoinEvent(victim.player(), "")));
    }

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

    /**
     * Drives three same-attacker hits — one past the 2-hit minHits (the 2.4.5 retune),
     * a publish-latency margin — and waits until the view publishes the active combo.
     */
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

    @SuppressWarnings("unchecked")
    private static ReachHandicapSettings reachHandicapSettings(MentalPluginV5 mental) {
        return mental.snapshot().settings(
                (SettingsKey<ReachHandicapSettings>) Feature.COMBO_REACH_HANDICAP.settingsKey());
    }

    @SuppressWarnings("unchecked")
    private static HitRegSettings hitReg(MentalPluginV5 mental) {
        return mental.snapshot().settings(
                (SettingsKey<HitRegSettings>) Feature.HIT_REGISTRATION.settingsKey());
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

    /**
     * Captures every gen-3 combo api event (the events fire on the victim's owning
     * region thread; the lists are concurrent so the off-thread driver can read
     * them without a hop). For each {@link ComboHitEvent} the captor also records
     * {@link MentalCombat#currentTick()} sampled INSIDE the handler (index-aligned
     * with {@link #hits}), so the §11.4 "getTick() ≤ currentTick()" pin reads the
     * frame as of the fire, not a later driver read.
     */
    private static final class ComboEventCaptor implements Listener {
        /**
         * Only events for this victim are recorded. Load-bearing: a victim
         * retaliating lands a hit on the attacker, which opens the ATTACKER's own
         * developing chain and fires its own ComboChainEvent — scoping keeps that
         * out of the victim's counts.
         */
        private final UUID scope;
        final List<ComboStartEvent> starts = new CopyOnWriteArrayList<>();
        final List<ComboEndEvent> ends = new CopyOnWriteArrayList<>();
        final List<ComboChainEvent> chains = new CopyOnWriteArrayList<>();
        final List<ComboChainAbortEvent> aborts = new CopyOnWriteArrayList<>();
        final List<ComboHitEvent> hits = new CopyOnWriteArrayList<>();
        final List<Long> hitCurrentTicks = new CopyOnWriteArrayList<>();

        ComboEventCaptor(UUID scope) {
            this.scope = scope;
        }

        private boolean inScope(UUID victim) {
            return scope == null || scope.equals(victim);
        }

        @EventHandler
        public void onStart(ComboStartEvent event) {
            if (inScope(event.getVictim().getUniqueId())) {
                starts.add(event);
            }
        }

        @EventHandler
        public void onEnd(ComboEndEvent event) {
            if (inScope(event.getVictim().getUniqueId())) {
                ends.add(event);
            }
        }

        @EventHandler
        public void onChain(ComboChainEvent event) {
            if (inScope(event.getVictim().getUniqueId())) {
                chains.add(event);
            }
        }

        @EventHandler
        public void onAbort(ComboChainAbortEvent event) {
            if (inScope(event.getVictim().getUniqueId())) {
                aborts.add(event);
            }
        }

        @EventHandler
        public void onHit(ComboHitEvent event) {
            if (!inScope(event.getVictim().getUniqueId())) {
                return;
            }
            // The current-tick sample must come from the SAME region-thread instant
            // the hit fired (combat() is non-null here — a combo is active). Recorded
            // first so hits.get(i) always has a matching hitCurrentTicks.get(i).
            MentalCombat combat = Mental.get().combat();
            hitCurrentTicks.add(combat != null ? combat.currentTick() : MentalCombat.NO_TICK);
            hits.add(event);
        }

        void reset() {
            starts.clear();
            ends.clear();
            chains.clear();
            aborts.clear();
            hits.clear();
            hitCurrentTicks.clear();
        }
    }
}
