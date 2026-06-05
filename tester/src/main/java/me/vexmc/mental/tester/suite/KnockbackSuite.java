package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.module.knockback.EntityState;
import me.vexmc.mental.module.knockback.KnockbackEngine;
import me.vexmc.mental.module.knockback.KnockbackVector;
import me.vexmc.mental.module.knockback.VictimMotion;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * End-to-end knockback delivery: the velocity a victim's velocity event
 * carries must equal the engine's vector pushed through the profile's wire
 * delivery (legacy-1.7 default: one tracker decay tick — the measured
 * 1.7.10 behavior), with combo residuals served by the live ledger.
 */
public final class KnockbackSuite {

    private static final double EPSILON = 1.0e-3;

    private KnockbackSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("knockback: plain hit matches engine + tracker delivery", context ->
                        runScenario(mental, tester, context, false)),
                new TestCase("knockback: sprint hit matches engine + tracker delivery", context ->
                        runScenario(mental, tester, context, true)),
                new TestCase("knockback: second hit stacks the ledger residual (1.7.10 combos)", context ->
                        runComboScenario(mental, tester, context)));
    }

    private static void runScenario(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context, boolean sprinting)
            throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            // Land and settle — spawn invulnerability is cleared at spawn.
            context.awaitTicks(5);

            KnockbackVector expected = context.sync(() -> {
                attacker.player().setSprinting(sprinting);
                victim.player().setNoDamageTicks(0);
                EntityState attackerState = EntityState.capture(attacker.player());
                // A fresh grounded victim reads the gravity equilibrium —
                // the era servers ticked player physics, so motY parked at
                // −0.0784, never zero (measured: standing hits are 0.3608-
                // based, sprint 0.4608, before the wire decay).
                EntityState victimState = restingVictim(victim);
                KnockbackProfile profile =
                        mental.services().knockbackProfiles().resolve(victim.player());
                KnockbackVector vector = KnockbackEngine.compute(
                        attackerState, victimState, profile, null);
                // Spawn placement can emit its own velocity event; clear it
                // so the next captured event is the knock's, not stale state.
                captors.reset();
                attacker.attack(victim.player());
                return SuiteDelivery.melee(vector, profile, victimState.grounded());
            });
            context.expect(expected != null, "engine returned no vector for an unresisted hit");

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the knock's velocity event");

            Vector applied = captors.velocityOf(victim.uuid());
            context.expect(applied != null,
                    "no PlayerVelocityEvent observed for the victim — knockback never applied"
                            + " (damage event: " + captors.damageOf(victim.uuid()) + ")");
            context.expectNear(expected.x(), applied.getX(), EPSILON, "knockback x");
            context.expectNear(expected.y(), applied.getY(), EPSILON, "knockback y");
            context.expectNear(expected.z(), applied.getZ(), EPSILON, "knockback z");
            if (sprinting) {
                // the full-stamp era wire for a standing sprint hit — what
                // 1.8.9 always shipped and 1.7.10 shipped to victims who
                // joined before their attacker (measured 0.4607 on both)
                context.expectNear(0.4608, applied.getY(), 5.0e-3,
                        "sprint hit vertical (era wire value)");
            }
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /**
     * The 1.7.10 combo: the second hit computes from the live ledger —
     * which, after the first knock's liftoff, holds the JUMP STAMP free-fall
     * (the era movement handler overwrote motY with 0.42 at liftoff), not
     * the delivered vertical. Expectations read the same ledger the module
     * consumes, at the elapsed tick ±1 (wall/game skew).
     */
    private static void runComboScenario(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());
        int gapTicks = 4;

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            // Land and settle — spawn invulnerability is cleared at spawn.
            context.awaitTicks(5);

            KnockbackProfile profile = context.sync(
                    () -> mental.services().knockbackProfiles().resolve(victim.player()));

            KnockbackVector first = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                EntityState victimState = restingVictim(victim);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityState.capture(attacker.player()), victimState, profile, null);
                captors.reset();
                attacker.attack(victim.player());
                return SuiteDelivery.melee(vector, profile, victimState.grounded());
            });
            context.expect(first != null, "engine returned no vector for the first hit");

            context.awaitTicks(gapTicks);
            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the first hit's velocity event");

            Vector appliedFirst = captors.velocityOf(victim.uuid());
            context.expect(appliedFirst != null, "first hit produced no velocity event");
            context.expectNear(first.y(), appliedFirst.getY(), EPSILON, "first hit y");
            captors.reset();

            VictimMotion ledger = mental.knockbackPipeline().ledger();
            List<KnockbackVector> candidates = context.sync(() -> {
                boolean grounded = victim.player().isOnGround();
                victim.player().setNoDamageTicks(0);
                EntityState attackerState = EntityState.capture(attacker.player());
                EntityState resting = restingVictim(victim);
                long now = System.nanoTime();
                List<KnockbackVector> expected = new ArrayList<>();
                // The live ledger at now and ±1 tick — it already carries the
                // first knock, the liftoff jump stamp, and any landing.
                for (int offset = -1; offset <= 1; offset++) {
                    VictimMotion.Motion residual = ledger.current(
                            victim.uuid(), now + offset * 50_000_000L, grounded,
                            VictimMotion.DEFAULT_GRAVITY);
                    KnockbackVector vector = KnockbackEngine.compute(
                            attackerState,
                            new EntityState(
                                    resting.x(), resting.y(), resting.z(), resting.yaw(),
                                    residual.vx(), residual.vy(), residual.vz(),
                                    grounded, resting.sprinting(),
                                    resting.knockbackEnchantLevel(),
                                    resting.knockbackResistance()),
                            profile, null);
                    expected.add(SuiteDelivery.melee(vector, profile, grounded));
                }
                attacker.attack(victim.player());
                return expected;
            });

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the second hit's velocity event");
            Vector appliedSecond = captors.velocityOf(victim.uuid());
            context.expect(appliedSecond != null, "second hit produced no velocity event");

            double bestDelta = Double.MAX_VALUE;
            for (KnockbackVector candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                double delta = Math.max(
                        Math.abs(candidate.x() - appliedSecond.getX()),
                        Math.max(Math.abs(candidate.y() - appliedSecond.getY()),
                                Math.abs(candidate.z() - appliedSecond.getZ())));
                bestDelta = Math.min(bestDelta, delta);
            }
            context.expect(bestDelta < EPSILON,
                    "second hit did not match any ledger candidate (best delta " + bestDelta
                            + ", applied " + appliedSecond + ")");
            // The residual margin after two tracker decays is small when the
            // victim reads grounded at both hits (legacy versions keep
            // onGround true while rising) — strictly-greater proves the
            // compounding; the candidate match above pins the exact value.
            context.expect(appliedSecond.getZ() > appliedFirst.getZ() + 1.0e-3,
                    "second hit must stack past the first (z " + appliedFirst.getZ()
                            + " -> " + appliedSecond.getZ() + ")");
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** The victim's engine input with a fresh ledger: grounded equilibrium motion. */
    static EntityState restingVictim(FakePlayer victim) {
        EntityState live = EntityState.capture(victim.player());
        double vy = live.grounded()
                ? VictimMotion.groundedEquilibrium(VictimMotion.DEFAULT_GRAVITY)
                : 0.0;
        return new EntityState(
                live.x(), live.y(), live.z(), live.yaw(), 0.0, vy, 0.0,
                live.grounded(), live.sprinting(),
                live.knockbackEnchantLevel(), live.knockbackResistance());
    }
}
