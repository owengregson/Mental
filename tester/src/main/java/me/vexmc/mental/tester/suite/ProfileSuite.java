package me.vexmc.mental.tester.suite;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.vexmc.mental.api.event.KnockbackProfileChangeEvent;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
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
                        runGlobalApiScenario(mental, tester, context)));
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

    private static KnockbackProfile profileFor(MentalPluginV5 mental, FakePlayer victim) {
        return mental.snapshot().profileFor(victim.player().getWorld().getName());
    }
}
