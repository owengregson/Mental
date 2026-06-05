package me.vexmc.mental.tester.suite;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.api.event.PlayerKnockbackProfileChangeEvent;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.module.knockback.EntityState;
import me.vexmc.mental.module.knockback.KnockbackEngine;
import me.vexmc.mental.module.knockback.KnockbackVector;
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
 * Knockback profiles end to end on a live server: a per-player override
 * (the practice-core path) must reroute the very next hit through the
 * preset's parsed values — file → parse → resolve → engine → velocity
 * event — and the mmc preset must demonstrate the two levers no flat knob
 * can fake: the assigned vertical and the distance taper.
 */
public final class ProfileSuite {

    private static final double EPSILON = 1.0e-3;

    private ProfileSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("profile: kohi override reroutes the next hit", context ->
                        runOverrideScenario(mental, tester, context)),
                new TestCase("profile: mmc assigns the vertical and tapers the reach", context ->
                        runMmcScenario(mental, tester, context)),
                new TestCase("profile: the command path assigns and clears overrides", context ->
                        runCommandScenario(mental, tester, context)),
                new TestCase("profile: unknown names are rejected and events fire on change", context ->
                        runApiContractScenario(mental, tester, context)));
    }

    /** Hit under a kohi override must equal kohi math, not the legacy default. */
    private static void runOverrideScenario(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            KnockbackVector expected = context.sync(() -> {
                context.expect(mental.services().knockbackProfiles()
                                .setOverride(victim.player(), "kohi"),
                        "kohi preset missing — overrides must accept every bundled profile");
                KnockbackProfile resolved = mental.services().knockbackProfiles()
                        .resolve(victim.player());
                context.expect("kohi".equals(resolved.name()),
                        "override did not win resolution (got " + resolved.name() + ")");
                victim.player().setNoDamageTicks(0);
                var victimState = KnockbackSuite.restingVictim(victim);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityState.capture(attacker.player()),
                        victimState, resolved, null);
                captors.reset();
                attacker.attack(victim.player());
                return SuiteDelivery.melee(vector, resolved, victimState.grounded());
            });
            context.expect(expected != null, "engine returned no vector for an unresisted hit");
            // The kohi base differs from legacy-1.7 — matching it proves the
            // profile actually switched, not just that a knock arrived. The
            // expectation is the WIRE value: equilibrium baseline + kohi
            // base, shipped as the full tracker stamp (the decayed wire is
            // the opt-in tracker-decayed delivery, not kohi's default).
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
                mental.services().knockbackProfiles().setOverride(victim.player(), null);
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /**
     * The mmc preset at four blocks: vertical is ASSIGNED (0.25635 — no
     * residual, no era 0.4) and the horizontal push is tapered by
     * 0.025 × (d − 1.2), capped 0.12 — the two structural levers.
     */
    private static void runMmcScenario(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            KnockbackVector expected = context.sync(() -> {
                context.expect(mental.services().knockbackProfiles()
                                .setOverride(victim.player(), "mmc"),
                        "mmc preset missing");
                victim.player().setNoDamageTicks(0);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityState.capture(attacker.player()),
                        KnockbackSuite.restingVictim(victim),
                        mental.services().knockbackProfiles().resolve(victim.player()), null);
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
            context.expectNear(0.25635, applied.getY(), EPSILON,
                    "mmc vertical must be the ASSIGNED constant");
            double horizontal = Math.hypot(applied.getX(), applied.getZ());
            context.expect(horizontal < 0.38488 - 0.01,
                    "a four-block mmc hit must taper below the full 0.38488 push (got "
                            + horizontal + ")");
        } finally {
            context.syncRun(() -> {
                mental.services().knockbackProfiles().setOverride(victim.player(), null);
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** /mental kb set and reset drive the same override store. */
    private static void runCommandScenario(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer target = new FakePlayer(tester, mental.services().scheduling());

        try {
            context.syncRun(() ->
                    target.spawn(Arena.prepare(Bukkit.getWorlds().get(0))));
            context.awaitTicks(3);

            String name = context.sync(() -> target.player().getName());
            boolean setHandled = context.sync(() -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(), "mental kb set lunar " + name));
            context.expect(setHandled, "'mental kb set' was not handled");
            context.expect("lunar".equals(context.sync(() ->
                            mental.services().knockbackProfiles().resolve(target.player()).name())),
                    "command override did not apply");

            boolean resetHandled = context.sync(() -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(), "mental kb reset " + name));
            context.expect(resetHandled, "'mental kb reset' was not handled");
            context.expect(context.sync(() -> mental.services().knockbackProfiles()
                            .override(target.uuid())) == null,
                    "command reset did not clear the override");

            boolean overviewHandled = context.sync(() -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(), "mental kb"));
            context.expect(overviewHandled, "'mental kb' overview was not handled");
        } finally {
            context.syncRun(target::remove);
        }
    }

    /** API contract: validation rejects unknowns; the change event fires per transition. */
    private static void runApiContractScenario(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer player = new FakePlayer(tester, mental.services().scheduling());
        List<String> transitions = new CopyOnWriteArrayList<>();
        Listener probe = new Listener() {
            @EventHandler
            public void onChange(PlayerKnockbackProfileChangeEvent event) {
                transitions.add(event.getPreviousProfile() + "->" + event.getNewProfile());
            }
        };

        try {
            context.syncRun(() -> {
                player.spawn(Arena.prepare(Bukkit.getWorlds().get(0)));
                Bukkit.getPluginManager().registerEvents(probe, tester);
            });
            context.awaitTicks(3);

            context.syncRun(() -> {
                var profiles = mental.services().knockbackProfiles();
                context.expect(!profiles.setOverride(player.player(), "minemen-exact"),
                        "an unknown profile name must be rejected");
                context.expect(profiles.setOverride(player.player(), "kohi"), "set kohi");
                context.expect(profiles.setOverride(player.player(), "kohi"),
                        "re-setting the same profile is a no-op success");
                context.expect(profiles.setOverride(player.player(), "mmc"), "switch to mmc");
                context.expect(profiles.setOverride(player.player(), null), "clear");
            });

            context.expect(List.of("null->kohi", "kohi->mmc", "mmc->null").equals(transitions),
                    "change events must fire once per actual transition, got " + transitions);
        } finally {
            context.syncRun(() -> {
                HandlerList.unregisterAll(probe);
                player.remove();
            });
        }
    }
}
