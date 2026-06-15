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
 *   <li><b>attack-cooldown</b> — the client-side {@code attack_speed} attribute
 *       spoof (value 1024.0, packet-only) only changes the charge-bar overlay
 *       the CLIENT draws for its own entity. A FakePlayer has no client, so the
 *       spoof is unobservable here. Behaviour is unit-pinned in
 *       {@code CooldownSpoofTest} and wire-pinned by the legacy-lab harness.</li>
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

    private static void runAttackCooldown(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            toggleModule(context, "attack-cooldown", true);
            context.expect(moduleActive(mental, "attack-cooldown"),
                    "attack-cooldown module failed to enable");
            // The attack_speed spoof is a client-only overlay change; a
            // clientless fake player cannot observe it (CooldownSpoofTest +
            // legacy-lab pin the real behaviour). We only confirm a hit lands.
            context.note("attack-cooldown spoof is client-only — unobservable clientless; "
                    + "behaviour unit-pinned in CooldownSpoofTest + legacy-lab");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "a melee hit to land with attack-cooldown enabled");

            context.expect(captors.damageOf(victim.uuid()) != null,
                    "a normal hit must still land with attack-cooldown enabled");
        } finally {
            toggleModule(context, "attack-cooldown", false);
            context.expect(!moduleActive(mental, "attack-cooldown"),
                    "attack-cooldown module failed to disable");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
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
        context.syncRun(() -> Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(), "mental module " + id + " " + (enabled ? "on" : "off")));
        context.awaitTicks(1);
    }

    private static boolean moduleActive(MentalPlugin mental, String id) {
        return mental.modules().byId(id).map(module -> module.active()).orElse(false);
    }
}
