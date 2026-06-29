package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

/**
 * Integration <em>smoke</em> coverage for the four cosmetic / edge-case combat
 * modules. Their precise behaviour is unobservable to a clientless FakePlayer
 * or is probabilistic, so it is pinned elsewhere — this suite only asserts the
 * zero-touch + boot-safety contract: each module enables cleanly through the
 * real command path, registers without throwing, does not break a normal hit,
 * and disables cleanly again.
 *
 * <p>Why each is smoke-only, and where the real pin lives:</p>
 * <ul>
 *   <li><b>attack-cooldown</b> — has a SERVER half that IS observable: it raises
 *       the player's {@code attack_speed} base to full charge so vanilla
 *       {@code Player#attack} stops scaling spam-clicked hits (the bug a
 *       client-only spoof left on mob / fast-path-off hits). This suite asserts
 *       the base is raised on enable and restored on disable, plus a best-effort
 *       full-damage spam hit. The client charge-overlay half is unit-pinned in
 *       {@code CooldownSpoofTest}; the damage math in {@code CooldownDamageScalingTest}.</li>
 *   <li><b>disable-attack-sounds</b> — cancels the attack sound packet on send;
 *       a clientless player receives nothing, so the cancel is unobservable
 *       here. Unit-pinned in {@code AttackSoundsTest}.</li>
 *   <li><b>old-armour-durability</b> — restores the 1.8 Unbreaking-on-armour
 *       model ({@code damageChance = 60 + 40/(level+1)}); the wear/skip decision
 *       is a per-hit random roll, so the exact magnitude is not deterministically
 *       observable in one staged hit. Unit-pinned in
 *       {@code ArmourDurabilityMathTest}.</li>
 *   <li><b>old-critical-hits</b> — the fast-path-off crit override (×1.5 on the
 *       1.8 precondition set) fires only when the netty fast path is inactive
 *       (mob melee / fast-path-off), an edge a staged player-vs-player hit does
 *       not exercise. Unit-pinned in {@code DamageCalculatorTest} (era-crit
 *       cases).</li>
 * </ul>
 *
 * <p>The matrix therefore smoke-tests integration safety only — that enabling
 * any of these on a live server is harmless and does not interfere with a
 * normal melee hit landing.</p>
 */
public final class CosmeticSmokeSuite {

    private CosmeticSmokeSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("smoke: attack-cooldown enables and a hit still lands", context ->
                        runAttackCooldown(mental, tester, context)),
                new TestCase("smoke: disable-attack-sounds enables and a hit still lands", context ->
                        runDisableAttackSounds(mental, tester, context)),
                new TestCase("smoke: old-armour-durability enables and a hit on armour still lands", context ->
                        runArmourDurability(mental, tester, context)),
                new TestCase("smoke: old-critical-hits enables cleanly", context ->
                        runOldCriticalHits(mental, tester, context)));
    }

    /**
     * The full-charge value the module writes to the SERVER attack_speed base
     * (mirrors {@code CooldownSpoof.FULL_CHARGE_ATTACK_SPEED}; that constant lives
     * in a PacketEvents-importing class the clientless tester must not load, so it
     * is duplicated here and guarded against drift in {@code CooldownDamageScalingTest}).
     */
    private static final double FULL_CHARGE_BASE = 1024.0;

    /**
     * Behaviour test for the SERVER half of attack-cooldown: enabling the module
     * raises the attacker's {@code attack_speed} base to full charge (so vanilla
     * {@code Player#attack} stops scaling spam-clicked hits — the iron-golem bug),
     * and disabling restores it (zero-touch). A clientless {@link FakePlayer}
     * attacks server-side through the real {@code Player#attack}, which is exactly
     * the vanilla path the fix targets, so the full-damage spam hit is observable
     * here (best-effort, since hit-landing varies for synthetic players).
     */
    private static void runAttackCooldown(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());
        Attribute attackSpeed = Attributes.attackSpeed();

        try {
            // Spawn BEFORE enabling so the module's apply-to-online-players reaches
            // the fake players (a clientless spawn may not fire PlayerJoinEvent).
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
                attacker.player().getInventory().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
            });
            context.awaitTicks(5);

            toggleModule(context, "attack-cooldown", true);
            context.expect(moduleActive(mental, "attack-cooldown"),
                    "attack-cooldown module failed to enable");

            if (attackSpeed == null) {
                context.note("attack_speed attribute absent on this version — server-base half N/A");
            } else {
                // The fix: the SERVER attack_speed base must be raised to full
                // charge. The apply runs on the player's region thread (deferred a
                // tick on Paper), so give it a few ticks then read on the main thread.
                context.awaitTicks(5);
                double enabledBase = context.sync(() -> baseValue(attacker, attackSpeed));
                context.expect(enabledBase >= FULL_CHARGE_BASE,
                        "server attack_speed base must be raised to full charge on enable (was "
                                + enabledBase + ") — a client-only spoof leaves vanilla Player#attack "
                                + "scaling spam-clicked hits toward 20%");

                // Behaviour: a spam-clicked hit (its attack-strength ticker reset by
                // the prior swing) must deal full weapon damage, not the 1.9 ramp.
                double weaponDamage = context.sync(() -> attackDamageValue(attacker));
                captors.reset();
                context.syncRun(() -> {
                    victim.player().setNoDamageTicks(0);
                    attacker.attack(victim.player());        // charge the strike
                    victim.player().setNoDamageTicks(0);
                    attacker.attack(victim.player());        // spam: ticker just reset (last-write-wins)
                });
                context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                        "a spam-clicked hit to land with attack-cooldown enabled");
                Double spamDamage = captors.damageOf(victim.uuid());
                if (spamDamage == null || weaponDamage <= 0.0) {
                    context.note("spam hit did not land / weapon damage unreadable (synthetic-player "
                            + "variance) — the full-charge math is unit-pinned in CooldownDamageScalingTest");
                } else {
                    // Full charge: damage == the weapon's attack-damage; the 1.9 ramp
                    // would have scaled this spam hit toward 0.2x (the 11/12-hits bug).
                    context.expectNear(weaponDamage, spamDamage, Math.max(0.75, weaponDamage * 0.1),
                            "spam-clicked hit must deal full weapon damage with no cooldown ramp");
                }
            }
        } finally {
            toggleModule(context, "attack-cooldown", false);
            context.expect(!moduleActive(mental, "attack-cooldown"),
                    "attack-cooldown module failed to disable");
            if (attackSpeed != null) {
                // Zero-touch: disable restores the captured (sanitized) original base
                // — vanilla 4.0 here — on the disabling thread.
                context.awaitTicks(3);
                double restoredBase = context.sync(() -> baseValue(attacker, attackSpeed));
                context.expect(restoredBase < FULL_CHARGE_BASE,
                        "server attack_speed base must be restored on disable (zero-touch); was "
                                + restoredBase);
            }
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** The player's {@code attack_speed} base, or {@code -1} when the attribute is absent. */
    private static double baseValue(FakePlayer player, Attribute attribute) {
        AttributeInstance instance = player.player().getAttribute(attribute);
        return instance == null ? -1.0 : instance.getBaseValue();
    }

    /** The player's resolved {@code attack_damage} (with the held weapon), or {@code -1} when absent. */
    private static double attackDamageValue(FakePlayer player) {
        Attribute attackDamage = Attributes.attackDamage();
        if (attackDamage == null) {
            return -1.0;
        }
        AttributeInstance instance = player.player().getAttribute(attackDamage);
        return instance == null ? -1.0 : instance.getValue();
    }

    private static void runDisableAttackSounds(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            toggleModule(context, "disable-attack-sounds", true);
            context.expect(moduleActive(mental, "disable-attack-sounds"),
                    "disable-attack-sounds module failed to enable");
            // The sound-packet cancel is invisible without a client to play the
            // sound (AttackSoundsTest pins it). We only confirm a hit lands.
            context.note("attack-sound cancel is client-only — unobservable clientless; "
                    + "behaviour unit-pinned in AttackSoundsTest");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 3, -2));
                victim.spawn(Arena.offset(centre, 3, 2));
            });
            context.awaitTicks(5);

            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "a melee hit to land with disable-attack-sounds enabled");

            context.expect(captors.damageOf(victim.uuid()) != null,
                    "a normal hit must still land with disable-attack-sounds enabled");
        } finally {
            toggleModule(context, "disable-attack-sounds", false);
            context.expect(!moduleActive(mental, "disable-attack-sounds"),
                    "disable-attack-sounds module failed to disable");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    private static void runArmourDurability(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            toggleModule(context, "old-armour-durability", true);
            context.expect(moduleActive(mental, "old-armour-durability"),
                    "old-armour-durability module failed to enable");
            // The 1.8 Unbreaking-on-armour wear/skip is a per-hit random roll;
            // its magnitude is unit-pinned in ArmourDurabilityMathTest. Here we
            // only confirm the module does not break a hit that lands on armour.
            context.note("Unbreaking-on-armour wear is probabilistic — magnitude "
                    + "unit-pinned in ArmourDurabilityMathTest");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 6, -2));
                victim.spawn(Arena.offset(centre, 6, 2));
                // Iron armour material names are stable across the whole
                // supported range; equip every slot so the durability hook has
                // worn pieces to roll against.
                PlayerInventory inventory = victim.player().getInventory();
                inventory.setHelmet(new ItemStack(Material.IRON_HELMET));
                inventory.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                inventory.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                inventory.setBoots(new ItemStack(Material.IRON_BOOTS));
            });
            context.awaitTicks(5);

            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "a melee hit to land on an armoured victim with old-armour-durability enabled");

            context.expect(captors.damageOf(victim.uuid()) != null,
                    "a hit on armour must still land with old-armour-durability enabled");
        } finally {
            toggleModule(context, "old-armour-durability", false);
            context.expect(!moduleActive(mental, "old-armour-durability"),
                    "old-armour-durability module failed to disable");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    private static void runOldCriticalHits(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            toggleModule(context, "old-critical-hits", true);
            context.expect(moduleActive(mental, "old-critical-hits"),
                    "old-critical-hits module failed to enable");
            // The fast-path-off ×1.5 crit override fires only when the netty
            // fast path is inactive (mob melee / fast-path-off); a staged
            // player-vs-player hit does not exercise it. Pinned in
            // DamageCalculatorTest (era-crit cases). We only confirm the module
            // enables cleanly and a normal hit still lands.
            context.note("fast-path-off crit override is an edge path — unit-pinned in "
                    + "DamageCalculatorTest (era-crit cases)");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, -6, -2));
                victim.spawn(Arena.offset(centre, -6, 2));
            });
            context.awaitTicks(5);

            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "a melee hit to land with old-critical-hits enabled");

            context.expect(captors.damageOf(victim.uuid()) != null,
                    "a normal hit must still land with old-critical-hits enabled");
        } finally {
            toggleModule(context, "old-critical-hits", false);
            context.expect(!moduleActive(mental, "old-critical-hits"),
                    "old-critical-hits module failed to disable");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** Toggles through the real console command path and waits for convergence. */
    private static void toggleModule(TestContext context, String id, boolean enabled) throws Exception {
        context.syncRun(() -> ((MentalPlugin) Bukkit.getPluginManager().getPlugin("Mental"))
                .management().setModuleEnabled(id, enabled));
        context.awaitTicks(1);
    }

    private static boolean moduleActive(MentalPlugin mental, String id) {
        return mental.modules().byId(id).map(module -> module.active()).orElse(false);
    }
}
