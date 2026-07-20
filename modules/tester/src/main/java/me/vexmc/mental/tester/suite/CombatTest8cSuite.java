package me.vexmc.mental.tester.suite;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.vexmc.mental.kernel.math.DamageTables;
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
import me.vexmc.mental.v5.feature.cadence.Ct8cChargeView;
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
                new TestCase("ct8c: the canonical non-sprint jump crit lands the flat 1.5", context ->
                        runCritComposition(mental, tester, context)),
                new TestCase("ct8c: weapon damage table composes through the DamageShaper seam", context ->
                        runDamageTable(mental, tester, context)),
                new TestCase("ct8c: the i-frame window scales with the attacker's weapon speed", context ->
                        runIframeScaling(mental, tester, context)),
                new TestCase("ct8c: the shrunken i-frame window restores under continuous environmental damage", context ->
                        runIframeEnvironmentalCadence(mental, tester, context)),
                new TestCase("ct8c: the whole i-frame window is difference-damage (a mid-window re-hit deals nothing)", context ->
                        runIframeFullWindowDiffDamage(mental, tester, context)),
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
            //
            // The gap between the opener (which resets the charge ledger) and the follow-up is pinned
            // to EXACTLY 2 server ticks by scheduling the follow-up from inside the opener's tick via
            // runOnLater — NOT the wall-clock-drifting awaitTicks(2)+syncRun pair the case used before.
            // The ledger measures charge in game ticks the TickClock reads at each hit; awaitTicks starts
            // counting only once the tester thread re-reads the tick AFTER the opener's syncRun returns,
            // and each syncRun then waits for a free main-tick — so under 15-JVM matrix load the opener→
            // follow-up gap drifted well past the fist's 8-tick full-charge point, the follow-up genuinely
            // recharged, and the deny correctly did NOT fire (landed 1 — a suite anchoring bug, not a
            // product bug; matrix-gate concurrency-flake doctrine). runOnLater makes the gap load-invariant:
            // a 2-tick follow-up is unambiguously sub-charge (scale 0.25), and the published charge view
            // proves it was sub-charge before we assert the deny, so the case can never pass vacuously on a
            // hit that had actually recharged.
            setFeature(mental, context, Feature.CHARGED_ATTACKS, true);
            context.expect(mental.featureActive(Feature.CHARGED_ATTACKS),
                    "charged-attacks did not converge active");
            AtomicBoolean followUpFired = new AtomicBoolean();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player()); // opener — fully charged ⇒ lands + resets the ledger THIS tick
                mental.scheduling().runOnLater(attacker.player(), 2L, () -> {
                    landed.set(0);
                    victim.player().setNoDamageTicks(0);
                    attacker.attack(victim.player()); // exactly 2 ticks later ⇒ scale 0.25 ⇒ denied at LOW
                    followUpFired.set(true);
                }, () -> followUpFired.set(true)); // retired ⇒ no follow-up published; the scale guard below fails loud, not vacuous
            });
            context.awaitUntil(followUpFired::get, 60, "the sub-charge follow-up to fire");
            context.awaitTicks(2); // let any MONITOR dispatch settle
            double followUpScale = Ct8cChargeView.INSTANCE.currentScale(attacker.uuid());
            context.expect(followUpScale < 1.0,
                    "the staged follow-up was not sub-charge (scale " + followUpScale + " ≥ 1.0) — the 2-tick "
                            + "gate pin regressed, so the deny assertion below would be vacuous");
            context.expect(landed.get() == 0,
                    "the sub-charge follow-up was not denied (landed " + landed.get() + ", scale "
                            + followUpScale + ") — the CT8c full-recharge gate regressed");

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
    /*  2b. ct8c-crits — the canonical non-sprint jump crit lands ×1.5      */
    /* ------------------------------------------------------------------ */

    /**
     * The CT8c flat ×1.5 crit on the canonical NON-sprint jump crit (spec §2.3)
     * — the exact hit the shipped v2.9.0 guard inverted (it yielded on charged
     * non-sprint posture on the false premise that vanilla's crit was still in
     * BASE, after {@code Ct8cDamageUnit} had overwritten BASE crit-free; only
     * sprint crits ×1.5'd). Both bundle units are enabled together, the attacker
     * is staged falling in the same tick as the swing (teleport up un-grounds,
     * {@code setFallDistance} arms the posture — the whole {@code
     * isLegacyCritical} read), and the BASE the captor reads must be the table
     * value × the kernel crit multiplier: diamond sword 6 → 9.
     */
    private static void runCritComposition(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            setFeature(mental, context, Feature.CT8C_DAMAGE, true);
            setFeature(mental, context, Feature.CT8C_CRITS, true);
            context.expect(mental.featureActive(Feature.CT8C_CRITS), "ct8c-crits did not converge active");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            double expected = context.sync(() -> {
                ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
                attacker.player().getInventory().setItemInMainHand(sword);
                return DamageShaper.ct8cToolBase(sword) * DamageTables.critMultiplier();
            });
            context.awaitTicks(2);
            captors.reset();
            // Posture-anchored staging (the clientless-fake rule): lift the fake
            // well off the floor and arm the fall, then swing only once the WHOLE
            // isLegacyCritical read is OBSERVED true — a same-tick teleport does
            // not clear onGround on every legacy revision (its entity tick does,
            // one tick into the fall), so a fixed-tick swing lands grounded there
            // and the case would fail vacuously. Not sprinting — the very case
            // the old guard lost. 2.5 blocks buys ~10 falling ticks of margin.
            context.syncRun(() -> {
                Player raw = attacker.player();
                raw.teleport(raw.getLocation().add(0, 2.5, 0));
                raw.setFallDistance(1.0f);
            });
            context.awaitUntil(() -> DamageShaper.isLegacyCritical(attacker.player()), 20,
                    "the staged fall to be observed as the legacy crit posture");
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "the jump-crit hit to land");
            Double base = captors.damageOf(victim.uuid());
            context.expect(base != null, "the jump-crit hit produced no damage event");
            context.expectNear(expected, base, DAMAGE_EPSILON,
                    "the canonical non-sprint jump crit must land the CT8c base × the flat 1.5 "
                            + "(Ct8cDamageUnit composes crit-free at LOWEST; Ct8cCritsUnit applies at LOW)");
        } finally {
            setFeature(mental, context, Feature.CT8C_CRITS, false);
            setFeature(mental, context, Feature.CT8C_DAMAGE, false);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  3. ct8c-iframes — the window scales with the attacker's weapon      */
    /* ------------------------------------------------------------------ */

    /**
     * The CT8c melee i-frame window (spec §2.4). 8c makes the WHOLE
     * {@code min(attackerAttackDelay, 10)}-tick window difference-damage
     * ({@code invulnerableTime > 0}); Mental writes {@link Ct8cIframesUnit#windowField}
     * — {@code 2 ×} that — into {@code maximumNoDamageTicks} so CraftBukkit's
     * half-window gate reproduces the full 8c window (never a raw positive
     * {@code setNoDamageTicks} — the field write sidesteps the 1.16.5–1.20.6
     * total-invuln trap). A diamond sword (attr 4.5 → 8c window 7) writes 14; a
     * diamond axe (attr 3.5 → window 10) writes 20 — which equals the vanilla default,
     * faithfully so: 8c's slowest weapon's 10-tick full window IS vanilla's half of 20.
     * The scaling shows in sword 14 ≠ axe 20; the sword (last hit) then restores to
     * the vanilla 20 once drained.
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

            int vanillaWindow = context.sync(() -> victim.player().getMaximumNoDamageTicks());

            // The written field is 2× the 8c logical window (full-window diff-damage via
            // the half-gate). Axe (window 10 → field 20) equals the vanilla default —
            // faithful; the sword (window 7 → field 14) is distinct. Sword is landed
            // LAST so the restore pin below watches a field that differs from vanilla.
            int axeWindow = assertIframeWindow(context, captors, attacker, victim,
                    new ItemStack(Material.DIAMOND_AXE),
                    Ct8cIframesUnit.windowField(Ct8cIframesUnit.iframeTicks(3.5)), "diamond axe");
            int swordWindow = assertIframeWindow(context, captors, attacker, victim,
                    new ItemStack(Material.DIAMOND_SWORD),
                    Ct8cIframesUnit.windowField(Ct8cIframesUnit.iframeTicks(4.5)), "diamond sword");
            context.expect(swordWindow != axeWindow,
                    "the i-frame window must SCALE with weapon speed (sword " + swordWindow
                            + " == axe " + axeWindow + ")");
            context.expect(swordWindow != vanillaWindow,
                    "the sword's shaped window " + swordWindow + " must differ from the vanilla "
                            + vanillaWindow + " for the restore pin to mean anything");

            // The leak regression pin: maximumNoDamageTicks is a PERSISTENT entity
            // field, so the unit must hand the vanilla window back once the shaped
            // window drains — otherwise fire/cactus/drowning re-apply on the shrunken
            // cycle. The generation-keyed hand-back re-checks past the field's own
            // drain, so poll a little past it. (The sword was the last hit — its 14.)
            context.awaitUntil(() -> victim.player().getMaximumNoDamageTicks() == vanillaWindow,
                    swordWindow + 20,
                    "the shaped i-frame window to restore to the vanilla "
                            + vanillaWindow + " once drained");
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

    /**
     * The shrunken i-frame window must NEVER bleed into environmental damage — the
     * report: "falling in lava/berries spam-hurts the player every tick." {@code
     * maximumNoDamageTicks} is a PERSISTENT, all-causes entity field; the CT8c unit
     * shrinks it for a melee/projectile hit and schedules a drain-time restore. The
     * v2.9.0-era restore keyed the drain off the LIVE hurt counter — but a victim
     * standing in a hazard (lava, sweet-berry bush, fire, cactus) has that counter
     * re-armed to the shrunken window every few ticks, so the "counter still live"
     * reschedule NEVER drained and the shrunken window (with its 2–3× faster
     * environmental cadence) persisted for as long as the hazard did.
     *
     * <p>This drives exactly that: shrink the window with a real sword hit, then
     * feed continuous environmental damage — a source-less {@code damage(1.0)}, the
     * lava/berry proxy (cause CUSTOM, NOT a by-entity hit, so it never re-enters the
     * CT8c unit to re-shrink) — and assert the window STILL restores to the vanilla
     * default. A starved (counter-keyed) restore leaves it pinned at the shrunken
     * value and this times out; the generation-keyed restore returns it on schedule
     * because only a genuine CT8c re-hit — never a hazard tick — defers it.</p>
     */
    private static void runIframeEnvironmentalCadence(
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
                attacker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            });
            context.awaitTicks(5);

            int vanillaWindow = context.sync(() -> victim.player().getMaximumNoDamageTicks());

            // Shrink the window with a real melee hit (diamond sword ⇒ a 7-tick window).
            captors.reset();
            int shrunk = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
                return victim.player().getMaximumNoDamageTicks();
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40, "the shrinking melee hit to land");
            context.expect(shrunk < vanillaWindow,
                    "the melee hit must shrink the window below the vanilla " + vanillaWindow + " (got " + shrunk + ")");

            // Feed continuous environmental damage at a hazard's cadence, keeping the
            // hurt counter alive. The window must still restore within a few ticks of
            // its own duration; a counter-starved restore never will. Health is topped
            // each tick so the victim survives the whole hazard window.
            int budget = shrunk + 15;
            boolean restored = false;
            for (int i = 0; i < budget; i++) {
                int window = context.sync(() -> {
                    Player p = victim.player();
                    p.setHealth(p.getMaxHealth());
                    p.damage(1.0); // the lava/berry proxy — source-less, so never a CT8c re-shrink
                    return p.getMaximumNoDamageTicks();
                });
                if (window == vanillaWindow) {
                    restored = true;
                    break;
                }
                context.awaitTicks(1);
            }
            context.expect(restored,
                    "under continuous environmental damage the CT8c i-frame window must still restore to the vanilla "
                            + vanillaWindow + " — it stayed pinned at " + shrunk + ", so a hazard tick kept re-arming "
                            + "the shrunken window (the lava/berry spam the report describes)");
        } finally {
            setFeature(mental, context, Feature.CT8C_IFRAMES, false);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /**
     * The 8c FULL-window difference-damage (spec §2.4; hurt() gate {@code
     * invulnerableTime > 0}). 8c makes the WHOLE {@code min(delay,10)}-tick window
     * difference-damage: a stronger re-hit deals only the difference, a same-or-weaker
     * one deals NOTHING, and a fresh full hit is allowed only AFTER the window fully
     * elapses. CraftBukkit's own gate is the HALF-window ({@code noDamageTicks >
     * max/2}), so a re-hit past the half-point but still inside the 8c window would
     * wrongly land as fresh. Mental writes {@code 2 ×} the window ({@link
     * Ct8cIframesUnit#windowField}) so the half-gate spans the full window.
     *
     * <p>Land a full-charge diamond-sword opener (8c window 7 ⇒ field 14), then re-hit
     * with the SAME sword exactly 5 ticks later — inside the 8c window (7) but past the
     * pre-fix half-window (~3.5). Under 8c's full window that re-hit is difference-damage
     * and, being no stronger than the opener, deals NOTHING; under the old half-window
     * it landed fresh and dealt damage. So the re-hit must leave the victim's health
     * unchanged. The re-hit's damage is measured in a single tick, so natural regen
     * never enters the reading.</p>
     */
    private static void runIframeFullWindowDiffDamage(
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
                attacker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            });
            context.awaitTicks(6); // let the attacker's swing timer recover to full charge

            // The opener: a fresh, full-charge sword hit. It sets lastDamage high and
            // arms the 14-tick field (8c window 7). Measured in-tick.
            captors.reset();
            double openerDamage = context.sync(() -> {
                victim.player().setHealth(victim.player().getMaxHealth());
                victim.player().setNoDamageTicks(0);
                double before = victim.player().getHealth();
                attacker.attack(victim.player());
                return before - victim.player().getHealth();
            });
            context.expect(openerDamage > 0.5, "the opener sword hit must land (dealt " + openerDamage + ")");

            // Re-hit exactly 5 ticks in — inside the 8c window (7), past the old
            // half-window. NO setNoDamageTicks(0): the hurt counter is still live.
            context.awaitTicks(5);
            double reHitDamage = context.sync(() -> {
                double before = victim.player().getHealth();
                attacker.attack(victim.player());
                return before - victim.player().getHealth();
            });
            context.expect(reHitDamage < 1.0,
                    "a same-weapon re-hit 5 ticks in — inside the 8c min(delay,10) window but past the old "
                            + "half-window — must deal NOTHING under 8c full-window difference-damage; it dealt "
                            + reHitDamage + ", so the window is only the vanilla half");
        } finally {
            setFeature(mental, context, Feature.CT8C_IFRAMES, false);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
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
        // The teleport un-grounds the clientless fake for a few physics ticks. Ct8cShieldUnit's block gate
        // (isBlockingPosture) reads onGround && sneaking && offhand SHIELD and the axe disable is written
        // SYNCHRONOUSLY inside the damage event, so a strike before the fake re-settles finds no block and the
        // disable never fires — waiting longer AFTER the strike cannot recover it. A fixed 3-tick settle raced
        // the slower legacy NMS ground-settle (1.13.2/1.15.2 lost it even at light load; 1.17.1 only under the
        // 15-JVM matrix), a staging race, not a product gap. First let the un-grounding register, then anchor to
        // the real precondition — the grounded crouch-to-shield posture — before any strike is staged.
        context.awaitTicks(2);
        context.awaitUntil(() -> blockPostureHeld(victim), 20,
                "the victim to re-settle into the grounded crouch-to-shield posture before the strike");
    }

    /** The live crouch-to-shield precondition Ct8cShieldUnit gates on: grounded, sneaking, offhand SHIELD. */
    @SuppressWarnings("deprecation") // the client-reported onGround flag is the block gate's own precondition
    private static boolean blockPostureHeld(FakePlayer victim) {
        Player player = victim.player();
        return player.isOnGround() && player.isSneaking()
                && player.getInventory().getItemInOffHand().getType() == Material.SHIELD;
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
     * gap; asserted here as an empty handle. At/above the floor the registry chain
     * is javap-pinned against the live 1.21.4 AND 26.1.2 jars (including the
     * year-scheme {@code Identifier} spelling), so the handle is now a HARD
     * expectation — an empty handle at the floor is the exact green-but-unproven
     * soft spot the v2.9.0 gate shipped with, and it fails loudly here instead of
     * degrading to a note. Where installed, {@link CleavingHandle#levelOf} must
     * read an enchanted axe (and 0 off a plain one).
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

            context.expect(handle.isPresent(),
                    "the Cleaving injection must land at/above the ~1.21.3 floor — the chain is javap-pinned "
                            + "on 1.21.4 and 26.1.2, so an empty handle here is a real regression ("
                            + CleavingRegistrar.describe() + ")");
            if (handle.isEmpty()) {
                return; // the expect above already recorded the failure
            }

            // Installed: the handle must read a Cleaving-enchanted axe and 0 off a plain one.
            CleavingHandle live = handle.get();
            context.expect(live.levelOf(new ItemStack(Material.DIAMOND_AXE)) == 0,
                    "a plain axe must read Cleaving level 0");
            Enchantment cleaving = resolveCleaving();
            context.expect(cleaving != null,
                    "the mental:cleaving Bukkit enchant must resolve via getByKey once the handle is installed — "
                            + "install() itself resolved it that way (" + CleavingRegistrar.describe() + ")");
            if (cleaving == null) {
                return; // the expect above already recorded the failure
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
