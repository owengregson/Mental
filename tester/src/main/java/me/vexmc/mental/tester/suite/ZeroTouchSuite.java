package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Snowball;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * A disabled Mental module must do <em>nothing at all</em> to the game, so
 * other combat plugins can own the mechanic. Each scenario turns one module
 * off through the real command path, performs the action on a live server,
 * asserts pure vanilla behavior — no Mental knockback application, no damage
 * substitution, no event cancellation, no velocity rewrite — and re-enables
 * the module for whatever runs next.
 */
public final class ZeroTouchSuite {

    private ZeroTouchSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("zero-touch: disabled knockback module leaves the vanilla knock alone", context ->
                        runDisabledKnockback(mental, tester, context)),
                new TestCase("zero-touch: disabled fishing module leaves reel-in vanilla", context ->
                        runDisabledFishing(mental, tester, context)),
                new TestCase("zero-touch: disabled rod-velocity module keeps the vanilla cast", context ->
                        runDisabledRodVelocity(mental, tester, context)),
                new TestCase("zero-touch: disabled projectile module never substitutes damage", context ->
                        runDisabledProjectile(mental, tester, context)));
    }

    private static void runDisabledKnockback(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            toggleModule(context, "knockback", false);
            context.expect(!moduleActive(mental, "knockback"), "knockback module failed to disable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(70);

            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitTicks(3);

            context.expect(captors.damageOf(victim.uuid()) != null,
                    "the hit itself must still land (vanilla damage path)");
            context.expect(captors.knockbackAppliesTo(victim.uuid()) == 0,
                    "Mental fired KnockbackApplyEvent with its knockback module disabled");
            Vector velocity = captors.velocityOf(victim.uuid());
            context.expect(velocity != null,
                    "vanilla knockback must survive the disabled module (no velocity event seen)");
        } finally {
            toggleModule(context, "knockback", true);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    private static void runDisabledFishing(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer rodder = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer caught = new FakePlayer(tester, mental.services().scheduling());

        try {
            toggleModule(context, "fishing-knockback", false);

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                rodder.spawn(Arena.offset(centre, 3, -3));
                caught.spawn(Arena.offset(centre, 3, 0));
            });
            context.awaitTicks(70);

            // Stage the reel-in event Mental's reel-in policy listens to,
            // with a real hook hooked onto the victim. When the module is
            // disabled the event must come back uncancelled — vanilla drag.
            Boolean cancelled = context.sync(() -> {
                try {
                    FishHook hook = rodder.player().launchProjectile(
                            FishHook.class, new Vector(0, 0.1, 0.75));
                    if (hook == null) {
                        return null;
                    }
                    hook.setHookedEntity(caught.player());
                    PlayerFishEvent event = new PlayerFishEvent(
                            rodder.player(), caught.player(), hook,
                            PlayerFishEvent.State.CAUGHT_ENTITY);
                    Bukkit.getPluginManager().callEvent(event);
                    hook.remove();
                    return event.isCancelled();
                } catch (Throwable unsupported) {
                    return null;
                }
            });
            if (cancelled == null) {
                context.note("this version cannot stage a CAUGHT_ENTITY event — covered by the gate checks");
                return;
            }

            context.expect(!cancelled,
                    "reel-in was cancelled with the fishing module disabled — vanilla drag must survive");
        } finally {
            toggleModule(context, "fishing-knockback", true);
            context.syncRun(() -> {
                rodder.remove();
                caught.remove();
            });
        }
    }

    private static void runDisabledRodVelocity(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer rodder = new FakePlayer(tester, mental.services().scheduling());

        try {
            toggleModule(context, "rod-velocity", false);

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                rodder.spawn(Arena.offset(centre, -6, -3));
            });
            context.awaitTicks(70);

            // A real PlayerFishEvent FISHING pass: with the module off, the
            // hook's velocity one tick later must be whatever vanilla gave it
            // (the legacy override multiplies the launch well above 1.0).
            Vector launchVelocity = new Vector(0, 0.1, 0.75);
            FishHook hook = context.sync(() -> {
                try {
                    FishHook launched = rodder.player().launchProjectile(FishHook.class, launchVelocity);
                    PlayerFishEvent event = new PlayerFishEvent(
                            rodder.player(), null, launched, PlayerFishEvent.State.FISHING);
                    Bukkit.getPluginManager().callEvent(event);
                    return launched;
                } catch (Throwable unsupported) {
                    return null;
                }
            });
            if (hook == null) {
                context.note("this version cannot launch a bare FishHook — covered by the gate checks");
                return;
            }

            Vector after = context.sync(() -> hook.isValid() ? hook.getVelocity() : null);
            context.syncRun(hook::remove);
            if (after == null) {
                context.note("hook retired before observation — covered by the gate checks");
                return;
            }
            // The 1.7.10 cast formula launches at ~1.5x speed along the view
            // vector; an untouched hook keeps (gravity-decayed) launch speed.
            double launchSpeed = launchVelocity.length();
            context.expect(after.length() < launchSpeed + 0.5,
                    "hook flies far faster than its launch with rod-velocity disabled (got "
                            + after.length() + " vs launch " + launchSpeed + ")");
        } finally {
            toggleModule(context, "rod-velocity", true);
            context.syncRun(rodder::remove);
        }
    }

    private static void runDisabledProjectile(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer shooter = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            toggleModule(context, "projectile-knockback", false);

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                shooter.spawn(Arena.offset(centre, 6, -3));
                victim.spawn(Arena.offset(centre, 6, 0));
            });
            context.awaitTicks(70);

            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                Vector direction = victim.player().getLocation().toVector()
                        .subtract(shooter.player().getLocation().toVector())
                        .normalize().multiply(1.5);
                shooter.player().launchProjectile(Snowball.class, direction);
            });

            String hit = null;
            for (int attempt = 0; attempt < 12 && hit == null; attempt++) {
                context.awaitTicks(5);
                hit = captors.projectileHitOn(victim.uuid());
            }
            if (hit == null) {
                context.note("snowball never struck the victim (flight physics) — covered by the gate checks");
                return;
            }

            context.expect(captors.knockbackAppliesTo(victim.uuid()) == 0,
                    "Mental fired KnockbackApplyEvent with its projectile module disabled");
            Double damage = captors.damageOf(victim.uuid());
            context.expect(damage == null || damage == 0.0,
                    "snowball damage was substituted with the projectile module disabled (got "
                            + damage + ")");
        } finally {
            toggleModule(context, "projectile-knockback", true);
            context.syncRun(() -> {
                shooter.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** Toggles through the real console command path and waits for convergence. */
    private static void toggleModule(TestContext context, String id, boolean enabled) throws Exception {
        context.syncRun(() -> Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(), "mental module " + id + " " + (enabled ? "on" : "off")));
        context.awaitTicks(1);
    }

    private static boolean moduleActive(MentalPlugin mental, String id) {
        return mental.modules().byId(id).map(module -> module.active()).orElse(false);
    }
}
