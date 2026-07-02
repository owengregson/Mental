package me.vexmc.mental.tester.suite;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.settings.ProjectileKnockbackSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
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

    @SuppressWarnings("unchecked")
    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(new TestCase("projectile: snowball knocks away from the shooter", context -> {
            Captors captors = Captors.register(tester);
            FakePlayer shooter = new FakePlayer(tester, mental.scheduling());
            FakePlayer victim = new FakePlayer(tester, mental.scheduling());

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
                // Land and settle — spawn invulnerability is cleared at spawn.
                context.awaitTicks(5);

                KnockbackVector expected = context.sync(() -> {
                    victim.player().setNoDamageTicks(0);
                    // Point-blank: the snowball's first flight step crosses the
                    // victim's hitbox, so the hit cannot depend on long-range physics.
                    Location launch = victimSpot.clone().add(0, 1.0, -0.8);
                    Snowball snowball = victimSpot.getWorld().spawn(launch, Snowball.class);
                    snowball.setShooter(shooter.player());
                    snowball.setVelocity(new Vector(0, 0.0, 0.5));
                    var victimState = KnockbackSuite.restingVictim(victim);
                    var profile = mental.snapshot().profileFor(victim.player().getWorld().getName());
                    return SuiteDelivery.projectile(
                            KnockbackEngine.computeBase(
                                    victimState,
                                    shooter.player().getLocation().getX(),
                                    shooter.player().getLocation().getZ(),
                                    profile, null,
                                    ThreadLocalRandom.current()),
                            profile, victimState.grounded());
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
                if (observedDamage == null) {
                    context.note("no zero-damage event on this version — Mental's positional knock "
                            + "still applied and was verified above");
                } else if (mental.platformProfile().projectileKnockbackRestored()) {
                    // F4: on 1.21.2+ (vanilla restored projectile knockback) the
                    // negligible-damage substitution is a NO-OP — the hit keeps
                    // its era-true zero damage; the era vector was asserted above.
                    context.expectNear(0.0, observedDamage, 1.0e-6,
                            "era-true zero damage (the 1.21.2+ substitution no-op)");
                } else {
                    ProjectileKnockbackSettings settings = mental.snapshot().settings(
                            (SettingsKey<ProjectileKnockbackSettings>) Feature.PROJECTILE_KNOCKBACK.settingsKey());
                    double substituted = settings.snowballDamage();
                    context.expectNear(substituted, observedDamage, 1.0e-6, "substituted snowball damage");
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
