package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.MentalPlugin;
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
 * A real hit between two synthetic players must produce exactly the vector
 * the engine computes from the same pre-hit state — the full event chain
 * (damage → module stash → velocity event) verified end to end on a live
 * server.
 */
public final class KnockbackSuite {

    private static final double EPSILON = 1.0e-3;

    private KnockbackSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("knockback: plain hit matches the engine vector", context ->
                        runScenario(mental, tester, context, false)),
                new TestCase("knockback: sprint hit matches the engine vector", context ->
                        runScenario(mental, tester, context, true)),
                new TestCase("knockback: second hit stacks the decayed residual (1.7.10 combos)", context ->
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
            // Outlast the 60-tick join invulnerability modern servers grant.
            context.awaitTicks(70);

            KnockbackVector expected = context.sync(() -> {
                attacker.player().setSprinting(sprinting);
                victim.player().setNoDamageTicks(0);
                EntityState attackerState = EntityState.capture(attacker.player());
                // A fresh victim has no residual: the ledger the module
                // consumes reads zero motion, exactly as a 1.7.10 server's
                // untouched fields would.
                EntityState victimState = restingVictim(victim);
                KnockbackVector vector = KnockbackEngine.compute(
                        attackerState, victimState,
                        mental.services().knockbackProfiles().resolve(victim.player()), null);
                attacker.attack(victim.player());
                return vector;
            });
            context.expect(expected != null, "engine returned no vector for an unresisted hit");

            context.awaitTicks(3);

            Vector applied = captors.velocityOf(victim.uuid());
            context.expect(applied != null,
                    "no PlayerVelocityEvent observed for the victim — knockback never applied"
                            + " (damage event: " + captors.damageOf(victim.uuid()) + ")");
            context.expectNear(expected.x(), applied.getX(), EPSILON, "knockback x");
            context.expectNear(expected.y(), applied.getY(), EPSILON, "knockback y");
            context.expectNear(expected.z(), applied.getZ(), EPSILON, "knockback z");
            if (sprinting) {
                context.expect(applied.getY() > 0.45,
                        "sprint hit must exceed the 0.4 vertical limit (got " + applied.getY() + ")");
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
     * The 1.7.10 combo: a second hit shortly after the first must compute
     * from the first knock's friction-decayed residual, landing harder than
     * the first. Fake players tick real physics and read grounded, so the
     * residual decays with ground drag — the gap stays short enough for the
     * stack to remain well above the epsilon.
     *
     * <p>The ledger decays on wall-clock ticks while the suite waits on
     * game ticks, so the expectation is computed for the elapsed tick count
     * ±1; the candidates sit ~8e-3 apart, far beyond the 1e-3 epsilon, so
     * exactly one can match.</p>
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
            // Outlast the 60-tick join invulnerability modern servers grant.
            context.awaitTicks(70);

            KnockbackVector first = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityState.capture(attacker.player()), restingVictim(victim),
                        mental.services().knockbackProfiles().resolve(victim.player()), null);
                attacker.attack(victim.player());
                return vector;
            });
            context.expect(first != null, "engine returned no vector for the first hit");

            context.awaitTicks(gapTicks);

            Vector appliedFirst = captors.velocityOf(victim.uuid());
            context.expect(appliedFirst != null, "first hit produced no velocity event");
            context.expectNear(first.y(), appliedFirst.getY(), EPSILON, "first hit y");
            captors.reset();

            List<KnockbackVector> candidates = context.sync(() -> {
                boolean grounded = victim.player().isOnGround();
                victim.player().setNoDamageTicks(0);
                EntityState attackerState = EntityState.capture(attacker.player());
                EntityState resting = restingVictim(victim);
                List<KnockbackVector> expected = new ArrayList<>();
                for (int elapsed = gapTicks - 1; elapsed <= gapTicks + 1; elapsed++) {
                    VictimMotion.Motion residual = VictimMotion.decay(
                            first.x(), first.y(), first.z(), elapsed, grounded,
                            VictimMotion.DEFAULT_GRAVITY);
                    expected.add(KnockbackEngine.compute(
                            attackerState,
                            new EntityState(
                                    resting.x(), resting.y(), resting.z(), resting.yaw(),
                                    residual.vx(), residual.vy(), residual.vz(),
                                    resting.grounded(), resting.sprinting(),
                                    resting.knockbackEnchantLevel(),
                                    resting.knockbackResistance()),
                            mental.services().knockbackProfiles().resolve(victim.player()), null));
                }
                attacker.attack(victim.player());
                return expected;
            });

            context.awaitTicks(3);
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
                    "second hit did not match any residual candidate (best delta " + bestDelta
                            + ", applied " + appliedSecond + ")");
            context.expect(appliedSecond.getZ() > appliedFirst.getZ() + 0.005,
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

    /** The victim's engine input with an empty residual ledger: zero motion. */
    static EntityState restingVictim(FakePlayer victim) {
        EntityState live = EntityState.capture(victim.player());
        return new EntityState(
                live.x(), live.y(), live.z(), live.yaw(), 0.0, 0.0, 0.0,
                live.grounded(), live.sprinting(),
                live.knockbackEnchantLevel(), live.knockbackResistance());
    }
}
