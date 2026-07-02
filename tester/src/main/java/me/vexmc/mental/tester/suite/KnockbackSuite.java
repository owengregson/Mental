package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.kernel.ledger.MotionLedger;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.MentalPluginV5;
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
 * delivery (legacy-1.7 default: the full stamp — the measured 1.7.10 wire),
 * with combo residuals served by the live per-session ledger.
 *
 * <p>v5: the expectation math is the kernel {@link KnockbackEngine}/{@link Decay}
 * authority and the victim capture is the production {@link EntityStates}
 * capture, so the suite and the shipped values share one source; combo residuals
 * are read from the session's own {@link MotionLedger} (tick-based).</p>
 */
public final class KnockbackSuite {

    private static final double EPSILON = 1.0e-3;

    private KnockbackSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("knockback: plain hit matches engine + tracker delivery", context ->
                        runScenario(mental, tester, context, false)),
                new TestCase("knockback: sprint hit matches engine + tracker delivery", context ->
                        runScenario(mental, tester, context, true)),
                new TestCase("knockback: second hit stacks the ledger residual (1.7.10 combos)", context ->
                        runComboScenario(mental, tester, context)));
    }

    private static void runScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context, boolean sprinting)
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
            // Land and settle — spawn invulnerability is cleared at spawn.
            context.awaitTicks(5);

            KnockbackVector expected = context.sync(() -> {
                attacker.player().setSprinting(sprinting);
                victim.player().setNoDamageTicks(0);
                EntityState attackerState = EntityStates.capture(attacker.player());
                // A fresh grounded victim reads the gravity equilibrium —
                // the era servers ticked player physics, so motY parked at
                // −0.0784, never zero (measured: standing hits are 0.3608-
                // based, sprint 0.4608, before the wire decay).
                EntityState victimState = restingVictim(victim);
                KnockbackProfile profile = profileFor(mental, victim);
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
     * The 1.7.10 combo: the second hit computes from the live per-session ledger
     * — which, after the first knock, holds the delivered residual decaying at
     * ground friction, so the second base compounds it. The expectation reads the
     * same ledger the knockback unit consumes, through the production victim
     * capture; the unit's owning-thread read runs synchronously inside
     * {@code attack()} on the same tick, so a single candidate matches (a
     * one-tick-later decay candidate absorbs any scheduling slack).
     */
    private static void runComboScenario(
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
            // Land and settle — spawn invulnerability is cleared at spawn.
            context.awaitTicks(5);

            KnockbackProfile profile = context.sync(() -> profileFor(mental, victim));

            KnockbackVector first = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                EntityState victimState = restingVictim(victim);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityStates.capture(attacker.player()), victimState, profile, null);
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

            CombatSession session = mental.sessions().sessionFor(victim.uuid());
            context.expect(session != null, "no combat session for the victim");
            MotionLedger ledger = session.ledger();
            List<KnockbackVector> candidates = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                EntityState attackerState = EntityStates.capture(attacker.player());
                // The production victim capture over the session ledger — exactly
                // what the knockback unit reads on the same tick. It already
                // carries the first knock decaying at ground friction.
                EntityState victimState = EntityStates.captureVictim(victim.player(), ledger);
                List<KnockbackVector> expected = new ArrayList<>();
                expected.add(SuiteDelivery.melee(
                        KnockbackEngine.compute(attackerState, victimState, profile, null),
                        profile, victimState.grounded()));
                // One-tick-later alignment: the unit's read can land a session
                // tick after this one under scheduling slack.
                Decay.Motion later = Decay.decayOnce(
                        victimState.vx(), victimState.vy(), victimState.vz(),
                        victimState.grounded(), Decay.DEFAULT_GRAVITY);
                EntityState laterState = new EntityState(
                        victimState.x(), victimState.y(), victimState.z(), victimState.yaw(),
                        later.vx(), later.vy(), later.vz(),
                        victimState.grounded(), victimState.sprinting(),
                        victimState.knockbackEnchantLevel(), victimState.knockbackResistance());
                expected.add(SuiteDelivery.melee(
                        KnockbackEngine.compute(attackerState, laterState, profile, null),
                        profile, laterState.grounded()));
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
            // The residual margin after the ground-friction decay is small when
            // the victim reads grounded at both hits (legacy versions keep
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

    /** The knockback profile governing the victim's world (the v5 snapshot resolution). */
    static KnockbackProfile profileFor(MentalPluginV5 mental, FakePlayer victim) {
        return mental.snapshot().profileFor(victim.player().getWorld().getName());
    }

    /** The victim's engine input with a fresh ledger: grounded equilibrium motion. */
    static EntityState restingVictim(FakePlayer victim) {
        EntityState live = EntityStates.capture(victim.player());
        double vy = live.grounded()
                ? Decay.groundedEquilibrium(Decay.DEFAULT_GRAVITY)
                : 0.0;
        return new EntityState(
                live.x(), live.y(), live.z(), live.yaw(), 0.0, vy, 0.0,
                live.grounded(), live.sprinting(),
                live.knockbackEnchantLevel(), live.knockbackResistance());
    }
}
