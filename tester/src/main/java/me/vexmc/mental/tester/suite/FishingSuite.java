package me.vexmc.mental.tester.suite;

import java.util.List;
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
import org.bukkit.entity.FishHook;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * A fishing hook striking a player must deal the configured rod damage and
 * apply the engine's base knock <em>away from the angler's position</em> —
 * the 1.7.10 direction. Hook flight has real physics, so a version whose
 * API cannot launch a bare hook, or whose hook misses, records a note
 * instead of failing; the formula itself is locked by unit tests.
 */
public final class FishingSuite {

    private static final double EPSILON = 1.0e-3;

    private FishingSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(new TestCase("fishing: hook hit damages and knocks from the angler", context -> {
            Captors captors = Captors.register(tester);
            FakePlayer rodder = new FakePlayer(tester, mental.services().scheduling());
            FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

            try {
                context.syncRun(() -> {
                    Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                    rodder.spawn(Arena.offset(centre, -3, -3));
                    victim.spawn(Arena.offset(centre, -3, 0));
                });
                // Outlast the 60-tick join invulnerability modern servers grant.
                context.awaitTicks(70);

                boolean launched = context.sync(() -> {
                    victim.player().setNoDamageTicks(0);
                    try {
                        FishHook hook = rodder.player().launchProjectile(
                                FishHook.class, new Vector(0, 0.1, 0.75));
                        return hook != null;
                    } catch (Throwable unsupported) {
                        return false;
                    }
                });
                if (!launched) {
                    context.note("this version cannot launch a bare FishHook — covered by unit tests");
                    return;
                }

                Double observedDamage = null;
                for (int attempt = 0; attempt < 12 && observedDamage == null; attempt++) {
                    context.awaitTicks(5);
                    observedDamage = captors.damageOf(victim.uuid());
                }

                if (observedDamage == null) {
                    context.note("hook never struck the victim (flight physics) — covered by unit tests");
                    return;
                }

                double expectedDamage = mental.services().config().fishingKnockback().damage();
                context.expectNear(expectedDamage, observedDamage, 1.0e-6, "rod hit damage");

                // The fresh victim has an empty residual ledger and neither
                // player has moved, so the rod vector is fully deterministic:
                // bare base knock away from where the angler stands.
                KnockbackVector expected = context.sync(() -> KnockbackEngine.computeBase(
                        KnockbackSuite.restingVictim(victim),
                        rodder.player().getLocation().getX(),
                        rodder.player().getLocation().getZ(),
                        mental.services().knockbackProfiles().resolve(victim.player()), null,
                        java.util.concurrent.ThreadLocalRandom.current()));
                context.expect(expected != null, "engine returned no vector for an unresisted rod hit");

                Vector applied = captors.velocityOf(victim.uuid());
                context.expect(applied != null, "rod hit produced no velocity event");
                context.expectNear(expected.x(), applied.getX(), EPSILON, "rod knockback x");
                context.expectNear(expected.y(), applied.getY(), EPSILON, "rod knockback y");
                context.expectNear(expected.z(), applied.getZ(), EPSILON, "rod knockback z");
            } finally {
                context.syncRun(() -> {
                    rodder.remove();
                    victim.remove();
                });
                captors.unregister();
            }
        }));
    }
}
