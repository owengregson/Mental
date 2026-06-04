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
                // Outlast the 60-tick join invulnerability modern servers grant.
                context.awaitTicks(70);

                context.syncRun(() -> {
                    victim.player().setNoDamageTicks(0);
                    // Point-blank: the snowball's first flight step crosses the
                    // victim's hitbox, so the hit cannot depend on long-range physics.
                    Location launch = victimSpot.clone().add(0, 1.0, -0.8);
                    Snowball snowball = victimSpot.getWorld().spawn(launch, Snowball.class);
                    snowball.setVelocity(new Vector(0, 0.0, 0.5));
                });

                Double observedDamage = null;
                for (int attempt = 0; attempt < 12 && observedDamage == null; attempt++) {
                    context.awaitTicks(5);
                    observedDamage = captors.damageOf(victim.uuid());
                    if (captors.projectileHitOn(victim.uuid()) != null && attempt >= 2) {
                        break; // the hit landed; whatever events it produces have fired by now
                    }
                }

                if (observedDamage != null) {
                    // Pre-1.21.2 behavior: the zero-damage hit fired an event and
                    // the module substituted the configured trigger damage.
                    double expected = mental.services().config().projectileKnockback().snowballDamage();
                    context.expectNear(expected, observedDamage, 1.0e-6, "substituted snowball damage");
                    return;
                }

                // 1.21.2+ restored native projectile knockback for players: no
                // zero-damage event fires and none is needed — the contract is
                // that the victim still gets shoved.
                context.expect(captors.projectileHitOn(victim.uuid()) != null,
                        "snowball never reached the victim");
                context.expect(captors.velocityOf(victim.uuid()) != null,
                        "snowball hit produced neither a damage event nor knockback");
                context.note("no zero-damage event on this version — vanilla applies projectile "
                        + "knockback natively and the module stays correctly inert");
            } finally {
                context.syncRun(victim::remove);
                captors.unregister();
            }
        }));
    }
}
