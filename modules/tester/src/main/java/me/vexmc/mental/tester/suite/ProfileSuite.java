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
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.manage.Management;
import me.vexmc.mental.v5.preset.PresetCatalog;
import me.vexmc.mental.v5.preset.PresetKind;
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
                new TestCase("profile: signature scales a Speed III sprint hit ~1.563 (0.95 pace scaling)", context ->
                        runPaceScaleScenario(mental, tester, context)),
                new TestCase("profile: pace-off ignores Speed III (the inverse control)", context ->
                        runPaceOffScenario(mental, tester, context)),
                new TestCase("profile: signature Speed III combo hit-2 satisfies the A3 relation", context ->
                        runPaceComboScenario(mental, tester, context)),
                new TestCase("profile: server-sprinting attacker with no wire journals paceFactor ~1.0 (F1)",
                        context -> runStanceMismatchBaseSpeed(mental, tester, context)),
                new TestCase("profile: server-sprinting Speed III attacker with no wire journals ~1.563 (F1)",
                        context -> runStanceMismatchSpeedThree(mental, tester, context)),
                new TestCase("profile: modern-vanilla plain hit ships the 26.1.2 wire (~0.4, 0.3608)", context ->
                        runModernScenario(mental, tester, context, "modern-vanilla", false, 0.4, 0.3608)),
                new TestCase("profile: modern-vanilla sprint hit ships the 26.1.2 wire (~0.7, 0.4)", context ->
                        runModernScenario(mental, tester, context, "modern-vanilla", true, 0.7, 0.4)),
                new TestCase("profile: modern-uplift grounded hit lifts to 0.3608 (no mid-air slam)", context ->
                        runModernScenario(mental, tester, context, "modern-uplift", false, 0.4, 0.3608)));
    }

    /**
     * A modern-formula ({@code formula: modern}) hit, end to end. The modern
     * compute is direction-independent for a resting victim horizontally (the base
     * push has magnitude {@code base-strength}, and the sprint impulse rides the
     * attacker's facing — yaw 0 down +z, aligned with the away-from-attacker axis),
     * so the horizontal MAGNITUDE is a robust pin: 0.4 plain, 0.7 sprint (base 0.4
     * halved to 0.2 plus the 0.5 sprint impulse). The engine==applied assertion is
     * the always-on core; the absolute VERTICAL is pinned only when the victim
     * reads grounded (a clientless fake reads isOnGround() false on the 1.9/1.10
     * NMS, and modern-vanilla's downward slam then ships the falling vy instead of
     * the 0.3608 lift — version-noise the grounded guard sidesteps; the airborne
     * branches are unit-pinned in ModernKnockbackEngineTest). The expectation reads
     * the production victim capture over the session ledger — the EXACT grounded
     * flag + residual the region path reads — so engine==applied holds on every
     * version. modern-vanilla/uplift deliver IMMEDIATE (no wire decay).
     */
    private static void runModernScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context,
            String preset, boolean sprinting, double expectedHorizontal, double expectedVertical)
            throws Exception {
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

            context.syncRun(() -> context.expect(mental.management().setGlobalProfile(preset),
                    preset + " preset missing — every bundled profile must be selectable"));
            context.awaitTicks(PROPAGATE_TICKS);

            KnockbackVector expected = context.sync(() -> {
                KnockbackProfile resolved = profileFor(mental, victim);
                context.expect(preset.equals(resolved.name()) && resolved.modern().enabled(),
                        "the modern preset must resolve with formula modern (got " + resolved.name() + ")");
                if (sprinting) {
                    attacker.player().setSprinting(true);
                }
                victim.player().setNoDamageTicks(0);

                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                context.expect(session != null, "no combat session for the victim");
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityStates.capture(attacker.player()), victimState, resolved, null);

                // Horizontal magnitude is direction-independent for a resting victim
                // (air multipliers are identity on the modern presets); the vertical
                // is grounded-gated (the downward slam ships the falling vy airborne).
                context.expectNear(expectedHorizontal, Math.hypot(vector.x(), vector.z()), EPSILON,
                        preset + (sprinting ? " sprint" : " plain") + " horizontal magnitude");
                if (victimState.grounded()) {
                    context.expectNear(expectedVertical, vector.y(), EPSILON,
                            preset + (sprinting ? " sprint" : " plain") + " grounded vertical");
                }

                captors.reset();
                attacker.attack(victim.player());
                return SuiteDelivery.melee(vector, resolved, victimState.grounded());
            });
            context.expect(expected != null, "engine returned no vector for the modern hit");

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the modern knock's velocity event");
            Vector applied = captors.velocityOf(victim.uuid());
            context.expect(applied != null, preset + " hit produced no velocity event");
            context.expectNear(expected.x(), applied.getX(), EPSILON, preset + " wire x");
            context.expectNear(expected.y(), applied.getY(), EPSILON, preset + " wire y");
            context.expectNear(expected.z(), applied.getZ(), EPSILON, preset + " wire z");
        } finally {
            context.syncRun(() -> {
                mental.management().setGlobalProfile(DEFAULT_PROFILE);
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
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

                // §6.3: the unified PresetCatalog is a pure delegate over the SAME Management write seam —
                // pin catalog-through-management live so a second overlay path can never grow. The active
                // profile is 'mmc' here; re-applying it is a no-op success that fires no transition (the
                // event list below stays exact), and 'signature' is the default effects selection, an
                // equally eventless no-op.
                context.expect(!PresetCatalog.apply(PresetKind.KNOCKBACK, "minemen-exact", management),
                        "the catalog must reject an unknown knockback preset name");
                context.expect(PresetCatalog.apply(PresetKind.KNOCKBACK,
                                mental.snapshot().defaultProfile(), management),
                        "re-applying the active knockback preset through the catalog is a no-op success");
                context.expect(PresetCatalog.selected(PresetKind.KNOCKBACK, mental.snapshot())
                                .equals(mental.snapshot().defaultProfile()),
                        "the catalog's selected knockback preset must equal the snapshot's default profile");
                context.expect(PresetCatalog.apply(PresetKind.EFFECTS, "signature", management),
                        "applying the default effects preset (signature) through the catalog is a no-op success");
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
    /**
     * Speed III walk-normalized: 0.16 / 0.10 baseline = 1.6; the signature's 0.95
     * exponent gives {@code 1.6^0.95 ≈ 1.5628}. An EXPRESSION, not a literal — the
     * live attribute yields {@code pow(1.6000000474…, 0.95)}, which sits well
     * inside the 0.02 guard and the 1.5e-3 wire epsilon of this value.
     */
    private static final double EXPECTED_S = Math.pow(1.6, 0.95);

    /**
     * The signature preset opts into speed-conformal knockback. A Speed III
     * sprint hit-1 (no residual) must ship a stamp that is exactly ×{@code
     * EXPECTED_S} (1.6^0.95 ≈ 1.563) the base-speed stamp HORIZONTALLY and
     * UNCHANGED vertically, through the real capture → engine → velocity-event
     * pipeline. The base-speed reference is the SAME captured state with the
     * (walk-normalized) movement-speed attribute forced to the walk baseline (pace
     * factor 1.0), so this isolates the pace factor from all other geometry.
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
                double s = PaceScale.factor(captured.moveSpeedAttr(), signature.paceScaling());
                // Guard: Speed III sprint must actually elevate the (walk-normalized)
                // attribute so the pace factor is ~1.563 — a failure here is a setup
                // fault (potion/sprint modifier absent), never a silent 1.0 pass.
                context.expectNear(EXPECTED_S, s, 0.02,
                        "Speed III sprint must yield pace factor ~1.563 (attr " + captured.moveSpeedAttr()
                                + ", sprinting " + captured.sprinting() + ")");

                // The production victim capture over the session ledger — the EXACT
                // grounded flag + residual the KnockbackUnit reads, so the air
                // multipliers (signature is air-non-identity) match the wire on
                // every version (a clientless fake reads isOnGround()=false on the
                // 1.9/1.10 NMS, which selects the airborne air-multiplier branch).
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                context.expect(session != null, "no combat session for the victim");
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                EntityState baseSpeed = withMoveSpeed(captured, PaceScale.WALK_BASELINE);
                KnockbackVector scaledV = KnockbackEngine.compute(captured, victimState, signature, null);
                KnockbackVector baseV = KnockbackEngine.compute(baseSpeed, victimState, signature, null);

                // hit-1 has no residual, so the whole horizontal is fresh knock:
                // exactly ×EXPECTED_S (~1.563), and the vertical is untouched.
                context.expectNear(EXPECTED_S * baseV.x(), scaledV.x(), PACE_EPSILON, "pace hit-1 x ×s");
                context.expectNear(EXPECTED_S * baseV.z(), scaledV.z(), PACE_EPSILON, "pace hit-1 z ×s");
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
                // Guard: the (walk-normalized) attribute IS elevated (Speed III
                // active) — so a no-change result proves OFF ignores it, not that
                // the potion missed.
                context.expect(captured.moveSpeedAttr() > PaceScale.WALK_BASELINE * 1.4,
                        "Speed III must be active for the inverse control (attr " + captured.moveSpeedAttr() + ")");

                // Production capture (see runPaceScaleScenario) — matches the wire's
                // grounded flag on every version.
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                context.expect(session != null, "no combat session for the victim");
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                EntityState baseSpeed = withMoveSpeed(captured, PaceScale.WALK_BASELINE);
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
                double s = PaceScale.factor(scaledAttacker.moveSpeedAttr(), signature.paceScaling());
                context.expectNear(EXPECTED_S, s, 0.02,
                        "Speed III sprint must yield pace factor ~1.563 for the combo (attr "
                                + scaledAttacker.moveSpeedAttr() + ")");

                // The production victim capture over the session ledger — the
                // SCALED residual the knockback unit reads on the same tick.
                EntityState scaledResidual = EntityStates.captureVictim(victim.player(), ledger);

                // A3 pin (engine-level, deterministic): reconstruct the base-speed
                // combo — the same residual scaled DOWN by s (decay is linear) and
                // a base-speed attacker — and assert the scaled hit-2 is exactly s×
                // horizontally / unchanged vertically.
                EntityState baseAttacker = withMoveSpeed(scaledAttacker, PaceScale.WALK_BASELINE);
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

    /* --------------------- the F1 stance-mismatch regression (live) --------------------- */

    /**
     * The live discriminator for F1. A clientless fake attacker has NO connection
     * wire, so a server-side NMS attack registers with an absent/false sprint
     * verdict while the SERVER sprint flag and the ×1.3 movement-speed modifier ARE
     * present — exactly the historical mismatch. The 2.4.0 region path paired the
     * false verdict's baseline with the sprint-inclusive live attribute and shipped
     * pace factor 1.3 at base speed. With the walk-normalized capture the sprint
     * modifier is divided back out coherently, so the journal must record ~1.0 — a
     * byte-identical era stamp — regardless of the verdict.
     */
    private static void runStanceMismatchBaseSpeed(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
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
                    "signature preset missing"));
            applyServerSprint(context, attacker);
            context.awaitTicks(PROPAGATE_TICKS);

            Double factor = stanceMismatchFactor(mental, context, attacker, victim, false);
            if (factor == null) {
                return; // note-skipped (setup gap) — recorded in stanceMismatchFactor
            }
            // The fix: ~1.0, not the 2.4.0 bug's 1.3. Assert the FACTOR (stance math),
            // not an absolute vector — a 1.9/1.10 clientless fake reads isOnGround()
            // false, so absolute verticals are version-noisy (project memory).
            context.expectNear(1.0, factor, 0.02,
                    "a server-sprinting attacker with no wire must journal paceFactor ~1.0 (was 1.3)");
        } finally {
            teardown(context, mental, attacker, victim, null);
        }
    }

    /**
     * The Speed III half of the F1 discriminator: the same server-flag-only sprint,
     * now with Speed III genuinely elevating the attribute. The 2.4.0 region path
     * divided the sprint-inclusive 0.208 by the false verdict's walk baseline (2.08
     * → clamped 2.0); the walk-normalized capture reads 0.16 and yields
     * {@code 1.6^0.95 ≈ 1.563}.
     */
    private static void runStanceMismatchSpeedThree(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
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
                    "signature preset missing"));
            applySpeedThreeSprint(context, attacker);
            context.awaitTicks(PROPAGATE_TICKS);

            Double factor = stanceMismatchFactor(mental, context, attacker, victim, true);
            if (factor == null) {
                return;
            }
            // The fix: ~1.563 (1.6^0.95), not the 2.4.0 bug's 2.0 clamp.
            context.expectNear(EXPECTED_S, factor, 0.02,
                    "a server-sprinting Speed III attacker with no wire must journal ~1.563 (was clamped 2.0)");
            context.expect(factor < 2.0 - 1.0e-6,
                    "the paceFactor must not be the old 2.0 clamp (got " + factor + ")");
        } finally {
            teardown(context, mental, attacker, victim, null);
        }
    }

    /**
     * Drives one server-side NMS attack from the server-sprinting fake attacker and
     * returns the pace factor the desk journaled for it, or {@code null} (note-SKIP)
     * when the clientless fake's ×1.3 sprint modifier did not apply — the whole
     * mismatch depends on it, so its absence is a staging gap, not a regression.
     */
    private static Double stanceMismatchFactor(
            MentalPluginV5 mental, TestContext context, FakePlayer attacker, FakePlayer victim,
            boolean speedThree) throws Exception {
        int[] shipsBefore = new int[1];
        Boolean armed = context.sync(() -> {
            KnockbackProfile signature = profileFor(mental, victim);
            context.expect("signature".equals(signature.name()) && signature.paceScaling().active(),
                    "signature must be resolved and pace-scaling active");
            context.expect(attacker.player().isSprinting(),
                    "the attacker must carry the SERVER sprint flag (the mismatch precondition)");

            // Guard: the walk-normalized capture must read ~0.10 (base) / >0.14
            // (Speed III). If it reads ~0.077, the ×1.3 modifier never applied to the
            // clientless fake and the mismatch cannot be staged — note-SKIP.
            EntityState captured = EntityStates.capture(attacker.player());
            double normalized = captured.moveSpeedAttr();
            boolean modifierPresent = speedThree
                    ? normalized > PaceScale.WALK_BASELINE * 1.4
                    : Math.abs(normalized - PaceScale.WALK_BASELINE) < 0.01;
            if (!modifierPresent) {
                return Boolean.FALSE;
            }
            shipsBefore[0] = countShips(mental, victim);
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(armed)) {
            context.note("clientless fake never carried the ×1.3 sprint modifier — the wire-vs-server "
                    + "stance mismatch cannot be staged; the stance math is unit-pinned in PaceScaleTest");
            return null;
        }
        context.syncRun(() -> {
            victim.player().setNoDamageTicks(0);
            attacker.attack(victim.player()); // server-side NMS attack — the region path
        });
        JournalEntry ship = awaitNewShip(context, mental, victim, shipsBefore[0]);
        context.expect(ship != null && ship.shipped() != null,
                "the server-sprinting hit journalled no SHIP");
        return ship == null ? null : ship.paceFactor();
    }

    /** setSprinting(true) with NO potion (base speed); a short settle lets the ×1.3 modifier apply. */
    private static void applyServerSprint(TestContext context, FakePlayer attacker) throws Exception {
        context.syncRun(() -> attacker.player().setSprinting(true));
        context.awaitTicks(2);
    }

    /** SHIP entries (a delivered vector) currently in the victim's desk journal. */
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

    /** Polls the victim's desk journal for a NEW SHIP beyond {@code shipsBefore}. */
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
        if (captors != null) {
            captors.unregister();
        }
    }

    private static KnockbackProfile profileFor(MentalPluginV5 mental, FakePlayer victim) {
        return mental.snapshot().profileFor(victim.player().getWorld().getName());
    }
}
