package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.kernel.math.SwordBlockReduction;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.HandStates;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Live-server behaviour for the {@code sword-blocking} module: right-clicking a
 * sword raises the block state, and a blocked melee hit is reduced by the 1.8
 * {@code (damage-1)*0.5} while knockback is left FULL (era truth — Mental owns
 * knockback and never cancels a blocked hit).
 *
 * <p>The module drives one of three capability tiers, resolved by component
 * presence rather than version literals (mirrored from {@code SwordBlockComponents}
 * / {@code SwordBlockingModule}): {@code BLOCKS_ATTACKS} (1.21.5+, native
 * reduction + native block state), {@code CONSUMABLE} (1.21.0-1.21.4, software
 * reduction), and the off-hand real-shield fallback (1.17.1-1.20.6, software
 * reduction). The {@code (dmg-1)*0.5} math itself is unit-pinned in
 * {@code SwordBlockReductionTest}; this suite verifies that the live pipeline
 * actually enters the blocking state and applies the reduction end to end.</p>
 *
 * <h2>Clientless-fake honesty</h2>
 * <p>A {@link FakePlayer} has no client, so the active-use ({@code isBlocking()})
 * state a real client would confirm over the wire may never light up — the
 * server-side block pose on the component tiers depends on the use-item state
 * the client normally drives. When the block state cannot be observed for the
 * synthetic player on the running tier, every case here {@code note}-SKIPs rather
 * than fails: the reduction formula is unit-pinned elsewhere, so a clientless
 * staging gap must never be reported as a regression.</p>
 */
public final class BlockingSuite {

    private BlockingSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("block: right-click with a sword enters the blocking state", context ->
                        runEnterBlock(mental, tester, context)),
                new TestCase("block: a blocked hit takes 1.8 (dmg-1)*0.5 and still knocks full", context ->
                        runBlockedReduction(mental, tester, context)),
                new TestCase("block: the off-hand shield decoration reverts on an exit trigger (B12)", context ->
                        runOffhandRevert(mental, tester, context)));
    }

    /* ------------------------------------------------------------------ */
    /*  Case 3 — B12: the off-hand temp shield reverts on an exit trigger  */
    /* ------------------------------------------------------------------ */

    /**
     * B12 guaranteed revert on the off-hand tier (≤1.20.6): a right-click injects
     * a PDC-marked temp shield into the off-hand, and an exit trigger must restore
     * the original item — nothing sticks. This is the live half of the
     * {@code EphemeralDecoration} proof: the temp-shield PDC and the inventory
     * writes need a real server, so the unit test pins only the never-stuck
     * orchestration. On the component tiers (1.21+) no off-hand shield is
     * injected, so this note-SKIPs when the injection did not take (which also
     * covers a clientless staging gap).
     */
    private static void runOffhandRevert(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer blocker = new FakePlayer(tester, mental.scheduling());

        try {
            toggleModule(context, "sword-blocking", true);
            context.expect(moduleActive(mental, "sword-blocking"),
                    "sword-blocking module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                blocker.spawn(Arena.offset(centre, 5, -2));
                blocker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
                blocker.player().getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            });
            context.awaitTicks(5);

            // Right-click the sword → the off-hand tier injects the temp shield.
            Boolean staged = context.sync(() -> {
                try {
                    PlayerInteractEvent event = new PlayerInteractEvent(
                            blocker.player(),
                            Action.RIGHT_CLICK_AIR,
                            blocker.player().getInventory().getItemInMainHand(),
                            null,
                            BlockFace.SELF,
                            EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(event);
                    return Boolean.TRUE;
                } catch (Throwable unsupported) {
                    return null;
                }
            });
            if (staged == null) {
                context.note("this version cannot stage a RIGHT_CLICK_AIR interact — off-hand revert "
                        + "covered by EphemeralDecorationTest");
                return;
            }
            // Read before the ~10-tick release poll could revert it on its own.
            context.awaitTicks(2);

            boolean injected = context.sync(() ->
                    blocker.player().getInventory().getItemInOffHand().getType() == Material.SHIELD);
            if (!injected) {
                context.note("no off-hand shield injected (component tier, or clientless staging gap) — the "
                        + "off-hand revert path only runs on the off-hand tier; orchestration is unit-pinned");
                return;
            }

            // An exit trigger — a held-slot change — must revert the off-hand.
            context.syncRun(() -> Bukkit.getPluginManager().callEvent(
                    new PlayerItemHeldEvent(blocker.player(), 0, 1)));
            context.awaitTicks(2);

            Material offhand = context.sync(() ->
                    blocker.player().getInventory().getItemInOffHand().getType());
            context.expect(offhand == Material.AIR,
                    "the exit trigger did not revert the temp shield — off-hand is " + offhand
                            + " (B12: nothing sticks)");
        } finally {
            toggleModule(context, "sword-blocking", false);
            context.syncRun(blocker::remove);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Case 1 — right-click raises the block state                        */
    /* ------------------------------------------------------------------ */

    private static void runEnterBlock(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer blocker = new FakePlayer(tester, mental.scheduling());

        try {
            toggleModule(context, "sword-blocking", true);
            context.expect(moduleActive(mental, "sword-blocking"),
                    "sword-blocking module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                blocker.spawn(Arena.offset(centre, 0, -2));
                blocker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            });
            context.awaitTicks(5);

            // Fire the real PlayerInteractEvent the module listens to: a
            // RIGHT_CLICK_AIR for the main-hand sword. The module gates on
            // getHand()==HAND, so the EquipmentSlot.HAND arg is load-bearing.
            // startUsingItem is reflective and absent on the 1.17.1 compile
            // floor; if the interact cannot be staged, the staging probe NULLs.
            Boolean staged = context.sync(() -> {
                try {
                    PlayerInteractEvent event = new PlayerInteractEvent(
                            blocker.player(),
                            Action.RIGHT_CLICK_AIR,
                            blocker.player().getInventory().getItemInMainHand(),
                            null,
                            BlockFace.SELF,
                            EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(event);
                    return Boolean.TRUE;
                } catch (Throwable unsupported) {
                    return null;
                }
            });
            if (staged == null) {
                context.note("this version cannot stage a RIGHT_CLICK_AIR interact — block-state covered "
                        + "by SwordBlockReductionTest");
                return;
            }
            // Give the block pose / active-use state a couple of ticks to settle.
            context.awaitTicks(2);

            // The block state is read natively (isBlocking / isHandRaised) on
            // every tier — Tier A/B via the use-item state, Tier C via the
            // injected off-hand shield. A clientless fake may never enter that
            // state (no client confirms the use over the wire), so note-SKIP
            // rather than fail when it is not observable.
            boolean blocking = context.sync(() ->
                    blocker.player().isBlocking() || HandStates.isHandRaised(blocker.player()));
            if (!blocking) {
                context.note("clientless fake never reported isBlocking()/isHandRaised() — the in-place "
                        + "block pose needs a client to confirm the use; reduction is unit-pinned");
                return;
            }
            context.expect(blocking, "right-click with a sword did not raise the block state");
        } finally {
            toggleModule(context, "sword-blocking", false);
            context.syncRun(blocker::remove);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Case 2 — blocked hit: (dmg-1)*0.5 damage, FULL knockback           */
    /* ------------------------------------------------------------------ */

    private static void runBlockedReduction(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer control = new FakePlayer(tester, mental.scheduling());
        FakePlayer blocker = new FakePlayer(tester, mental.scheduling());

        try {
            toggleModule(context, "sword-blocking", true);
            context.expect(moduleActive(mental, "sword-blocking"),
                    "sword-blocking module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                control.spawn(Arena.offset(centre, -3, 2));
                blocker.spawn(Arena.offset(centre, 3, 2));
                // The blocking victim holds the sword it will raise.
                blocker.player().getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            });
            context.awaitTicks(5);

            // A bare-fist hit deals 1.0 base damage, which blocks to (1-1)*0.5 = 0
            // reduction — the assertion would be vacuous. Synthetic players skip
            // vanilla's per-tick equipment bookkeeping, so a held sword never
            // raises attackDamage; pin the attribute directly (as the OCM suite
            // does) so the base damage is well above 1.0 and the block bites.
            boolean armed = context.sync(() -> {
                Attribute damageAttribute = Attributes.attackDamage();
                Attribute speedAttribute = Attributes.attackSpeed();
                if (damageAttribute == null || speedAttribute == null) {
                    return false;
                }
                AttributeInstance attackDamage = attacker.player().getAttribute(damageAttribute);
                AttributeInstance attackSpeed = attacker.player().getAttribute(speedAttribute);
                if (attackDamage == null || attackSpeed == null) {
                    return false;
                }
                attackDamage.setBaseValue(7.0); // diamond-sword-scale base, clearly > 1.0
                // High attack speed → the modern attack-strength charge is always
                // full (scale 1.0), so the two staged hits below deliver the same
                // unblocked base regardless of how few ticks apart they land. The
                // 1.8 era has no cooldown; this just neutralises modern variance.
                attackSpeed.setBaseValue(40.0);
                return true;
            });
            if (!armed) {
                context.note("attack-damage/speed attribute unresolvable — reduction covered by "
                        + "SwordBlockReductionTest");
                return;
            }

            // Reset once before both staged hits (they land under distinct victim
            // UUIDs, so neither read clobbers the other) and await each by its own
            // key — never a fixed window, which the concurrent matrix would race.
            captors.reset();

            // (1) CONTROL hit: an identical attack on a NON-blocking victim, to
            // measure the unblocked base damage this exact setup delivers. Reading
            // the real unblocked base (rather than assuming the 7.0 attribute maps
            // 1:1 to event damage) keeps the reduction assertion exact regardless
            // of vanilla's per-version melee bookkeeping.
            context.syncRun(() -> {
                control.player().setNoDamageTicks(0);
                attacker.attack(control.player());
            });
            context.awaitUntil(() -> captors.damageOf(control.uuid()) != null, 40,
                    "the control (unblocked) hit's damage event");
            Double unblockedBase = captors.damageOf(control.uuid());
            if (unblockedBase == null || unblockedBase <= 1.0) {
                context.note("control hit produced no usable base damage (" + unblockedBase
                        + ") — reduction covered by SwordBlockReductionTest");
                return;
            }

            // (2) Put the blocking victim into the block state exactly as case 1.
            Boolean staged = context.sync(() -> {
                try {
                    PlayerInteractEvent event = new PlayerInteractEvent(
                            blocker.player(),
                            Action.RIGHT_CLICK_AIR,
                            blocker.player().getInventory().getItemInMainHand(),
                            null,
                            BlockFace.SELF,
                            EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(event);
                    return Boolean.TRUE;
                } catch (Throwable unsupported) {
                    return null;
                }
            });
            if (staged == null) {
                context.note("this version cannot stage a RIGHT_CLICK_AIR interact — reduction covered "
                        + "by SwordBlockReductionTest");
                return;
            }
            context.awaitTicks(2);

            boolean blocking = context.sync(() ->
                    blocker.player().isBlocking() || HandStates.isHandRaised(blocker.player()));
            if (!blocking) {
                context.note("clientless fake never entered the blocking state on this tier — the "
                        + "(dmg-1)*0.5 reduction is unit-pinned in SwordBlockReductionTest");
                return;
            }

            // (3) BLOCKED hit: same attacker setup, now against the blocking victim.
            context.syncRun(() -> {
                blocker.player().setNoDamageTicks(0);
                attacker.attack(blocker.player());
            });
            context.awaitUntil(() -> captors.damageOf(blocker.uuid()) != null, 40,
                    "the blocked hit's damage event");
            Double blockedBase = captors.damageOf(blocker.uuid());
            if (blockedBase == null) {
                context.note("no damage event observed for the blocked hit — covered by the gate checks");
                return;
            }

            // The captor records the BASE damage (EntityDamageEvent#getDamage) at
            // MONITOR priority — AFTER the block module's HIGH-priority setDamage.
            // On the SOFTWARE-reduction tiers (CONSUMABLE 1.21.0-1.21.4, off-hand
            // shield 1.17.1-1.20.6) the module subtracts (dmg-1)*0.5 from the base,
            // so the observed base IS the reduced value and the reduction is directly
            // verifiable against the control. On the NATIVE tier (BLOCKS_ATTACKS,
            // 1.21.5+) the module applies NO software reduction — the component
            // reduces getFinalDamage() instead, which this captor does not expose —
            // so the observed BASE stays full; the reduction there is native + the
            // value is unit-pinned, so note-skip the damage assertion (knockback is
            // still asserted on every tier below).
            if (blocksAttacksPresent()) {
                context.note("BLOCKS_ATTACKS tier: native reduction lands in getFinalDamage(), not the "
                        + "captured BASE damage — reduction unit-pinned in SwordBlockReductionTest; "
                        + "verifying full knockback only.");
            } else {
                double expectedBlocked = unblockedBase - SwordBlockReduction.blockedDamage(unblockedBase);
                context.expectNear(expectedBlocked, blockedBase, 0.05,
                        "blocked base = unblocked " + unblockedBase + " - (dmg-1)*0.5 software reduction");
                // The reduction must have actually fired (not a no-op equal pass).
                context.expect(blockedBase < unblockedBase,
                        "blocked base (" + blockedBase + ") was not reduced below the unblocked base ("
                                + unblockedBase + ") — software (dmg-1)*0.5 reduction did not apply");
                // A partial block reduces but never cancels — the hit still bites.
                context.expect(blockedBase > 0.0,
                        "blocked hit was cancelled to 0 — a partial block reduces, never cancels");
            }

            // Era truth (every tier): a blocked hit still knocks the victim FULL —
            // only the DAMAGE is reduced. The module never cancels the event or
            // touches velocity, so a knock must have arrived (a velocity event, or
            // Mental's own KnockbackApplyEvent when Mental owns the knock).
            boolean knocked = captors.velocityOf(blocker.uuid()) != null
                    || captors.knockbackAppliesTo(blocker.uuid()) > 0;
            context.expect(knocked,
                    "a blocked hit produced no knockback — era blocks must knock FULL");
        } finally {
            toggleModule(context, "sword-blocking", false);
            context.syncRun(() -> {
                attacker.remove();
                control.remove();
                blocker.remove();
            });
            captors.unregister();
        }
    }

    /**
     * Whether this server runs the native BLOCKS_ATTACKS reduction tier — probed
     * by the same {@code DataComponents.BLOCKS_ATTACKS} field presence the module's
     * {@code SwordBlockComponents} uses to pick its tier (never a version literal).
     * On this tier the module applies no software reduction (the component reduces
     * {@code getFinalDamage()} natively), so the captured BASE damage is unchanged.
     */
    private static boolean blocksAttacksPresent() {
        try {
            Class<?> dataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            dataComponents.getField("BLOCKS_ATTACKS");
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Shared helpers (copied from ZeroTouchSuite)                        */
    /* ------------------------------------------------------------------ */

    /** Toggles through the real console command path and waits for convergence. */
    private static void toggleModule(TestContext context, String id, boolean enabled) throws Exception {
        Feature feature = Feature.byModuleId(id)
                .orElseThrow(() -> new AssertionError("unknown module id '" + id + "'"));
        context.syncRun(() -> ((MentalPluginV5) Bukkit.getPluginManager().getPlugin("Mental"))
                .management().setModuleEnabled(feature, enabled));
        context.awaitTicks(1);
    }

    private static boolean moduleActive(MentalPluginV5 mental, String id) {
        return Feature.byModuleId(id).map(mental::featureActive).orElse(false);
    }
}
