package me.vexmc.mental.tester.suite;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.module.knockback.KnockbackEngine;
import me.vexmc.mental.module.knockback.KnockbackVector;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Snowball;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * A snowball hit must knock the victim away from where the <em>shooter</em>
 * stands — the 1.7.10 positional direction — with the engine's exact base
 * vector, on every supported version. The shooter is placed diagonally off
 * the flight line, so a flight-directed knock (the modern vanilla rule)
 * cannot pass the assertion by coincidence.
 */
public final class ProjectileSuite {

    private static final double EPSILON = 1.0e-3;

    private ProjectileSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(new TestCase("projectile: snowball knocks away from the shooter", context -> {
            Captors captors = Captors.register(tester);
            FakePlayer shooter = new FakePlayer(tester, mental.services().scheduling());
            FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

            try {
                Location victimSpot = context.sync(() -> {
                    Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                    Location spot = Arena.offset(centre, 3, 0);
                    // Diagonal to the snowball's +z flight: positional knock
                    // and flight-directed knock point measurably apart.
                    shooter.spawn(Arena.offset(centre, 0, -3));
                    victim.spawn(spot);
                    return spot;
                });
                // Outlast the 60-tick join invulnerability modern servers grant.
                context.awaitTicks(70);

                KnockbackVector expected = context.sync(() -> {
                    victim.player().setNoDamageTicks(0);
                    // Point-blank: the snowball's first flight step crosses the
                    // victim's hitbox, so the hit cannot depend on long-range physics.
                    Location launch = victimSpot.clone().add(0, 1.0, -0.8);
                    Snowball snowball = victimSpot.getWorld().spawn(launch, Snowball.class);
                    snowball.setShooter(shooter.player());
                    snowball.setVelocity(new Vector(0, 0.0, 0.5));
                    return KnockbackEngine.computeBase(
                            KnockbackSuite.restingVictim(victim),
                            shooter.player().getLocation().getX(),
                            shooter.player().getLocation().getZ(),
                            mental.services().config().knockback(), null,
                            ThreadLocalRandom.current());
                });
                context.expect(expected != null, "engine returned no vector for an unresisted hit");

                Vector applied = null;
                for (int attempt = 0; attempt < 12 && applied == null; attempt++) {
                    context.awaitTicks(5);
                    applied = captors.velocityOf(victim.uuid());
                }

                context.expect(captors.projectileHitOn(victim.uuid()) != null,
                        "snowball never reached the victim");
                context.expect(applied != null,
                        "snowball hit produced no knockback velocity"
                                + " (damage event: " + captors.damageOf(victim.uuid()) + ")");
                context.expectNear(expected.x(), applied.getX(), EPSILON, "projectile knockback x");
                context.expectNear(expected.y(), applied.getY(), EPSILON, "projectile knockback y");
                context.expectNear(expected.z(), applied.getZ(), EPSILON, "projectile knockback z");

                Double observedDamage = captors.damageOf(victim.uuid());
                if (observedDamage != null) {
                    double substituted = mental.services().config().projectileKnockback().snowballDamage();
                    context.expectNear(substituted, observedDamage, 1.0e-6, "substituted snowball damage");
                } else {
                    context.note("no zero-damage event on this version (1.21.2+ native path) — "
                            + "Mental's positional knock still applied and was verified above");
                }
            } finally {
                context.syncRun(() -> {
                    shooter.remove();
                    victim.remove();
                });
                captors.unregister();
            }
        }));
    }
}
