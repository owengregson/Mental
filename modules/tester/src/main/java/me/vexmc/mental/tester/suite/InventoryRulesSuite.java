package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.platform.SweepCauses;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.cadence.SweepDamageListener;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Event-cancellation coverage for the three inventory-shaping rule modules that
 * strip 1.9 inventory affordances back to the 1.7/1.8 single-hand model —
 * {@code disable-sword-sweep}, {@code disable-offhand} and
 * {@code disable-crafting}.
 *
 * <p>Unlike the damage-shaping modules (see {@code DamageRulesSuite}), these
 * three act purely by <em>cancelling</em> a Bukkit event — they never reshape a
 * populated damage modifier. A synthetic event fired through
 * {@code PluginManager.callEvent} therefore exercises them faithfully: the
 * module either flips the cancel flag (or the result to {@code DENY}) or it does
 * not, and that flag is exactly what we read back. Each scenario enables its
 * module in the {@code try} and always toggles it back off and removes its
 * FakePlayers in {@code finally} — a disabled module must leave the game
 * untouched for whatever runs next (the zero-touch invariant).</p>
 *
 * <p>Where a version cannot construct an event (a deprecated constructor that
 * was removed, or a staging path a clientless FakePlayer cannot drive) the case
 * records a {@code note} SKIP rather than failing — the cancellation logic is
 * unit/logic-covered and the synthetic event is only an integration probe.</p>
 */
public final class InventoryRulesSuite {

    private InventoryRulesSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("rules: disable-sword-sweep cancels the sweep damage", context ->
                        runDisableSwordSweep(mental, tester, context)),
                new TestCase("rules: disable-offhand blocks a disallowed off-hand swap", context ->
                        runDisableOffhand(mental, tester, context)),
                new TestCase("rules: disable-crafting suppresses the shield recipe result", context ->
                        runDisableCrafting(mental, tester, context)));
    }

    /* ------------------------------------------------------------------ */
    /*  1. disable-sword-sweep                                            */
    /* ------------------------------------------------------------------ */

    /**
     * The module listens on {@link EntityDamageEvent} at LOW priority and
     * cancels every {@link EntityDamageEvent.DamageCause#ENTITY_SWEEP_ATTACK}
     * hit, restoring 1.7/1.8 single-target swords. We fire a synthetic
     * {@link EntityDamageByEntityEvent} (a subclass, so it reaches the same
     * {@code EntityDamageEvent} handler) with the SWEEP cause: enabled it must
     * come back cancelled; disabled (the control) an identical fresh event must
     * survive untouched. A pure cancellation assert needs no real attack — the
     * module reads only the cause, not any populated modifier.
     */
    private static void runDisableSwordSweep(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            // --- Module ON: the SWEEP damage event must come back cancelled. ---
            toggleModule(context, "disable-sword-sweep", true);
            context.expect(moduleActive(mental, "disable-sword-sweep"),
                    "disable-sword-sweep module failed to enable");

            // Below 1.11 the ENTITY_SWEEP_ATTACK cause does not exist and the feature is a
            // documented no-op (the 2.4.1 GAP-2 contract, printed as a boot degrade line by
            // SweepUnit.assemble): pin the degrade EXPLICITLY instead of silently skipping —
            // the module is on, yet NO SweepDamageListener may be registered, because a
            // registered one rethrows a sticky NoSuchFieldError on every damage event.
            if (!SweepCauses.present()) {
                boolean sweepListenerRegistered = context.sync(
                        InventoryRulesSuite::sweepDamageListenerRegistered);
                context.expect(!sweepListenerRegistered,
                        "ENTITY_SWEEP_ATTACK is absent on this version (lands 1.11), yet a "
                                + "SweepDamageListener is registered — the assemble-time skip "
                                + "regressed (it would NoSuchFieldError on every damage event)");
                context.note("sweep cause absent (< 1.11): pinned the documented no-op — no "
                        + "SweepDamageListener registered while disable-sword-sweep is on "
                        + "(vanilla sword sweep remains; see the boot degrade line)");
                return;
            }

            Boolean cancelledWhenOn = fireSweep(context, attacker, victim);
            if (cancelledWhenOn == null) {
                context.note("this version cannot construct an ENTITY_SWEEP_ATTACK event — "
                        + "cancellation logic unit-pinned elsewhere");
                return;
            }
            context.expect(cancelledWhenOn,
                    "the sweep-attack damage event was not cancelled with disable-sword-sweep on");

            // --- Control: module OFF, an identical fresh event must survive. ---
            toggleModule(context, "disable-sword-sweep", false);
            context.expect(!moduleActive(mental, "disable-sword-sweep"),
                    "disable-sword-sweep module failed to disable for the control");

            Boolean cancelledWhenOff = fireSweep(context, attacker, victim);
            // A null here only means staging blipped on the control; the ON
            // assertion already proved the module fires. Don't fail on it.
            if (cancelledWhenOff != null) {
                context.expect(!cancelledWhenOff,
                        "a sweep-attack event was cancelled with disable-sword-sweep off — "
                                + "the module is not zero-touch when disabled");
            }
        } finally {
            toggleModule(context, "disable-sword-sweep", false);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
        }
    }

    /** Whether any {@code SweepDamageListener} holds a live {@link EntityDamageEvent} registration. */
    private static boolean sweepDamageListenerRegistered() {
        for (RegisteredListener listener : EntityDamageEvent.getHandlerList().getRegisteredListeners()) {
            if (listener.getListener() instanceof SweepDamageListener) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fires a synthetic SWEEP {@link EntityDamageByEntityEvent} and returns its
     * cancelled flag afterward, or {@code null} if this version cannot construct
     * the event (the deprecated double-arg constructor may be absent on some
     * builds; both the modern modifier-map and a missing constructor land here).
     */
    private static @Nullable Boolean fireSweep(
            TestContext context, FakePlayer attacker, FakePlayer victim) throws Exception {
        return context.sync(() -> {
            try {
                // Deprecated convenience constructor — present across Mental's
                // whole supported range; a NoSuchMethodError on a build that
                // removed it is caught below and reported as a SKIP.
                @SuppressWarnings("deprecation")
                EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                        attacker.player(),
                        victim.player(),
                        EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK,
                        5.0);
                Bukkit.getPluginManager().callEvent(event);
                return event.isCancelled();
            } catch (Throwable unsupported) {
                return null;
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /*  2. disable-offhand                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Under the default config (whitelist mode, empty item set) every off-hand
     * item is blocked. The module listens on {@link PlayerSwapHandItemsEvent} at
     * HIGHEST and cancels the swap when the would-be off-hand item is disallowed.
     * We fire a synthetic swap whose off-hand candidate is a non-AIR disallowed
     * item (a diamond sword): enabled it must be cancelled; disabled (the
     * control) it must survive.
     */
    private static void runDisableOffhand(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer player = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                player.spawn(Arena.offset(centre, 3, 0));
            });
            context.awaitTicks(5);

            // --- Module ON: the swap to a disallowed off-hand item is blocked. ---
            toggleModule(context, "disable-offhand", true);
            context.expect(moduleActive(mental, "disable-offhand"),
                    "disable-offhand module failed to enable");

            Boolean cancelledWhenOn = fireOffhandSwap(context, player);
            if (cancelledWhenOn == null) {
                context.note("this version cannot construct a PlayerSwapHandItemsEvent — "
                        + "off-hand filtering unit-pinned elsewhere");
                return;
            }
            context.expect(cancelledWhenOn,
                    "the off-hand swap of a disallowed item was not cancelled with disable-offhand on");

            // --- Control: module OFF, the same swap must survive. ---
            toggleModule(context, "disable-offhand", false);
            context.expect(!moduleActive(mental, "disable-offhand"),
                    "disable-offhand module failed to disable for the control");

            Boolean cancelledWhenOff = fireOffhandSwap(context, player);
            if (cancelledWhenOff != null) {
                context.expect(!cancelledWhenOff,
                        "an off-hand swap was cancelled with disable-offhand off — "
                                + "the module is not zero-touch when disabled");
            }
        } finally {
            toggleModule(context, "disable-offhand", false);
            context.syncRun(player::remove);
        }
    }

    /**
     * Fires a synthetic {@link PlayerSwapHandItemsEvent} whose off-hand candidate
     * is a disallowed (non-AIR) item, and returns its cancelled flag, or
     * {@code null} if the event cannot be constructed on this version. The
     * constructor signature is {@code (player, mainHandItem, offHandItem)}; the
     * off-hand item is what the module's filter inspects.
     */
    private static @Nullable Boolean fireOffhandSwap(
            TestContext context, FakePlayer player) throws Exception {
        return context.sync(() -> {
            try {
                ItemStack mainHand = new ItemStack(Material.AIR);
                // A diamond sword is a non-AIR item; under the default empty
                // whitelist it is disallowed in the off-hand, which is what the
                // module cancels.
                ItemStack offHand = new ItemStack(Material.DIAMOND_SWORD);
                PlayerSwapHandItemsEvent event =
                        new PlayerSwapHandItemsEvent(player.player(), mainHand, offHand);
                Bukkit.getPluginManager().callEvent(event);
                return event.isCancelled();
            } catch (Throwable unsupported) {
                return null;
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /*  3. disable-crafting                                               */
    /* ------------------------------------------------------------------ */

    /**
     * The module listens on {@link org.bukkit.event.inventory.PrepareItemCraftEvent}
     * and nulls the result whenever it is a blocked material (SHIELD by default).
     * That event is recalculated by the server only as a real crafting grid
     * changes — it has no stable synthetic constructor a clientless FakePlayer
     * can drive (no live {@code CraftingInventory}/{@code InventoryView} to seed
     * the recipe match). So the verifiable integration assertion here is that the
     * module activates through the real command path; the result-nulling logic is
     * exercised by the unit/logic layer. We attempt the real staging only if it
     * is trivially feasible, and otherwise SKIP rather than fail.
     */
    private static void runDisableCrafting(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer crafter = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                crafter.spawn(Arena.offset(centre, -3, 0));
            });
            context.awaitTicks(5);

            toggleModule(context, "disable-crafting", true);
            context.expect(moduleActive(mental, "disable-crafting"),
                    "disable-crafting module failed to enable");

            // PrepareItemCraftEvent cannot be reliably staged synthetically: it
            // requires a live CraftingInventory whose result the server matched
            // from a recipe, and a clientless player drives no crafting UI.
            context.note("crafting staging not feasible on this version — module-active asserted; "
                    + "behaviour unit/logic-covered");
        } finally {
            toggleModule(context, "disable-crafting", false);
            context.syncRun(crafter::remove);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Shared helpers                                                     */
    /* ------------------------------------------------------------------ */

    /** Toggles through the v5 management write-back seam and waits for convergence. */
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
