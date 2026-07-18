package me.vexmc.mental.tester.suite;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.CleavingHandle;
import me.vexmc.mental.platform.CleavingRegistrar;
import me.vexmc.mental.platform.Cooldowns;
import me.vexmc.mental.platform.MenuMaterials;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.cadence.Ct8cChargeLedger;
import me.vexmc.mental.v5.feature.damage.Ct8cIframesUnit;
import me.vexmc.mental.v5.feature.damage.DamageShaper;
import me.vexmc.mental.v5.feature.loadout.Ct8cReachTable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Live coverage for the Combat Test 8c rule features (spec §2, the CT8c import).
 * Each scenario enables exactly the CT8c {@link Feature}(s) it exercises through
 * the real management seam, drives a live server action, asserts the
 * decompile-cited era behaviour on this exact version, and toggles every feature
 * back off in {@code finally} — the enable/assert/teardown model. The CT8c
 * features all default OFF, so a passing ZeroTouchSuite plus these teardowns keep
 * the plugin transparent for whatever runs next.
 *
 * <p>The observability boundary is the same one the other rule suites hit: a
 * clientless {@link FakePlayer} attacks server-side (NMS {@code EntityHuman.attack}
 * / {@code Player.attack}), which fires {@code EntityDamageByEntityEvent} and the
 * whole Bukkit damage pipeline but never traverses Mental's netty fast path. So
 * event-resident CT8c units (damage table, i-frames, shields, charge gate) are
 * asserted LIVE here; the two fast-path-resident pieces (the reach GATE and the
 * {@code DamageShaper.compose} fast path) are pinned by their own unit tests and
 * not reachable by a fake attack — the reach ATTRIBUTE (the modern-lane lever) IS
 * observable and is asserted like {@link HitboxSuite}. Physics-dependent staging
 * (snowball flight) is a loud {@code note}/{@code skip}, never a fail; version-gated
 * levers (reach attribute 1.20.5+, Cleaving registry ~1.21.3+) branch on the same
 * capability probe the unit gates on, never a version literal.</p>
 */
public final class CombatTest8cSuite {

    private static final double DAMAGE_EPSILON = 1.0e-6;

    private CombatTest8cSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("ct8c: charged-attacks denies a sub-charge follow-up hit", context ->
                        runChargeGate(mental, tester, context)),
                new TestCase("ct8c: weapon damage table composes through the DamageShaper seam", context ->
                        runDamageTable(mental, tester, context)),
                new TestCase("ct8c: the i-frame window scales with the attacker's weapon speed", context ->
                        runIframeScaling(mental, tester, context)),
                new TestCase("ct8c: shields — 148 arc, the 5-damage cap passthrough, and axe disable", context ->
                        runShields(mental, tester, context)),
                new TestCase("ct8c: regen heals on the 40-tick cadence above the food gate", context ->
                        runRegen(mental, tester, context)),
                new TestCase("ct8c: snowball throw gate + the no-i-frame projectile knock", context ->
                        runProjectiles(mental, tester, context)),
                new TestCase("ct8c: per-weapon reach on the modern lane / degrades on the legacy lane", context ->
                        runReach(mental, tester, context)),
                new TestCase("ct8c: the Cleaving registry probe matches this version's expectation", context ->
                        runCleaving(mental, context)));
    }

    /* ------------------------------------------------------------------ */
    /*  1. charged-attacks — the sub-100% deny (miss-recovery lane note)    */
    /* ------------------------------------------------------------------ */

    /**
     * The CT8c charge gate (spec §2.1): a landed hit needs a full recharge, so a
     * second hit fired before the meter refills is DENIED. The discriminator is a
     * landed-hit COUNT — a private MONITOR/ignoreCancelled captor counts only hits
     * that survive to MONITOR, so a hit the charge gate cancels at LOW never
     * counts. Two rapid same-tick attacks (immunity reset between them so only the
     * charge gate can stop the second): with the feature OFF both land (count 2),
     * with it ON the second is denied (count 1). The 4-tick miss-recovery lane
     * needs an AIR SWING to arm it, which a clientless fake cannot emit (no swing
     * packet reaches the parse rim) — it is unit-pinned in {@code
     * Ct8cChargeLedgerTest} over the same production {@link Ct8cChargeLedger}, and
     * a fresh ledger is driven here to keep that lane visible in the live log.
     */
    private static void runChargeGate(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        AtomicInteger landed = new AtomicInteger();
        Listener counter = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onDamage(EntityDamageByEntityEvent event) {
                if (event.getEntity().getUniqueId().equals(victim.uuid())) {
                    landed.incrementAndGet();
                }
            }
        };

        try {
            context.syncRun(() -> Bukkit.getPluginManager().registerEvents(counter, tester));
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            // A fist's full delay is 16 ticks (scale reaches 1.0 at 8), so a
            // follow-up two ticks after the opener is well below full charge. The
            // two-tick gap (not a same-tick pair) avoids the legacy immunity
            // double-tick trap while staying inside the charge window.
            //
            // Control: with charged-attacks OFF the follow-up lands (count 1).
            setFeature(mental, context, Feature.CHARGED_ATTACKS, false);
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player()); // opener
            });
            context.awaitTicks(2);
            landed.set(0);
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player()); // follow-up
            });
            context.awaitTicks(3);
            context.expect(landed.get() == 1,
                    "with charged-attacks OFF the follow-up must land (saw " + landed.get()
                            + ") — the count discriminator is not wired");

            // Test: with charged-attacks ON the sub-charge follow-up is denied (count 0).
            setFeature(mental, context, Feature.CHARGED_ATTACKS, true);
            context.expect(mental.featureActive(Feature.CHARGED_ATTACKS),
                    "charged-attacks did not converge active");
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player()); // opener — fully charged ⇒ lands + resets the ledger
            });
            context.awaitTicks(2);
            landed.set(0);
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player()); // ~2 ticks later ⇒ scale < 1 ⇒ denied at LOW
            });
            context.awaitTicks(3);
            context.expect(landed.get() == 0,
                    "the sub-charge follow-up was not denied (landed " + landed.get()
                            + ") — the CT8c full-recharge gate regressed");

            // The miss-recovery lane over the production ledger (a fake emits no swing
            // packet to arm it live — the deny above is the live half).
            Ct8cChargeLedger ledger = new Ct8cChargeLedger();
            UUID id = UUID.randomUUID();
            double swordSpeed = 4.5; // full delay = attackDelayTicks(4.5)·2 = 14
            context.expect(ledger.onAttack(id, 100L, swordSpeed, true).allowed(),
                    "a never-attacked player's first hit must land (fully charged)");
            context.expect(!ledger.onAttack(id, 100L, swordSpeed, true).allowed(),
                    "the immediate same-tick follow-up must be denied");
            ledger.onAirSwing(id, 101L); // arms the recovery lane
            context.expect(!ledger.onAttack(id, 104L, swordSpeed, true).allowed(),
                    "3 ticks after the swing is still inside the 4-tick lane gate — denied");
            context.expect(ledger.onAttack(id, 106L, swordSpeed, true).allowed(),
                    "5 ticks after the swing opens the miss-recovery lane — allowed");
        } finally {
            setFeature(mental, context, Feature.CHARGED_ATTACKS, false);
            context.syncRun(() -> {
                HandlerList.unregisterAll(counter);
                attacker.remove();
                victim.remove();
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /*  2. ct8c-damage — the weapon table via staged weapons               */
    /* ------------------------------------------------------------------ */

    /**
     * The CT8c damage table (spec §2.2): {@code Ct8cDamageUnit} overwrites the
     * melee BASE with {@code composeCt8c(ct8cToolBase(weapon), …)} at LOWEST. For a
     * grounded (non-crit) attack by an unenchanted weapon on a dry, unarmoured,
     * non-blocking victim the composition collapses to {@code ct8cToolBase}, so the
     * BASE the MONITOR captor reads equals {@link DamageShaper#ct8cToolBase} — diamond
     * sword 6, diamond hoe 3, fist 2 (spec §2.2, one below vanilla). The table reads
     * the held MATERIAL, not the attribute, so no equipment-attribute tick is needed
     * (a settle tick is kept for safety). Diamond-tier weapons exist on the whole
     * range (the floor is 1.9.4) — netherite is 1.16+ and would NoSuchFieldError there.
     */
    private static void runDamageTable(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            setFeature(mental, context, Feature.CT8C_DAMAGE, true);
            context.expect(mental.featureActive(Feature.CT8C_DAMAGE), "ct8c-damage did not converge active");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            assertWeaponBase(context, captors, attacker, victim, new ItemStack(Material.DIAMOND_SWORD),
                    "diamond sword");
            assertWeaponBase(context, captors, attacker, victim, new ItemStack(Material.DIAMOND_HOE),
                    "diamond hoe");
            assertWeaponBase(context, captors, attacker, victim, new ItemStack(Material.AIR), "fist");
        } finally {
            setFeature(mental, context, Feature.CT8C_DAMAGE, false);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** Equips {@code weapon}, lands a grounded hit, and pins the composed BASE to {@link DamageShaper#ct8cToolBase}. */
    private static void assertWeaponBase(
            TestContext context, Captors captors, FakePlayer attacker, FakePlayer victim,
            ItemStack weapon, String label) throws Exception {
        double expected = context.sync(() -> {
            attacker.player().getInventory().setItemInMainHand(weapon);
            return DamageShaper.ct8cToolBase(weapon);
        });
        context.awaitTicks(2);
        captors.reset();
        context.syncRun(() -> {
            victim.player().setNoDamageTicks(0);
            attacker.attack(victim.player());
        });
        context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                "the " + label + " hit to land");
        Double base = captors.damageOf(victim.uuid());
        context.expect(base != null, label + " hit produced no damage event");
        context.expectNear(expected, base, DAMAGE_EPSILON,
                "CT8c " + label + " base damage (Ct8cTables via ct8cToolBase)");
    }

    /* ------------------------------------------------------------------ */
    /*  3. ct8c-iframes — the window scales with the attacker's weapon      */
    /* ------------------------------------------------------------------ */

    /**
     * The CT8c melee i-frame window (spec §2.4): {@code
     * setMaximumNoDamageTicks(min(attackerAttackDelay, 10))}, the delay read from
     * the CT8c weapon speed table (never a raw positive {@code setNoDamageTicks} —
     * the window field sidesteps the 1.16.5–1.20.6 total-invuln trap). A diamond
     * sword (attr 4.5 → delay 7) pins the window at 7; a diamond axe (attr 3.5 →
     * delay 10) at the 10-tick ceiling — distinct per weapon and both distinct from
     * the vanilla 20-tick default, so the scaling is unambiguous.
     */
    private static void runIframeScaling(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            setFeature(mental, context, Feature.CT8C_IFRAMES, true);
            context.expect(mental.featureActive(Feature.CT8C_IFRAMES), "ct8c-iframes did not converge active");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            int swordWindow = assertIframeWindow(context, captors, attacker, victim,
                    new ItemStack(Material.DIAMOND_SWORD), Ct8cIframesUnit.iframeTicks(4.5), "diamond sword");
            int axeWindow = assertIframeWindow(context, captors, attacker, victim,
                    new ItemStack(Material.DIAMOND_AXE), Ct8cIframesUnit.iframeTicks(3.5), "diamond axe");
            context.expect(swordWindow != axeWindow,
                    "the i-frame window must SCALE with weapon speed (sword " + swordWindow
                            + " == axe " + axeWindow + ")");
        } finally {
            setFeature(mental, context, Feature.CT8C_IFRAMES, false);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** Lands a hit with {@code weapon} and asserts the victim's max-no-damage window equals {@code expected}. */
    private static int assertIframeWindow(
            TestContext context, Captors captors, FakePlayer attacker, FakePlayer victim,
            ItemStack weapon, int expected, String label) throws Exception {
        context.syncRun(() -> attacker.player().getInventory().setItemInMainHand(weapon));
        context.awaitTicks(2);
        captors.reset();
        int window = context.sync(() -> {
            victim.player().setNoDamageTicks(0);
            attacker.attack(victim.player());
            return victim.player().getMaximumNoDamageTicks();
        });
        context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40, "the " + label + " hit to land");
        context.expect(captors.damageOf(victim.uuid()) != null, label + " hit did not land");
        context.expect(window == expected,
                "CT8c " + label + " i-frame window " + window + " != expected " + expected);
        return window;
    }

    /* ------------------------------------------------------------------ */
    /*  4. ct8c-shields — arc, 5-cap passthrough, axe disable               */
    /* ------------------------------------------------------------------ */

    /**
     * The CT8c shield (spec §2.6), driven live over the crouch-to-shield posture a
     * clientless fake CAN stage (offhand shield + sneaking + grounded — no
     * right-click, no use-duration gate). {@code ct8c-damage} is enabled alongside
     * so the incoming BASE is the deterministic table value (diamond sword 6,
     * diamond axe 7): head-on the 5-cap absorbs 5 and 1 passes through; facing
     * away (out of the 148 arc) the block is withdrawn and the full hit lands; an
     * axe hit disables the shield (~32t via the Cooldowns API). The arc/cap/disable
     * NUMBERS are kernel-pinned ({@code Ct8cShieldMath}) + unit-pinned ({@code
     * Ct8cShieldBlockTest}); this asserts the UNIT drives them over a real event.
     * The block posture reads {@code isOnGround}, which a clientless fake reads as
     * false on the 1.9/1.10 NMS (project memory), so the case skips below 1.11.
     */
    private static void runShields(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        if (!mental.environment().isAtLeast(1, 11, 0)) {
            context.note("skipped: a clientless fake reads isOnGround()=false on the 1.9/1.10 NMS, so the "
                    + "CT8c crouch-to-shield posture (onGround-gated) cannot be staged on "
                    + mental.environment().describe() + "; the arc/cap/disable numbers are unit-pinned "
                    + "in Ct8cShieldBlockTest");
            return;
        }
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            setFeature(mental, context, Feature.CT8C_DAMAGE, true);   // deterministic incoming base (6/7)
            setFeature(mental, context, Feature.CT8C_SHIELDS, true);
            context.expect(mental.featureActive(Feature.CT8C_SHIELDS), "ct8c-shields did not converge active");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            // Stage the crouch-to-shield posture: offhand shield + sneaking; the
            // victim is grounded on the arena floor after the settle above.
            context.syncRun(() -> {
                victim.player().getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
                victim.player().setSneaking(true);
                attacker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            });
            context.awaitTicks(2);

            double swordBase = context.sync(() ->
                    DamageShaper.ct8cToolBase(new ItemStack(Material.DIAMOND_SWORD)));

            // Head-on (facing the attacker at −z ⇒ yaw 180): inside the 148 arc ⇒
            // the 5-cap absorbs 5, the excess passes through.
            faceYaw(context, victim, 180.0f);
            Double headOnFinal = strikeAndReadFinal(context, captors, attacker, victim);
            context.expect(headOnFinal != null, "the head-on shield hit produced no damage event");
            context.expectNear(swordBase - 5.0, headOnFinal, 1.0e-3,
                    "a head-on crouch-block must cap 5 and pass " + (swordBase - 5.0) + " through");

            // Facing away (yaw 0 ⇒ the +z view, the attacker behind the gaze):
            // outside the 148 arc ⇒ the block is withdrawn, the full hit lands.
            faceYaw(context, victim, 0.0f);
            Double behindFinal = strikeAndReadFinal(context, captors, attacker, victim);
            context.expect(behindFinal != null, "the out-of-arc shield hit produced no damage event");
            context.expectNear(swordBase, behindFinal, 1.0e-3,
                    "an out-of-arc hit is not blocked — the full " + swordBase + " must land");

            // An axe hit always disables the shield (~32t + 10/Cleaving; 0 here).
            if (Cooldowns.itemCooldownSupported()) {
                context.syncRun(() ->
                        attacker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_AXE)));
                context.awaitTicks(2);
                faceYaw(context, victim, 180.0f);
                context.syncRun(() -> {
                    victim.player().setNoDamageTicks(0);
                    attacker.attack(victim.player());
                });
                context.awaitUntil(() -> victim.player().getCooldown(Material.SHIELD) > 0, 10,
                        "the axe hit to disable the shield");
                int cooldown = context.sync(() -> victim.player().getCooldown(Material.SHIELD));
                context.expect(cooldown >= 28,
                        "an axe hit must disable the shield ~32t (Ct8cShieldMath.axeDisableTicks(0)) — got "
                                + cooldown);
            } else {
                context.note("no item-cooldown API on this version (pre-1.11) — the axe shield-disable is a "
                        + "documented no-op here; ticks are unit-pinned in Ct8cShieldBlockTest");
            }
        } finally {
            setFeature(mental, context, Feature.CT8C_SHIELDS, false);
            setFeature(mental, context, Feature.CT8C_DAMAGE, false);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** Teleports the victim in place with a new yaw and lets the ground re-settle (the block posture reads onGround). */
    private static void faceYaw(TestContext context, FakePlayer victim, float yaw) throws Exception {
        context.syncRun(() -> {
            Location facing = victim.player().getLocation();
            facing.setYaw(yaw);
            facing.setPitch(0.0f);
            victim.player().teleport(facing);
            victim.player().setSneaking(true);
        });
        context.awaitTicks(3);
    }

    /** Lands one hit and returns the victim's final damage (after every modifier, incl. the shield cap). */
    private static Double strikeAndReadFinal(
            TestContext context, Captors captors, FakePlayer attacker, FakePlayer victim) throws Exception {
        captors.reset();
        context.syncRun(() -> {
            victim.player().setNoDamageTicks(0);
            attacker.attack(victim.player());
        });
        context.awaitUntil(() -> captors.finalDamageOf(victim.uuid()) != null, 40, "the shield hit to land");
        return captors.finalDamageOf(victim.uuid());
    }

    /* ------------------------------------------------------------------ */
    /*  5. ct8c-regen — the 40-tick cadence above the food gate            */
    /* ------------------------------------------------------------------ */

    /**
     * The CT8c regen (spec §2.7): +1 HP every 40 ticks while {@code foodLevel > 6}
     * and hurt — the 1.8 cadence, NOT the modern saturation fast-heal. The feature
     * is enabled BEFORE the victim spawns so the per-player region task starts on
     * its join. Food is set to 10 (above the CT8c gate of 6, below the vanilla
     * natural-regen threshold of 18) with zero saturation, so any heal is
     * unambiguously CT8c's — vanilla does not regen at food 10. The exact cadence /
     * drain / gate are unit-pinned in {@code Ct8cRegenTest}; this asserts the live
     * heal lands within a 40-tick window.
     */
    private static void runRegen(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        // Ct8cRegenUnit's per-tick heal reads the naturalRegeneration gamerule
        // through the platform NaturalRegen seam: the typed org.bukkit.GameRule API
        // on 1.13+, the deprecated String overload below (the typed constant is
        // never linked pre-1.13). The seam closed the every-tick NoClassDefFoundError
        // that previously made the legacy heal un-observable, so the live cadence is
        // now exercised on every version — including 1.9.4, where zero GameRule
        // linkage errors is the fix's acceptance bar.
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            setFeature(mental, context, Feature.CT8C_REGEN, true); // BEFORE spawn ⇒ the join starts the task
            context.expect(mental.featureActive(Feature.CT8C_REGEN), "ct8c-regen did not converge active");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                victim.spawn(Arena.offset(centre, 0, 0));
            });
            context.awaitTicks(5);

            double start = context.sync(() -> {
                Player player = victim.player();
                player.setFoodLevel(10);   // > 6 (CT8c gate) and < 18 (vanilla gate) ⇒ CT8c-only
                player.setSaturation(0.0f); // no vanilla fast-heal path
                @SuppressWarnings("deprecation")
                double max = player.getMaxHealth();
                double wounded = Math.max(1.0, max - 4.0);
                player.setHealth(wounded);
                return wounded;
            });

            // One 40-tick regen boundary sits inside this window; wait past it.
            context.awaitTicks(55);
            double healed = context.sync(() -> victim.player().getHealth());
            context.expect(healed > start + 0.5,
                    "CT8c regen did not heal above the food gate (health " + start + " -> " + healed
                            + ") — the 40-tick +1HP cadence regressed (vanilla does not regen at food 10)");
        } finally {
            setFeature(mental, context, Feature.CT8C_REGEN, false);
            context.syncRun(victim::remove);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  6. ct8c-projectiles — the throw gate + no-i-frame double knock      */
    /* ------------------------------------------------------------------ */

    /**
     * The CT8c projectile policy (spec §2.10/§2.4). The deterministic anchor is the
     * 4-tick THROW GATE: launching a snowball stamps the shooter's item cooldown
     * (via the Cooldowns API). The physics half — a point-blank snowball's 0-damage
     * knock, and a second snowball landing a knock with no projectile i-frames
     * (ct8c-iframes routes projectiles to a 0-tick window) — is best-effort:
     * clientless fakes and snowball flight do not always connect, so a miss is a
     * loud {@code note}, never a fail (the knock value is kernel-pinned in
     * ProjectileSuite, the 0-tick projectile routing in the i-frames unit).
     */
    private static void runProjectiles(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer shooter = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            setFeature(mental, context, Feature.CT8C_PROJECTILES, true);
            setFeature(mental, context, Feature.CT8C_IFRAMES, true);
            context.expect(mental.featureActive(Feature.CT8C_PROJECTILES),
                    "ct8c-projectiles did not converge active");

            Location victimSpot = context.sync(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                Location spot = Arena.offset(centre, 3, 0);
                shooter.spawn(Arena.offset(centre, 3, -3));
                victim.spawn(spot);
                return spot;
            });
            context.awaitTicks(5);

            // The throw gate (deterministic): launching a snowball stamps the shooter's cooldown.
            if (Cooldowns.itemCooldownSupported()) {
                // The cooldown item resolves through the platform inverse material seam — a raw
                // Material.SNOWBALL getstatic is a NoSuchFieldError on pre-flattening servers
                // (SNOW_BALL there), and 1.12.2 is the one lane that carries both the item-cooldown
                // API and pre-flattening names. This is the exact seam production's throw gate uses.
                Material snowballItem = MenuMaterials.of("SNOWBALL");
                context.syncRun(() -> {
                    shooter.player().setCooldown(snowballItem, 0);
                    shooter.player().launchProjectile(Snowball.class, new Vector(0, 0.1, 0.5));
                });
                context.awaitUntil(() -> shooter.player().getCooldown(snowballItem) > 0, 10,
                        "the CT8c snowball throw gate to stamp the shooter cooldown");
                int gate = context.sync(() -> shooter.player().getCooldown(snowballItem));
                context.expect(gate > 0,
                        "launching a snowball must stamp the 4-tick CT8c throw gate — got " + gate);
            } else {
                context.note("no item-cooldown API on this version (pre-1.11) — the snowball throw gate is a "
                        + "documented no-op here (Ct8cProjectilePolicy.THROW_GATE_TICKS unit-pinned)");
            }

            // The no-i-frame knock (physics, best-effort): a point-blank snowball
            // knocks, and a second knocks again with no projectile immunity between.
            captors.reset();
            boolean firstKnock = pointBlankSnowball(context, captors, shooter, victim, victimSpot);
            if (!firstKnock) {
                context.note("the point-blank snowball never reached the clientless victim on this NMS — the "
                        + "0.4 knock is kernel-pinned (ProjectileSuite) and the 0-tick projectile i-frame "
                        + "routing is unit-pinned (Ct8cIframesUnit)");
                return;
            }
            context.syncRun(() -> victim.player().setNoDamageTicks(0));
            captors.reset();
            boolean secondKnock = pointBlankSnowball(context, captors, shooter, victim, victimSpot);
            if (secondKnock) {
                context.expect(captors.knockbackAppliesTo(victim.uuid()) >= 1
                                || captors.velocityOf(victim.uuid()) != null,
                        "the second projectile hit produced no knock — projectile i-frames were not zeroed");
            } else {
                context.note("only the first snowball connected (flight physics) — the no-i-frame double hit "
                        + "could not be staged; projectile-0 i-frame routing is unit-pinned (Ct8cIframesUnit)");
            }
        } finally {
            setFeature(mental, context, Feature.CT8C_PROJECTILES, false);
            setFeature(mental, context, Feature.CT8C_IFRAMES, false);
            context.syncRun(() -> {
                shooter.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** Fires one point-blank snowball across the victim's hitbox; returns whether a knock was observed. */
    private static boolean pointBlankSnowball(
            TestContext context, Captors captors, FakePlayer shooter, FakePlayer victim, Location victimSpot)
            throws Exception {
        context.syncRun(() -> {
            victim.player().setNoDamageTicks(0);
            Location launch = victimSpot.clone().add(0, 1.0, -0.8);
            Snowball snowball = victimSpot.getWorld().spawn(launch, Snowball.class);
            snowball.setShooter(shooter.player());
            snowball.setVelocity(new Vector(0, 0.0, 0.5));
        });
        for (int attempt = 0; attempt < 8 && captors.velocityOf(victim.uuid()) == null; attempt++) {
            context.awaitTicks(3);
        }
        return captors.velocityOf(victim.uuid()) != null;
    }

    /* ------------------------------------------------------------------ */
    /*  7. ct8c-reach — the modern attribute lane / the legacy degrade      */
    /* ------------------------------------------------------------------ */

    /**
     * The CT8c reach lever (spec §2.2/§4). On the modern lane (1.20.5+, the {@code
     * ENTITY_INTERACTION_RANGE} attribute exists) enabling {@code ct8c-reach} pins
     * the held weapon's era reach into the attribute base — a diamond hoe reads 3.5
     * (distinct from the vanilla 3.0 default, so the lever demonstrably applied).
     * Below the floor the attribute is absent: the feature enables cleanly as a
     * documented no-op (the 3.5 weapons are impossible there; the ≤3.0 gate is the
     * netty reach validation, fast-path-only), asserted like {@link HitboxSuite}.
     */
    private static void runReach(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Attribute reach = Attributes.entityInteractionRange();
        FakePlayer subject = new FakePlayer(tester, mental.scheduling());

        try {
            if (reach == null) {
                // Legacy lane: the interaction-range attribute is absent ⇒ the reach
                // lever is a no-op. The feature must still enable cleanly (the netty
                // ≤3.0 gate is the sub-floor enforcement) and disable to zero-touch.
                setFeature(mental, context, Feature.CT8C_REACH, true);
                context.expect(mental.featureActive(Feature.CT8C_REACH),
                        "ct8c-reach did not converge active on the legacy lane (it is a Bukkit no-op there)");
                context.note("entity_interaction_range absent on " + mental.environment().describe()
                        + " — ct8c-reach degrades to the netty ≤3.0 gate (3.5 weapons impossible below 1.20.5); "
                        + "the reach table is unit-pinned (Ct8cReachTableTest)");
                return;
            }

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                subject.spawn(Arena.offset(centre, 0, 0));
                subject.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_HOE));
            });
            context.awaitTicks(5);

            // Enable AFTER the hoe is held: the enable pass reconciles online players'
            // held weapons (the HitboxUnit template), writing the reach base.
            setFeature(mental, context, Feature.CT8C_REACH, true);
            context.expect(mental.featureActive(Feature.CT8C_REACH), "ct8c-reach did not converge active");
            context.awaitTicks(3);

            double expected = Ct8cReachTable.reachFor("DIAMOND_HOE"); // 3.5, above the vanilla 3.0 default
            Double value = context.sync(() -> {
                AttributeInstance instance = subject.player().getAttribute(reach);
                return instance != null ? instance.getBaseValue() : null;
            });
            if (value == null) {
                context.note("entity_interaction_range not present on this player — cannot read the applied "
                        + "reach (the table is unit-pinned in Ct8cReachTableTest)");
                return;
            }
            context.expectNear(expected, value, 0.01,
                    "ct8c-reach must pin the hoe's 3.5 era reach into the interaction-range attribute base");
        } finally {
            setFeature(mental, context, Feature.CT8C_REACH, false);
            context.syncRun(subject::remove);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  8. cleaving — the registry probe vs the version expectation         */
    /* ------------------------------------------------------------------ */

    /**
     * The CT8c Cleaving enchant (spec §2.9/§5). Enabling the feature runs the
     * platform {@link CleavingRegistrar} once. Below its ~1.21.3 registry floor the
     * handle is empty and both consumers fold Cleaving as level 0 — the documented
     * gap; asserted here as an empty handle. At/above the floor the guarded
     * best-effort injection is UNVALIDATED against a cached live jar, so the honest
     * contract is: WHERE a handle is installed, {@link CleavingHandle#levelOf} reads
     * an enchanted axe (and 0 off a plain one); where the modern shape did not
     * resolve, a loud {@code note} (spec §5). The feature enables cleanly either way.
     */
    private static void runCleaving(MentalPluginV5 mental, TestContext context) throws Exception {
        try {
            setFeature(mental, context, Feature.CLEAVING, true);
            context.expect(mental.featureActive(Feature.CLEAVING), "cleaving did not converge active");

            Optional<CleavingHandle> handle = CleavingRegistrar.handle();
            boolean atFloor = mental.environment().isAtLeast(1, 21, 3);
            context.note("cleaving registrar state: " + CleavingRegistrar.describe()
                    + " (atFloor=" + atFloor + ") on " + mental.environment().describe());

            if (!atFloor) {
                context.expect(handle.isEmpty(),
                        "Cleaving must degrade to no handle below its ~1.21.3 registry floor (spec §5 gap) — got "
                                + CleavingRegistrar.describe());
                return;
            }

            if (handle.isEmpty()) {
                context.note("the guarded best-effort Cleaving injection did not land on this modern shape ("
                        + CleavingRegistrar.describe() + ") — a documented, unvalidated-write gap (spec §5)");
                return;
            }

            // Installed: the handle must read a Cleaving-enchanted axe and 0 off a plain one.
            CleavingHandle live = handle.get();
            context.expect(live.levelOf(new ItemStack(Material.DIAMOND_AXE)) == 0,
                    "a plain axe must read Cleaving level 0");
            Enchantment cleaving = resolveCleaving();
            if (cleaving == null) {
                context.note("the mental:cleaving Bukkit enchant did not resolve for staging — the handle is "
                        + "installed (" + CleavingRegistrar.describe() + ") but the level read cannot be staged here");
                return;
            }
            int level = context.sync(() -> {
                ItemStack axe = new ItemStack(Material.DIAMOND_AXE);
                axe.addUnsafeEnchantment(cleaving, 3);
                return live.levelOf(axe);
            });
            context.expect(level == 3,
                    "CleavingHandle.levelOf must read the enchanted axe's Cleaving level (expected 3, got "
                            + level + ")");
        } finally {
            setFeature(mental, context, Feature.CLEAVING, false);
        }
    }

    /** The injected {@code mental:cleaving} Bukkit enchant, or {@code null} if it does not resolve here. */
    @SuppressWarnings("deprecation") // getByKey + the (namespace,key) ctor are the cross-version key accessors
    private static Enchantment resolveCleaving() {
        try {
            return Enchantment.getByKey(new NamespacedKey("mental", "cleaving"));
        } catch (RuntimeException | LinkageError absent) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Shared helpers                                                     */
    /* ------------------------------------------------------------------ */

    /** Toggles a feature through the management write-back seam and waits one tick for convergence. */
    private static void setFeature(
            MentalPluginV5 mental, TestContext context, Feature feature, boolean enabled) throws Exception {
        context.syncRun(() -> mental.management().setModuleEnabled(feature, enabled));
        context.awaitTicks(1);
    }
}
