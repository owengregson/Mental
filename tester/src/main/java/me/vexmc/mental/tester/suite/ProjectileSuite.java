package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.MentalPlugin;
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
 * A zero-damage snowball hit must receive the substituted damage so
 * vanilla's own knockback re-engages.
 */
public final class ProjectileSuite {

    private ProjectileSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(new TestCase("projectile: snowball hit substitutes knockback damage", context -> {
            Captors captors = Captors.register(tester);
            FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

            try {
                Location victimSpot = context.sync(() -> {
                    Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                    Location spot = Arena.offset(centre, 3, 0);
                    victim.spawn(spot);
                    return spot;
                });
                context.awaitTicks(5);

                Snowball[] tracked = new Snowball[1];
                context.syncRun(() -> {
                    victim.player().setNoDamageTicks(0);
                    // Point-blank: the snowball's first flight step crosses the
                    // victim's hitbox, so the hit cannot depend on long-range physics.
                    Location launch = victimSpot.clone().add(0, 1.0, -0.8);
                    tracked[0] = victimSpot.getWorld().spawn(launch, Snowball.class);
                    tracked[0].setVelocity(new Vector(0, 0.0, 0.5));
                });

                Double observed = null;
                for (int attempt = 0; attempt < 12 && observed == null; attempt++) {
                    context.awaitTicks(5);
                    observed = captors.damageOf(victim.uuid());
                    if (observed == null) {
                        String state = context.sync(() -> "snowball alive=" + tracked[0].isValid()
                                + " at " + tracked[0].getLocation().toVector()
                                + " victim at " + victim.player().getLocation().toVector()
                                + " hitEvent=" + captors.projectileHitOn(victim.uuid()));
                        context.note(state);
                    }
                }

                double expected = mental.services().config().projectileKnockback().snowballDamage();
                context.expect(observed != null, "snowball never registered a damage event");
                context.expectNear(expected, observed, 1.0e-6, "substituted snowball damage");
            } finally {
                context.syncRun(victim::remove);
                captors.unregister();
            }
        }));
    }
}
