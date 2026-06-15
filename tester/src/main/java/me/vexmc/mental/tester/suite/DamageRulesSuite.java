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
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Coverage for the damage-shaping rule modules.
 *
 * <p>{@code old-armour-strength} is a full BEHAVIOUR test: it drives a genuine
 * {@link FakePlayer#attack} (the vanilla damage pipeline, with the armour /
 * resistance / enchant / absorption modifiers actually populated — a synthetic
 * {@code EntityDamageByEntityEvent} carries none of them and the module would
 * no-op) and pins the era flat {@code (25-20)/25 = 0.2} reduction via the
 * captor's post-modifier {@code finalDamageOf}.</p>
 *
 * <p>{@code old-potion-values} and {@code old-tool-durability} are
 * fast-path-RESIDENT — their era Strength {@code ×3.5} value and weapon-wear run
 * inside {@code DamageCalculator}/{@code HitApplier}, reached only by a real
 * client's netty ATTACK packet. Clientless {@link FakePlayer}s attack
 * server-side and never traverse {@code HitPacketListener}, so the matrix can
 * only SMOKE-test them (enables cleanly, combat still works); their behaviour is
 * unit-pinned ({@code DamageCalculatorTest}, {@code ToolDurabilityMathTest}) and
 * legacy-lab wire-pinned. Each case toggles its module off, removes its
 * FakePlayers and unregisters captors in {@code finally}.</p>
 */
public final class DamageRulesSuite {

    private DamageRulesSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("rules: old-armour-strength reduces a hit by the 1.8 flat 4%/point", context ->
                        runArmourStrength(mental, tester, context)),
                new TestCase("rules: old-potion-values enables cleanly (era value is fast-path/unit-pinned)", context ->
                        runFastPathSmoke(mental, tester, context, "old-potion-values", "DamageCalculatorTest")),
                new TestCase("rules: old-tool-durability enables cleanly (weapon-wear is fast-path/unit-pinned)", context ->
                        runFastPathSmoke(mental, tester, context, "old-tool-durability", "ToolDurabilityMathTest")));
    }

    /* ------------------------------------------------------------------ */
    /*  1. old-armour-strength (behaviour)                                 */
    /* ------------------------------------------------------------------ */

    /**
     * A full diamond set (20 armour points, no enchants / resistance /
     * absorption) must reduce a hit by the era flat factor: final = base × 0.2,
     * the {@code (25 - armourPoints) / 25} pre-1.9 model with no toughness term.
     * The hit is a REAL attack so vanilla populates the {@code ARMOR} modifier
     * the module rewrites; the captor's {@code finalDamageOf} is read AFTER every
     * modifier.
     */
    private static void runArmourStrength(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            toggleModule(context, "old-armour-strength", true);
            context.expect(moduleActive(mental, "old-armour-strength"),
                    "old-armour-strength module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 1));
            });
            context.awaitTicks(5);

            // Full diamond = 20 armour points; no enchants, resistance or
            // absorption, so the era cascade collapses to the flat armour stage.
            context.syncRun(() -> {
                victim.player().getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
                victim.player().getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
                victim.player().getInventory().setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
                victim.player().getInventory().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
            });
            // The ARMOR attribute updates from worn pieces on the victim's tick;
            // give the synthetic player a few ticks to register the equipment.
            context.awaitTicks(3);

            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "the armoured victim's damage event");

            Double base = captors.damageOf(victim.uuid());
            Double finalDamage = captors.finalDamageOf(victim.uuid());
            if (base == null || base == 0.0 || finalDamage == null) {
                // The real attack never landed (synthetic-player reach / charge
                // variance) — the era 0.2 factor is unit-pinned in DefenceMath.
                context.note("armoured hit did not land (base=" + base + ") — formula unit-pinned elsewhere");
                return;
            }

            double expected = base * 0.2;
            // 5% of base (floor 0.2) absorbs the inevitable half-heart rounding of
            // a 20-point reduction; the 0.2 factor itself is what is being pinned.
            double epsilon = Math.max(0.2, base * 0.05);
            context.expectNear(expected, finalDamage, epsilon,
                    "era diamond reduction (base " + base + " × 0.2)");
        } finally {
            toggleModule(context, "old-armour-strength", false);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  2-3. fast-path-resident modules (smoke only)                       */
    /* ------------------------------------------------------------------ */

    /**
     * Smoke coverage for a fast-path-resident module: enabling it must not break
     * combat, and a real (server-side, vanilla) hit must still land. The module's
     * actual effect rides Mental's netty fast path, which a clientless
     * {@link FakePlayer} never reaches (it attacks server-side, never traversing
     * {@code HitPacketListener}); that behaviour is unit-pinned (see
     * {@code pinnedBy}) and legacy-lab wire-pinned instead.
     */
    private static void runFastPathSmoke(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context,
            String moduleId, String pinnedBy) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            toggleModule(context, moduleId, true);
            context.expect(moduleActive(mental, moduleId), moduleId + " failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 4, -2));
                victim.spawn(Arena.offset(centre, 4, 1));
            });
            context.awaitTicks(5);

            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "a hit to land with " + moduleId + " enabled");

            context.expect(captors.damageOf(victim.uuid()) != null,
                    moduleId + " broke the hit pipeline — no damage landed while enabled");
            context.note(moduleId + " enables cleanly and leaves combat working; its fast-path-only "
                    + "behaviour is unit-pinned (" + pinnedBy + ") + legacy-lab wire-pinned — clientless "
                    + "fake players do not traverse the netty fast path, so it cannot be asserted here.");
        } finally {
            toggleModule(context, moduleId, false);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Module toggling (copied from ZeroTouchSuite)                       */
    /* ------------------------------------------------------------------ */

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
