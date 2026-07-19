package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.api.timing.HitTimingOverrides;
import me.vexmc.mental.kernel.timing.WindowPricing;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.damage.Ct8cIframesUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Live coverage for the public {@code HitTimingOverrides} service (the timing-
 * override doc §5 acceptance tests). Each case resolves the service the way a
 * consumer does — off the {@code ServicesManager} — and asserts the mechanism
 * where a clientless {@link FakePlayer} can observe it: the physical
 * {@code maximumNoDamageTicks} WINDOW write a live override produces. That write
 * IS the per-hit pricing — vanilla resets the hurt counter from it, so a shrunk
 * window is a faster re-hit — and it is visible synchronously the instant the
 * pre-apply damage handler returns (the same read {@link CombatTest8cSuite}'s
 * i-frame case makes), so no wire round-trip is needed.
 *
 * <p>The counter interaction the fast path adds (frozen-gate admission, the region
 * adopt/reject predicates) reads the live, now-scaled window and follows the field
 * automatically; those seams are unit-pinned and the fast path itself is not
 * reachable by a fake attack, so the WINDOW write is the honest live discriminator
 * here. Everything is a HARD expect; the override is always cleared and every
 * feature toggled back off in {@code finally}, so the plugin is transparent for
 * whatever runs next.
 */
public final class TimingOverrideSuite {

    private TimingOverrideSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("timing-override: the service is published on the ServicesManager", context ->
                        runRegistration(context)),
                new TestCase("timing-override: a plain-profile pair re-prices its window, a third attacker does not",
                        context -> runPlainProfile(mental, tester, context)),
                new TestCase("timing-override: the ct8c per-hit window composes as min(delay,10)*factor", context ->
                        runCt8cComposition(mental, tester, context)),
                new TestCase("timing-override: an accelerated admitted hit is still a real hit (damage + knock)",
                        context -> runRealHit(mental, tester, context)),
                new TestCase("timing-override: refresh replaces the factor and the clock, never stacking", context ->
                        runRefresh(mental, tester, context)),
                new TestCase("timing-override: victim death clears the victim's pairs (no stale admission)", context ->
                        runDeathHygiene(mental, tester, context)));
    }

    /* ------------------------------------------------------------------ */
    /*  0. the registration is the capability signal                       */
    /* ------------------------------------------------------------------ */

    private static void runRegistration(TestContext context) throws Exception {
        HitTimingOverrides service = context.sync(TimingOverrideSuite::resolve);
        context.expect(service != null,
                "the HitTimingOverrides service must be published on the ServicesManager — the registration IS "
                        + "the capability a consumer probes");
    }

    /* ------------------------------------------------------------------ */
    /*  1. plain profile — the fallback window writer, per-attacker         */
    /* ------------------------------------------------------------------ */

    /**
     * On a profile that does not already shrink the window (ct8c off), the service
     * owns the per-hit window write: an accelerated pair's hit prices the victim's
     * window to {@code round(baseline * factor)} (a MAX write, never a counter
     * write). A third attacker's hit — with no override — writes nothing, so it
     * faces the full vanilla window; and once the pair's clock lapses the override
     * attacker faces the full window again. All three off the victim's real
     * {@code maximumNoDamageTicks} field.
     */
    private static void runPlainProfile(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer third = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        HitTimingOverrides service = context.sync(TimingOverrideSuite::resolve);
        context.expect(service != null, "the timing-override service must resolve");
        if (service == null) {
            return;
        }
        try {
            // Defensive: this case is the PLAIN profile — the fallback owns the
            // write only while ct8c does not. (Every suite restores it OFF, but
            // pin it here so the case can never read a ct8c-shaped window.)
            setFeature(mental, context, Feature.CT8C_IFRAMES, false);
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                third.spawn(Arena.offset(centre, 2, 0));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            int vanillaWindow = context.sync(() -> victim.player().getMaximumNoDamageTicks());
            context.expect(vanillaWindow > 0, "the victim must start on a positive vanilla window, was " + vanillaWindow);

            // The accelerated pair: register 0.5 for a long window, then the pair's
            // hit must shrink the window to round(vanilla * 0.5).
            context.syncRun(() -> service.overrideWindow(victim.uuid(), attacker.uuid(), 0.5, 200));
            int accelerated = windowAfterAttack(context, attacker, victim, vanillaWindow);
            int expected = WindowPricing.price(vanillaWindow, 0.5);
            context.expect(accelerated == expected,
                    "the accelerated pair must re-price the window to round(" + vanillaWindow + " * 0.5) = "
                            + expected + ", saw " + accelerated);

            // The third attacker holds NO override: its hit must leave the full
            // vanilla window (nothing global erased — no fairness guard needed).
            int thirdWindow = windowAfterAttack(context, third, victim, vanillaWindow);
            context.expect(thirdWindow == vanillaWindow,
                    "a third attacker with no override must face the full vanilla window " + vanillaWindow
                            + ", saw " + thirdWindow + " — the pair scoping leaked");

            // Expiry (S3): a short-clock override lapses, and the once-accelerated
            // pair faces the full window again.
            context.syncRun(() -> service.overrideWindow(victim.uuid(), attacker.uuid(), 0.5, 3));
            context.awaitTicks(8);
            context.expect(!context.sync(() -> service.isActive(victim.uuid(), attacker.uuid())),
                    "the short-clock override must have lapsed after its duration");
            int afterExpiry = windowAfterAttack(context, attacker, victim, vanillaWindow);
            context.expect(afterExpiry == vanillaWindow,
                    "once the clock lapses the pair faces the full window " + vanillaWindow + " again, saw "
                            + afterExpiry);
        } finally {
            context.syncRun(() -> {
                service.clearVictim(victim.uuid());
                attacker.remove();
                third.remove();
                victim.remove();
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /*  2. ct8c profile — the per-hit maximum composes                     */
    /* ------------------------------------------------------------------ */

    /**
     * On the ct8c profile the window is already {@code min(attackDelay, 10)} per
     * hit; the override composes as {@code round(that * factor)}. A diamond sword
     * (delay 7) under override(0.5) prices to {@code round(7 * 0.5) = 4}, while a
     * third attacker's own sword hit still writes the un-scaled 7 — the pricing is
     * per-hit-attacker, nothing global erased. (The vanilla COUNTER is untouched;
     * this asserts the GATE — the window field — not the counter.)
     */
    private static void runCt8cComposition(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer third = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        HitTimingOverrides service = context.sync(TimingOverrideSuite::resolve);
        context.expect(service != null, "the timing-override service must resolve");
        if (service == null) {
            return;
        }
        try {
            setFeature(mental, context, Feature.CT8C_IFRAMES, true);
            context.expect(mental.featureActive(Feature.CT8C_IFRAMES), "ct8c-iframes did not converge active");
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                third.spawn(Arena.offset(centre, 2, 0));
                victim.spawn(Arena.offset(centre, 0, 2));
                attacker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
                third.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            });
            context.awaitTicks(5);

            int swordWindow = Ct8cIframesUnit.iframeTicks(4.5); // diamond sword delay = 7

            // Control: with no override, ct8c writes the plain window (7).
            int control = windowAfterAttack(context, attacker, victim, 0);
            context.expect(control == swordWindow,
                    "plain ct8c must write the sword window " + swordWindow + ", saw " + control);

            // Override 0.5 → round(7 * 0.5) = 4 (the counter may still read ~6; the
            // GATE admitted early).
            context.syncRun(() -> service.overrideWindow(victim.uuid(), attacker.uuid(), 0.5, 200));
            int accelerated = windowAfterAttack(context, attacker, victim, 0);
            int expected = WindowPricing.price(swordWindow, 0.5);
            context.expect(accelerated == expected,
                    "the ct8c window must compose to round(" + swordWindow + " * 0.5) = " + expected
                            + ", saw " + accelerated);

            // The third attacker's own sword hit writes the un-scaled 7 (per-hit-attacker pricing).
            int thirdWindow = windowAfterAttack(context, third, victim, 0);
            context.expect(thirdWindow == swordWindow,
                    "a third attacker's hit must write the un-scaled ct8c window " + swordWindow
                            + ", saw " + thirdWindow);
        } finally {
            context.syncRun(() -> {
                service.clearVictim(victim.uuid());
                attacker.remove();
                third.remove();
                victim.remove();
            });
            setFeature(mental, context, Feature.CT8C_IFRAMES, false);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  3. an accelerated admitted hit is a REAL hit (S6)                  */
    /* ------------------------------------------------------------------ */

    /**
     * S6: the override changes only the gate's arithmetic, so an admitted
     * accelerated hit runs the whole pipeline — it deals damage and ships a
     * Mental-owned knock exactly like any other hit. The WIRE pre-send is
     * unverifiable for a clientless fake (no PacketEvents user) and is proven
     * out-of-band; here the event-level "real hit" signals are the pins.
     */
    private static void runRealHit(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        HitTimingOverrides service = context.sync(TimingOverrideSuite::resolve);
        context.expect(service != null, "the timing-override service must resolve");
        if (service == null) {
            captors.unregister();
            return;
        }
        try {
            setFeature(mental, context, Feature.CT8C_IFRAMES, true);
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
                attacker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            });
            context.awaitTicks(5);

            context.syncRun(() -> service.overrideWindow(victim.uuid(), attacker.uuid(), 0.5, 200));
            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "the accelerated hit to deal damage");
            context.expect(captors.damageOf(victim.uuid()) != null,
                    "an accelerated admitted hit must still deal damage (S6)");
            context.expect(captors.knockbackAppliesTo(victim.uuid()) >= 1,
                    "an accelerated admitted hit must still ship a Mental-owned knock (S6)");
        } finally {
            context.syncRun(() -> {
                service.clearVictim(victim.uuid());
                attacker.remove();
                victim.remove();
            });
            setFeature(mental, context, Feature.CT8C_IFRAMES, false);
            captors.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  4. refresh-not-stack (S4) — factor AND clock replaced              */
    /* ------------------------------------------------------------------ */

    /**
     * A second registration for the same pair replaces the factor and the clock —
     * it never stacks. Registering a 3-tick 0.5 override, then (before it lapses) a
     * 100-tick 0.25 override, must leave the pair live well past the first clock's
     * 3-tick lapse AND pricing at the NEW 0.25 factor (a sword window of
     * {@code round(7 * 0.25) = 2}, not the 0.5 window of 4).
     */
    private static void runRefresh(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        HitTimingOverrides service = context.sync(TimingOverrideSuite::resolve);
        context.expect(service != null, "the timing-override service must resolve");
        if (service == null) {
            return;
        }
        try {
            setFeature(mental, context, Feature.CT8C_IFRAMES, true);
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
                attacker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            });
            context.awaitTicks(5);

            context.syncRun(() -> service.overrideWindow(victim.uuid(), attacker.uuid(), 0.5, 3));
            context.awaitTicks(2);
            context.syncRun(() -> service.overrideWindow(victim.uuid(), attacker.uuid(), 0.25, 100));
            context.awaitTicks(5); // now ~7 ticks in — the first clock's 3-tick window is long gone

            context.expect(context.sync(() -> service.isActive(victim.uuid(), attacker.uuid())),
                    "the refreshed clock must keep the pair live past the first registration's 3-tick lapse");

            int swordWindow = Ct8cIframesUnit.iframeTicks(4.5); // 7
            int refreshed = windowAfterAttack(context, attacker, victim, 0);
            int expected = WindowPricing.price(swordWindow, 0.25); // round(7 * 0.25) = 2
            context.expect(refreshed == expected,
                    "the refresh must price at the NEW 0.25 factor: round(" + swordWindow + " * 0.25) = "
                            + expected + ", saw " + refreshed);
        } finally {
            context.syncRun(() -> {
                service.clearVictim(victim.uuid());
                attacker.remove();
                victim.remove();
            });
            setFeature(mental, context, Feature.CT8C_IFRAMES, false);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  5. victim death hygiene (S5)                                       */
    /* ------------------------------------------------------------------ */

    /**
     * A victim's death drops every pair touching it — so a respawn (the same UUID)
     * admits nothing stale. A live override is registered, the victim is killed
     * with {@code setHealth(0.0)} (the deterministic fake-player kill —
     * {@code PlayerDeathEvent} fires synchronously inside it, per {@code
     * FeedbackSuite}), and the pair must read inactive.
     */
    private static void runDeathHygiene(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        HitTimingOverrides service = context.sync(TimingOverrideSuite::resolve);
        context.expect(service != null, "the timing-override service must resolve");
        if (service == null) {
            return;
        }
        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            context.syncRun(() -> service.overrideWindow(victim.uuid(), attacker.uuid(), 0.5, 500));
            context.expect(context.sync(() -> service.isActive(victim.uuid(), attacker.uuid())),
                    "the override must be live before the victim dies");

            // setHealth(0.0) fires PlayerDeathEvent synchronously, whose MONITOR
            // listener clears the victim's pairs.
            context.syncRun(() -> victim.player().setHealth(0.0));
            context.awaitTicks(3);
            context.expect(!context.sync(() -> service.isActive(victim.uuid(), attacker.uuid())),
                    "the victim's death must clear its override pairs (no stale admission on respawn)");
        } finally {
            context.syncRun(() -> {
                service.clearVictim(victim.uuid());
                attacker.remove();
                victim.remove();
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Shared helpers                                                     */
    /* ------------------------------------------------------------------ */

    /** The consumer's discovery path: the service off the {@code ServicesManager}, or null if unregistered. */
    private static @Nullable HitTimingOverrides resolve() {
        RegisteredServiceProvider<HitTimingOverrides> registration =
                Bukkit.getServicesManager().getRegistration(HitTimingOverrides.class);
        return registration == null ? null : registration.getProvider();
    }

    /**
     * Lands one melee hit and returns the victim's resulting hurt WINDOW, read in
     * the same tick — the pre-apply window write is visible the instant
     * {@code attack()} returns. {@code resetMax > 0} normalizes the victim's window
     * to a known baseline first (the plain-profile fallback prices off it); pass 0
     * to leave the field (ct8c writes an attack-speed-derived window regardless).
     */
    private static int windowAfterAttack(
            TestContext context, FakePlayer attacker, FakePlayer victim, int resetMax) throws Exception {
        return context.sync(() -> {
            if (resetMax > 0) {
                victim.player().setMaximumNoDamageTicks(resetMax);
            }
            victim.player().setNoDamageTicks(0);
            attacker.attack(victim.player());
            return victim.player().getMaximumNoDamageTicks();
        });
    }

    /** Toggles a feature through the management write-back seam and waits one tick for convergence. */
    private static void setFeature(
            MentalPluginV5 mental, TestContext context, Feature feature, boolean enabled) throws Exception {
        context.syncRun(() -> mental.management().setModuleEnabled(feature, enabled));
        context.awaitTicks(1);
    }
}
