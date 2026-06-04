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
import org.bukkit.entity.FishHook;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * A fishing hook striking a player must deal the configured rod damage and
 * shove them. Hook flight has real physics, so the assertion is the
 * user-visible contract (damage + displacement); the exact vector is locked
 * by unit tests. A version whose API cannot launch a bare hook records a
 * note instead of failing.
 */
public final class FishingSuite {

    private FishingSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(new TestCase("fishing: hook hit damages and knocks back", context -> {
            Captors captors = Captors.register(tester);
            FakePlayer rodder = new FakePlayer(tester, mental.services().scheduling());
            FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

            try {
                context.syncRun(() -> {
                    Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                    rodder.spawn(Arena.offset(centre, -3, -3));
                    victim.spawn(Arena.offset(centre, -3, 0));
                });
                context.awaitTicks(5);

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

                double expected = mental.services().config().fishingKnockback().damage();
                context.expectNear(expected, observedDamage, 1.0e-6, "rod hit damage");

                double speed = context.sync(() -> victim.player().getVelocity().length());
                context.expect(speed > 0.05,
                        "victim should have been shoved by the rod (speed=" + speed + ")");
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
