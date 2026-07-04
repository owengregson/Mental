package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.vexmc.mental.api.event.KnockbackProfileChangeEvent;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.math.PaceScale;
import me.vexmc.mental.kernel.ledger.MotionLedger;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.manage.Management;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Knockback profiles end to end on a live server. Mental's knockback is
 * <em>global</em>: selecting a profile (through the management seam, which the
 * GUI and the API both call) must reroute every subsequent hit through the
 * preset's parsed values — file → parse → resolve → engine → velocity event —
 * server-wide. The archived-server presets must demonstrate their structural
 * levers on the wire: mmc's heavier friction survival on the soft dev123 base,
 * and lunar's vertical cap that sits BELOW its base (every grounded hit pins at
 * the cap, a shape no base/extra pair can fake). Each scenario restores the
 * legacy-1.7 default in its finally so suites stay isolated.
 *
 * <p>v5: the active profile is frozen into the per-tick {@code PlayerView}, so a
 * global switch is followed by a short wait before the attack — the view (and
 * with it the knockback unit's profile) picks up the new snapshot on the next
 * session tick.</p>
 */
public final class ProfileSuite {

    private static final double EPSILON = 1.0e-3;
    private static final String DEFAULT_PROFILE = "legacy-1.7";
    /** Ticks to let the session view adopt a freshly-swapped snapshot profile. */
    private static final int PROPAGATE_TICKS = 3;

    private ProfileSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("profile: selecting kohi globally reroutes the next hit", context ->
                        runKohiScenario(mental, tester, context)),
                new TestCase("profile: mmc carries the archived dev123 values", context ->
                        runMmcScenario(mental, tester, context)),
                new TestCase("profile: lunar's cap pins every grounded vertical", context ->
                        runLunarScenario(mental, tester, context)),
                new TestCase("profile: global selection persists, rejects unknowns, fires events", context ->
                        runGlobalApiScenario(mental, tester, context)),
                new TestCase("profile: signature scales a Speed III sprint hit ×1.6 (pace scaling)", context ->
                        runPaceScaleScenario(mental, tester, context)),
                new TestCase("profile: pace-off ignores Speed III (the inverse control)", context ->
                        runPaceOffScenario(mental, tester, context)),
                new TestCase("profile: signature Speed III combo hit-2 satisfies the A3 relation", context ->
                        runPaceComboScenario(mental, tester, context)));
    }

    /** A hit under a global kohi selection must equal kohi math, not the legacy default. */
    private static void runKohiScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            context.syncRun(() -> context.expect(mental.management().setGlobalProfile("kohi"),
                    "kohi preset missing — every bundled profile must be selectable"));
            context.awaitTicks(PROPAGATE_TICKS);

            KnockbackVector expected = context.sync(() -> {
                KnockbackProfile resolved = profileFor(mental, victim);
                context.expect("kohi".equals(resolved.name()),
                        "global selection did not win resolution (got " + resolved.name() + ")");
                victim.player().setNoDamageTicks(0);
                var victimState = KnockbackSuite.restingVictim(victim);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityStates.capture(attacker.player()), victimState, resolved, null);
                captors.reset();
                attacker.attack(victim.player());
                return SuiteDelivery.melee(vector, resolved, victimState.grounded());
            });
            context.expect(expected != null, "engine returned no vector for an unresisted hit");
            // The kohi base differs from legacy-1.7 — matching it proves the
            // profile actually switched, not just that a knock arrived.
            context.expectNear(-0.0784 * 0.5 + 0.35, expected.y(), EPSILON,
                    "kohi expectation sanity (wire vertical)");

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the kohi knock's velocity event");
            Vector applied = captors.velocityOf(victim.uuid());
            context.expect(applied != null, "kohi-profile hit produced no velocity event");
            context.expectNear(expected.x(), applied.getX(), EPSILON, "kohi knockback x");
            context.expectNear(expected.y(), applied.getY(), EPSILON, "kohi knockback y");
            context.expectNear(expected.z(), applied.getZ(), EPSILON, "kohi knockback z");
        } finally {
            context.syncRun(() -> {
                mental.management().setGlobalProfile(DEFAULT_PROFILE);
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /**
     * The mmc preset at four blocks: the archived dev123 values on the wire —
     * the soft 0.32 base with NO distance taper (full push at max reach,
     * the superseded remake tapered here) and the 0.5556 friction survival
     * in the vertical term.
     */
    private static void runMmcScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            context.syncRun(() -> context.expect(mental.management().setGlobalProfile("mmc"),
                    "mmc preset missing"));
            context.awaitTicks(PROPAGATE_TICKS);

            KnockbackVector expected = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityStates.capture(attacker.player()),
                        KnockbackSuite.restingVictim(victim),
                        profileFor(mental, victim), null);
                captors.reset();
                attacker.attack(victim.player());
                return vector;
            });
            context.expect(expected != null, "engine returned no vector for an unresisted hit");

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the mmc knock's velocity event");
            Vector applied = captors.velocityOf(victim.uuid());
            context.expect(applied != null, "mmc-profile hit produced no velocity event");
            context.expectNear(expected.x(), applied.getX(), EPSILON, "mmc knockback x");
            context.expectNear(expected.y(), applied.getY(), EPSILON, "mmc knockback y");
            context.expectNear(expected.z(), applied.getZ(), EPSILON, "mmc knockback z");

            // The structural pins, independent of the engine expectation:
            // grounded-equilibrium vy × 0.5556 survival + the 0.32 base.
            context.expectNear(-0.0784 * 0.5556 + 0.32, applied.getY(), EPSILON,
                    "mmc vertical must be the dev123 ADD shape");
            double horizontal = Math.hypot(applied.getX(), applied.getZ());
            context.expectNear(0.32, horizontal, EPSILON,
                    "a four-block mmc hit ships the FULL dev123 push — no taper");
        } finally {
            context.syncRun(() -> {
                mental.management().setGlobalProfile(DEFAULT_PROFILE);
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /**
     * The lunar preset's signature shape: base vertical 0.44 sits ABOVE the
     * 0.361735 cap, so every grounded hit pins at exactly the cap — and the
     * 0.54 base horizontal ships in full. The cap-below-base lever is what
     * the superseded recreation (base 0.3535 == cap) couldn't show.
     */
    private static void runLunarScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            context.syncRun(() -> context.expect(mental.management().setGlobalProfile("lunar"),
                    "lunar preset missing"));
            context.awaitTicks(PROPAGATE_TICKS);

            KnockbackVector expected = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityStates.capture(attacker.player()),
                        KnockbackSuite.restingVictim(victim),
                        profileFor(mental, victim), null);
                captors.reset();
                attacker.attack(victim.player());
                return vector;
            });
            context.expect(expected != null, "engine returned no vector for an unresisted hit");

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the lunar knock's velocity event");
            Vector applied = captors.velocityOf(victim.uuid());
            context.expect(applied != null, "lunar-profile hit produced no velocity event");
            // 0.7634 × (−0.0784) + 0.44 = 0.3801 > cap — the cap must bind.
            context.expectNear(0.361735, applied.getY(), EPSILON,
                    "lunar grounded vertical must pin at the S5 cap");
            context.expectNear(0.54, Math.hypot(applied.getX(), applied.getZ()), EPSILON,
                    "lunar ships the full 0.54 base horizontal");
        } finally {
            context.syncRun(() -> {
                mental.management().setGlobalProfile(DEFAULT_PROFILE);
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /**
     * The global selection contract: {@code setGlobalProfile} rejects unknown
     * names, persists the chosen default (so the snapshot's default reflects
     * it), and fires {@link KnockbackProfileChangeEvent} once per actual
     * transition (a re-select of the active profile is a no-op success).
     */
    private static void runGlobalApiScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        List<String> transitions = new CopyOnWriteArrayList<>();
        Listener probe = new Listener() {
            @EventHandler
            public void onChange(KnockbackProfileChangeEvent event) {
                transitions.add(event.getPreviousProfile() + "->" + event.getNewProfile());
            }
        };

        try {
            context.syncRun(() -> Bukkit.getPluginManager().registerEvents(probe, tester));

            context.syncRun(() -> {
                Management management = mental.management();
                management.setGlobalProfile(DEFAULT_PROFILE); // normalise the starting point
                transitions.clear();

                context.expect(!management.setGlobalProfile("minemen-exact"),
                        "an unknown profile name must be rejected");
                context.expect(management.setGlobalProfile("kohi"), "set kohi");
                context.expect(management.setGlobalProfile("kohi"),
                        "re-selecting the active profile is a no-op success");
                context.expect(management.setGlobalProfile("mmc"), "switch to mmc");
                context.expect("mmc".equals(mental.snapshot().defaultProfile()),
                        "the global default must reflect the last selection");
            });

            context.expect(List.of(DEFAULT_PROFILE + "->kohi", "kohi->mmc").equals(transitions),
                    "global change events must fire once per actual transition, got " + transitions);
        } finally {
            context.syncRun(() -> {
                HandlerList.unregisterAll(probe);
                mental.management().setGlobalProfile(DEFAULT_PROFILE);
            });
        }
    }

    /* --------------------- speed-conformal knockback (pace scaling) --------------------- */

    private static final double PACE_EPSILON = 1.5e-3;
    /** Speed III sprint: 0.1 × 1.3 × 1.6 = 0.208; 0.208 / 0.13 baseline = 1.6. */
    private static final double EXPECTED_S = 1.6;

    /**
     * The signature preset opts into speed-conformal knockback. A Speed III
     * sprint hit-1 (no residual) must ship a stamp that is exactly ×1.6 the
     * base-speed stamp HORIZONTALLY and UNCHANGED vertically, through the real
     * capture → engine → velocity-event pipeline. The base-speed reference is the
     * SAME captured state with the movement-speed attribute forced to its stance
     * baseline (pace factor 1.0), so this isolates the pace factor from all other
     * geometry.
     */
    private static void runPaceScaleScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            context.syncRun(() -> context.expect(mental.management().setGlobalProfile("signature"),
                    "signature preset missing — the pace-scaling preset must be selectable"));
            applySpeedThreeSprint(context, attacker);
            context.awaitTicks(PROPAGATE_TICKS);

            KnockbackVector expected = context.sync(() -> {
                KnockbackProfile signature = profileFor(mental, victim);
                context.expect("signature".equals(signature.name())
                        && signature.paceScaling().active(),
                        "signature must be resolved and pace-scaling active (got " + signature.name() + ")");
                victim.player().setNoDamageTicks(0);

                EntityState captured = EntityStates.capture(attacker.player());
                double s = PaceScale.factor(
                        captured.moveSpeedAttr(), captured.sprinting(), signature.paceScaling());
                // Guard: Speed III sprint must actually elevate the attribute so
                // the pace factor is 1.6 — a failure here is a setup fault
                // (potion/sprint modifier absent), never a silent 1.0 pass.
                context.expectNear(EXPECTED_S, s, 0.02,
                        "Speed III sprint must yield pace factor 1.6 (attr " + captured.moveSpeedAttr()
                                + ", sprinting " + captured.sprinting() + ")");

                // The production victim capture over the session ledger — the EXACT
                // grounded flag + residual the KnockbackUnit reads, so the air
                // multipliers (signature is air-non-identity) match the wire on
                // every version (a clientless fake reads isOnGround()=false on the
                // 1.9/1.10 NMS, which selects the airborne air-multiplier branch).
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                context.expect(session != null, "no combat session for the victim");
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                EntityState baseSpeed = withMoveSpeed(captured, baselineFor(captured.sprinting()));
                KnockbackVector scaledV = KnockbackEngine.compute(captured, victimState, signature, null);
                KnockbackVector baseV = KnockbackEngine.compute(baseSpeed, victimState, signature, null);

                // hit-1 has no residual, so the whole horizontal is fresh knock:
                // exactly ×1.6, and the vertical is untouched.
                context.expectNear(EXPECTED_S * baseV.x(), scaledV.x(), PACE_EPSILON, "pace hit-1 x ×1.6");
                context.expectNear(EXPECTED_S * baseV.z(), scaledV.z(), PACE_EPSILON, "pace hit-1 z ×1.6");
                context.expectNear(baseV.y(), scaledV.y(), PACE_EPSILON, "pace hit-1 vertical UNCHANGED");

                captors.reset();
                attacker.attack(victim.player());
                return SuiteDelivery.melee(scaledV, signature, victimState.grounded());
            });
            context.expect(expected != null, "engine returned no vector for the pace hit");

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the pace-scaled knock's velocity event");
            Vector applied = captors.velocityOf(victim.uuid());
            context.expect(applied != null, "pace-scaled hit produced no velocity event");
            context.expectNear(expected.x(), applied.getX(), PACE_EPSILON, "pace wire x");
            context.expectNear(expected.y(), applied.getY(), PACE_EPSILON, "pace wire y");
            context.expectNear(expected.z(), applied.getZ(), PACE_EPSILON, "pace wire z");
        } finally {
            teardown(context, mental, attacker, victim, captors);
        }
    }

    /**
     * The inverse control: under a pace-OFF profile (legacy-1.7), Speed III must
     * NOT change the knock — the engine skips the multiply entirely, so the
     * stamp is byte-identical to the base-speed stamp. Proves the era-exact no-op.
     */
    private static void runPaceOffScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            context.syncRun(() -> context.expect(mental.management().setGlobalProfile(DEFAULT_PROFILE),
                    "legacy-1.7 must be selectable"));
            applySpeedThreeSprint(context, attacker);
            context.awaitTicks(PROPAGATE_TICKS);

            KnockbackVector expected = context.sync(() -> {
                KnockbackProfile legacy = profileFor(mental, victim);
                context.expect(!legacy.paceScaling().active(),
                        "legacy-1.7 must have pace scaling OFF (the no-op default)");
                victim.player().setNoDamageTicks(0);

                EntityState captured = EntityStates.capture(attacker.player());
                // Guard: the attribute IS elevated (Speed III active) — so a
                // no-change result proves OFF ignores it, not that the potion missed.
                context.expect(captured.moveSpeedAttr() > baselineFor(captured.sprinting()) * 1.4,
                        "Speed III must be active for the inverse control (attr " + captured.moveSpeedAttr() + ")");

                // Production capture (see runPaceScaleScenario) — matches the wire's
                // grounded flag on every version.
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                context.expect(session != null, "no combat session for the victim");
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                EntityState baseSpeed = withMoveSpeed(captured, baselineFor(captured.sprinting()));
                KnockbackVector withSpeed = KnockbackEngine.compute(captured, victimState, legacy, null);
                KnockbackVector baseV = KnockbackEngine.compute(baseSpeed, victimState, legacy, null);
                // OFF ⇒ byte-identical: Speed III changes nothing.
                context.expect(withSpeed.x() == baseV.x() && withSpeed.y() == baseV.y()
                                && withSpeed.z() == baseV.z(),
                        "pace OFF must ignore Speed III byte-identically (got " + withSpeed
                                + " vs base " + baseV + ")");

                captors.reset();
                attacker.attack(victim.player());
                return SuiteDelivery.melee(withSpeed, legacy, victimState.grounded());
            });
            context.expect(expected != null, "engine returned no vector for the pace-off hit");

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the pace-off knock's velocity event");
            Vector applied = captors.velocityOf(victim.uuid());
            context.expect(applied != null, "pace-off hit produced no velocity event");
            context.expectNear(expected.x(), applied.getX(), PACE_EPSILON, "pace-off wire x");
            context.expectNear(expected.y(), applied.getY(), PACE_EPSILON, "pace-off wire y");
            context.expectNear(expected.z(), applied.getZ(), PACE_EPSILON, "pace-off wire z");
        } finally {
            teardown(context, mental, attacker, victim, captors);
        }
    }

    /**
     * The A3 relation on the wire: a Speed III combo. hit-1 stamps ×1.6, the
     * session ledger records the scaled motion, and hit-2 compounds the scaled
     * residual. The scaled combo hit-2 must be EXACTLY {@code s ×} the base-speed
     * hit-2 (reconstructed from the same residual scaled down by {@code s}) —
     * because pace scaling touches the fresh knock but not the already-scaled
     * carried residual. Delivered end to end through the live ledger + capture.
     */
    private static void runPaceComboScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        int gapTicks = 4;

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            context.syncRun(() -> context.expect(mental.management().setGlobalProfile("signature"),
                    "signature preset missing"));
            applySpeedThreeSprint(context, attacker);
            context.awaitTicks(PROPAGATE_TICKS);

            KnockbackProfile signature = context.sync(() -> profileFor(mental, victim));

            // Hit 1 — the scaled opener seeds the ledger.
            context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                EntityState victimState = KnockbackSuite.restingVictim(victim);
                KnockbackVector v = KnockbackEngine.compute(
                        EntityStates.capture(attacker.player()), victimState, signature, null);
                captors.reset();
                attacker.attack(victim.player());
                return v;
            });
            context.awaitTicks(gapTicks);
            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40, "the first hit's velocity event");
            Vector appliedFirst = captors.velocityOf(victim.uuid());
            context.expect(appliedFirst != null, "first pace hit produced no velocity event");
            captors.reset();

            CombatSession session = mental.sessions().sessionFor(victim.uuid());
            context.expect(session != null, "no combat session for the victim");
            MotionLedger ledger = session.ledger();

            List<KnockbackVector> candidates = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                EntityState scaledAttacker = EntityStates.capture(attacker.player());
                double s = PaceScale.factor(
                        scaledAttacker.moveSpeedAttr(), scaledAttacker.sprinting(), signature.paceScaling());
                context.expectNear(EXPECTED_S, s, 0.02,
                        "Speed III sprint must yield pace factor 1.6 for the combo (attr "
                                + scaledAttacker.moveSpeedAttr() + ")");

                // The production victim capture over the session ledger — the
                // SCALED residual the knockback unit reads on the same tick.
                EntityState scaledResidual = EntityStates.captureVictim(victim.player(), ledger);

                // A3 pin (engine-level, deterministic): reconstruct the base-speed
                // combo — the same residual scaled DOWN by s (decay is linear) and
                // a base-speed attacker — and assert the scaled hit-2 is exactly s×
                // horizontally / unchanged vertically.
                EntityState baseAttacker = withMoveSpeed(scaledAttacker, baselineFor(scaledAttacker.sprinting()));
                EntityState baseResidual = scaleHorizontal(scaledResidual, 1.0 / s);
                KnockbackVector scaledHit2 = KnockbackEngine.compute(scaledAttacker, scaledResidual, signature, null);
                KnockbackVector baseHit2 = KnockbackEngine.compute(baseAttacker, baseResidual, signature, null);
                context.expectNear(s * baseHit2.x(), scaledHit2.x(), PACE_EPSILON, "A3 combo hit-2 x = s×base");
                context.expectNear(s * baseHit2.z(), scaledHit2.z(), PACE_EPSILON, "A3 combo hit-2 z = s×base");
                context.expectNear(baseHit2.y(), scaledHit2.y(), PACE_EPSILON, "A3 combo hit-2 vertical unchanged");

                // Wire candidates (this tick + one tick later, for scheduling slack).
                List<KnockbackVector> expected = new ArrayList<>();
                expected.add(SuiteDelivery.melee(scaledHit2, signature, scaledResidual.grounded()));
                Decay.Motion later = Decay.decayOnce(
                        scaledResidual.vx(), scaledResidual.vy(), scaledResidual.vz(),
                        scaledResidual.grounded(), Decay.DEFAULT_GRAVITY);
                EntityState laterResidual = new EntityState(
                        scaledResidual.x(), scaledResidual.y(), scaledResidual.z(), scaledResidual.yaw(),
                        later.vx(), later.vy(), later.vz(), scaledResidual.grounded(),
                        scaledResidual.sprinting(), scaledResidual.knockbackEnchantLevel(),
                        scaledResidual.knockbackResistance(), scaledResidual.moveSpeedAttr());
                expected.add(SuiteDelivery.melee(
                        KnockbackEngine.compute(scaledAttacker, laterResidual, signature, null),
                        signature, laterResidual.grounded()));
                attacker.attack(victim.player());
                return expected;
            });

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40, "the second hit's velocity event");
            Vector appliedSecond = captors.velocityOf(victim.uuid());
            context.expect(appliedSecond != null, "second pace hit produced no velocity event");

            double bestDelta = Double.MAX_VALUE;
            for (KnockbackVector candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                double delta = Math.max(Math.abs(candidate.x() - appliedSecond.getX()),
                        Math.max(Math.abs(candidate.y() - appliedSecond.getY()),
                                Math.abs(candidate.z() - appliedSecond.getZ())));
                bestDelta = Math.min(bestDelta, delta);
            }
            context.expect(bestDelta < PACE_EPSILON,
                    "scaled combo hit-2 did not match any ledger candidate (best delta " + bestDelta
                            + ", applied " + appliedSecond + ")");
        } finally {
            teardown(context, mental, attacker, victim, captors);
        }
    }

    /** Speed III (amplifier 2) plus the sprint flag; a short settle lets the attribute modifiers apply. */
    @SuppressWarnings("deprecation") // getByName spans the 1.17.1 → 26.x API; SPEED is the stable alias
    private static void applySpeedThreeSprint(TestContext context, FakePlayer attacker) throws Exception {
        PotionEffectType speed = PotionEffectType.getByName("SPEED");
        context.expect(speed != null, "SPEED potion effect type unresolved");
        context.syncRun(() -> {
            Player player = attacker.player();
            player.setSprinting(true);
            player.addPotionEffect(new PotionEffect(speed, 20 * 60, 2, false, false));
        });
        context.awaitTicks(2); // let the sprint + Speed III movement-speed modifiers settle
    }

    private static double baselineFor(boolean sprinting) {
        return sprinting ? PaceScale.SPRINT_BASELINE : PaceScale.WALK_BASELINE;
    }

    private static EntityState withMoveSpeed(EntityState state, double moveSpeedAttr) {
        return new EntityState(
                state.x(), state.y(), state.z(), state.yaw(), state.vx(), state.vy(), state.vz(),
                state.grounded(), state.sprinting(), state.knockbackEnchantLevel(),
                state.knockbackResistance(), moveSpeedAttr);
    }

    private static EntityState scaleHorizontal(EntityState state, double factor) {
        return new EntityState(
                state.x(), state.y(), state.z(), state.yaw(),
                state.vx() * factor, state.vy(), state.vz() * factor,
                state.grounded(), state.sprinting(), state.knockbackEnchantLevel(),
                state.knockbackResistance(), state.moveSpeedAttr());
    }

    private static void teardown(
            TestContext context, MentalPluginV5 mental, FakePlayer attacker, FakePlayer victim,
            Captors captors) throws Exception {
        context.syncRun(() -> {
            mental.management().setGlobalProfile(DEFAULT_PROFILE);
            attacker.remove();
            victim.remove();
        });
        captors.unregister();
    }

    private static KnockbackProfile profileFor(MentalPluginV5 mental, FakePlayer victim) {
        return mental.snapshot().profileFor(victim.player().getWorld().getName());
    }
}
