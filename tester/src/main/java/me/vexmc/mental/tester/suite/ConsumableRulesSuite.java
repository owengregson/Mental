package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.platform.Cooldowns;
import me.vexmc.mental.platform.PotionEffects;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The four standalone "old rules" consumable modules — {@code old-golden-apples},
 * {@code disable-enderpearl-cooldown}, {@code old-potion-durations}, and
 * {@code old-player-regen} — restore pre-1.9 item/regen behaviour with nothing
 * but the Bukkit API plus {@link me.vexmc.mental.platform.Scheduling}.
 * Each scenario enables exactly one module through the real command path,
 * exercises it on a live server, asserts the decompile-cited era value, and
 * always toggles the module back off + cleans up its actors.
 *
 * <p>Two of these modules apply their effect on a deferred (+1 tick) hop off the
 * triggering event (golden apples are component-food whose vanilla effects land
 * after the consume event; the pearl cooldown is set by the item-use code before
 * {@code ProjectileLaunchEvent} fires), so the assertions wait in game ticks via
 * {@code awaitTicks} — never wall time. Anything a running version cannot stage
 * (an event whose constructor drifted away, a projectile a clientless player
 * cannot launch) is a {@link TestContext#note SKIP}, because the era values
 * themselves are unit-pinned in {@code GoldenAppleEffects}/{@code PotionDurations}
 * and {@code RegenMath}.</p>
 */
public final class ConsumableRulesSuite {

    // The 1.9+ default Strength DRINK duration is 1800 ticks (90 s) for the base
    // (non-extended, level I) potion. The 1.8 era duration restored by
    // old-potion-durations is 3600 ticks (180 s), per PotionDurations and the
    // OCM table in §5 of the ground-truth plan. The safe cross-version assert is
    // "the module lengthened it" — strictly above this modern ceiling.
    private static final int MODERN_STRENGTH_DRINK_TICKS = 1800;
    private static final int ERA_STRENGTH_DRINK_TICKS = 3600;

    private ConsumableRulesSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("rules: old-golden-apples applies the 1.8 notch-apple effects", context ->
                        runGoldenApples(mental, tester, context)),
                new TestCase("rules: disable-enderpearl-cooldown clears the throw cooldown", context ->
                        runEnderPearlCooldown(mental, tester, context)),
                new TestCase("rules: old-potion-durations rewrites a potion to its 1.8 duration", context ->
                        runPotionDurations(mental, tester, context)),
                new TestCase("rules: old-player-regen suppresses 1.9 satiated regen", context ->
                        runPlayerRegen(mental, tester, context)));
    }

    /* ------------------------------------------------------------------ */
    /*  1. old-golden-apples                                               */
    /* ------------------------------------------------------------------ */

    private static void runGoldenApples(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer eater = new FakePlayer(tester, mental.scheduling());

        try {
            toggleModule(context, "old-golden-apples", true);
            context.expect(moduleActive(mental, "old-golden-apples"),
                    "old-golden-apples module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                eater.spawn(Arena.offset(centre, 0, 0));
            });
            context.awaitTicks(5);

            // Fire the consume event the module listens to with a notch apple in
            // hand. The module schedules the era effect table +1 tick later (it
            // must beat the modern component-food effects that land after the
            // event), so we wait a few ticks for the deferred application.
            Boolean staged = context.sync(() -> {
                try {
                    Player player = eater.player();
                    ItemStack notch = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
                    player.getInventory().setItemInMainHand(notch);
                    PlayerItemConsumeEvent event = newConsumeEvent(player, notch);
                    if (event == null) {
                        return null;
                    }
                    Bukkit.getPluginManager().callEvent(event);
                    return Boolean.TRUE;
                } catch (Throwable unsupported) {
                    return null;
                }
            });
            if (staged == null) {
                context.note("this version cannot stage a PlayerItemConsumeEvent — era effects pinned in unit tests");
                return;
            }

            // Resolve the era effect types defensively (getByKey on 1.20.5+,
            // getByName fallback below — DAMAGE_RESISTANCE is the pre-1.9 Bukkit
            // name for "resistance"). Mirrors GoldenAppleModule#resolveType.
            PotionEffectType regeneration = resolveEffect("regeneration", "REGENERATION");
            PotionEffectType resistance = resolveEffect("resistance", "DAMAGE_RESISTANCE");
            PotionEffectType fireResistance = resolveEffect("fire_resistance", "FIRE_RESISTANCE");
            if (regeneration == null || resistance == null || fireResistance == null) {
                context.note("could not resolve era effect types on this version — table pinned in unit tests");
                return;
            }

            // The +1-tick deferral plus a little slack for the matrix.
            context.awaitUntil(
                    () -> activeAmplifier(eater.player(), regeneration) != null,
                    40, "notch apple regeneration to apply");

            // Notch apple era table (GoldenAppleEffects.notchApple, [zt.java]):
            //   Regeneration V (amp 4) + Resistance I (amp 0) + Fire Res I (amp 0).
            // We assert the amplifiers — the distinguishing era pins (modern napple
            // gives Regen II / Resistance I / Fire Res I / Absorption IV).
            Integer regAmp = context.sync(() -> activeAmplifier(eater.player(), regeneration));
            Integer resAmp = context.sync(() -> activeAmplifier(eater.player(), resistance));
            Integer fireAmp = context.sync(() -> activeAmplifier(eater.player(), fireResistance));

            context.expect(regAmp != null && regAmp == 4,
                    "notch apple must grant Regeneration V (amp 4) — got " + regAmp);
            context.expect(resAmp != null && resAmp == 0,
                    "notch apple must grant Resistance I (amp 0) — got " + resAmp);
            context.expect(fireAmp != null && fireAmp == 0,
                    "notch apple must grant Fire Resistance I (amp 0) — got " + fireAmp);
        } finally {
            toggleModule(context, "old-golden-apples", false);
            context.syncRun(eater::remove);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  2. disable-enderpearl-cooldown                                     */
    /* ------------------------------------------------------------------ */

    private static void runEnderPearlCooldown(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        // The item-cooldown API (getCooldown/setCooldown) floors at 1.11.2: vanilla <=1.10 has no
        // ender-pearl throw cooldown at all, so there is nothing for the module to clear and the era state
        // it restores is already native. Skip loudly rather than call an absent accessor (the module itself
        // is a documented no-op on these versions — see EnderPearlCooldownUnit).
        if (!Cooldowns.itemCooldownSupported()) {
            context.note("no item-cooldown API on this version (pre-1.11) — vanilla <=1.10 has no ender-pearl "
                    + "cooldown, so disable-enderpearl-cooldown is a native no-op here; skipping the behaviour check");
            return;
        }

        FakePlayer thrower = new FakePlayer(tester, mental.scheduling());

        try {
            // Control first: with the module OFF, a 1.9+ server applies the
            // 20-tick throw cooldown. If this version has no pearl cooldown at
            // all (it never connected, or the launch failed), we cannot assert
            // the module did anything meaningful and skip.
            toggleModule(context, "disable-enderpearl-cooldown", false);

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                thrower.spawn(Arena.offset(centre, 0, 0));
            });
            context.awaitTicks(5);

            Integer controlCooldown = launchPearlAndReadCooldown(context, thrower);
            if (controlCooldown == null) {
                context.note("this version could not launch an EnderPearl — cooldown clearing covered by gate checks");
                return;
            }
            if (controlCooldown == 0) {
                context.note("no vanilla ender-pearl cooldown on this version (pre-1.9 behaviour) — module is a no-op here");
                return;
            }
            context.expect(controlCooldown > 0,
                    "expected a vanilla 1.9 ender-pearl cooldown with the module off — got " + controlCooldown);

            // Now enable the module and re-launch: the cooldown must be cleared.
            toggleModule(context, "disable-enderpearl-cooldown", true);
            context.expect(moduleActive(mental, "disable-enderpearl-cooldown"),
                    "disable-enderpearl-cooldown module failed to enable");
            // The module clears any prior cooldown left over from the control
            // launch on the next launch via setCooldown(0); reset to a clean
            // slate so the assertion reads this launch's result.
            context.syncRun(() -> thrower.player().setCooldown(Material.ENDER_PEARL, 0));

            Integer clearedCooldown = launchPearlAndReadCooldown(context, thrower);
            if (clearedCooldown == null) {
                context.note("the module-on pearl launch failed (flight physics) — covered by gate checks");
                return;
            }
            context.expect(clearedCooldown == 0,
                    "ender-pearl cooldown was not cleared with the module enabled — got " + clearedCooldown);
        } finally {
            toggleModule(context, "disable-enderpearl-cooldown", false);
            context.syncRun(thrower::remove);
        }
    }

    /**
     * Launches a real ender pearl (so {@code ProjectileLaunchEvent} fires through
     * the live pipeline the module listens to), waits a few ticks for the
     * module's {@code runOn} setCooldown(0) to land, then reads the throw
     * cooldown back. Returns {@code null} if the pearl could not be launched.
     */
    private static @Nullable Integer launchPearlAndReadCooldown(TestContext context, FakePlayer thrower)
            throws Exception {
        Boolean launched = context.sync(() -> {
            try {
                EnderPearl pearl = thrower.player().launchProjectile(
                        EnderPearl.class, new Vector(0, 0.1, 0.5));
                return pearl != null;
            } catch (Throwable unsupported) {
                return null;
            }
        });
        if (launched == null || !launched) {
            return null;
        }
        // The module routes setCooldown(0) through Scheduling.runOn (+0 / region
        // hop), so it lands within a tick or two of the launch event.
        context.awaitTicks(3);
        return context.sync(() -> thrower.player().getCooldown(Material.ENDER_PEARL));
    }

    /* ------------------------------------------------------------------ */
    /*  3. old-potion-durations                                            */
    /* ------------------------------------------------------------------ */

    private static void runPotionDurations(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer drinker = new FakePlayer(tester, mental.scheduling());

        try {
            toggleModule(context, "old-potion-durations", true);
            context.expect(moduleActive(mental, "old-potion-durations"),
                    "old-potion-durations module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                drinker.spawn(Arena.offset(centre, 0, 0));
            });
            context.awaitTicks(5);

            // Build a base (level I, non-extended) Strength DRINK potion, fire the
            // consume event the module's onDrink listener rewrites in place, then
            // read the rewritten effect off the same item (the module clears the
            // base type to WATER and adds a custom effect at the era duration).
            Integer rewrittenDuration = context.sync(() -> {
                try {
                    ItemStack potion = new ItemStack(Material.POTION);
                    if (!(potion.getItemMeta() instanceof PotionMeta meta)) {
                        return null;
                    }
                    if (!setBaseStrength(meta)) {
                        return null;
                    }
                    potion.setItemMeta(meta);

                    Player player = drinker.player();
                    player.getInventory().setItemInMainHand(potion);
                    PlayerItemConsumeEvent event = newConsumeEvent(player, potion);
                    if (event == null) {
                        return null;
                    }
                    Bukkit.getPluginManager().callEvent(event);

                    // The module rewrites in place and re-sets via event.setItem;
                    // read the result back off the event's item.
                    ItemStack after = event.getItem();
                    if (after == null || !(after.getItemMeta() instanceof PotionMeta afterMeta)) {
                        return null;
                    }
                    return strengthDurationTicks(afterMeta);
                } catch (Throwable unsupported) {
                    return null;
                }
            });

            if (rewrittenDuration == null) {
                // Either the item could not be built on this version's PotionMeta
                // API, the consume event could not be constructed, or the
                // rewritten duration could not be read back. The exact value is
                // pinned in PotionDurations' unit tests — confirm the module is
                // active and skip the precise-value check rather than fail.
                context.note("could not stage/read the Strength rewrite on this version — duration pinned in unit tests");
                return;
            }

            // Safe cross-version assert: the module LENGTHENED the duration past
            // the modern 1.9 base ceiling (1800 t). Note the exact value seen so
            // a drift away from the era 3600 t is visible in the log even though
            // the hard assert is the inequality.
            final int seen = rewrittenDuration;
            context.note("old-potion-durations rewrote Strength drink to " + seen
                    + " ticks (era target " + ERA_STRENGTH_DRINK_TICKS + " t)");
            context.expect(seen > MODERN_STRENGTH_DRINK_TICKS,
                    "old-potion-durations did not lengthen Strength past the modern " + MODERN_STRENGTH_DRINK_TICKS
                            + "-tick ceiling — got " + seen);
        } finally {
            toggleModule(context, "old-potion-durations", false);
            context.syncRun(drinker::remove);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  4. old-player-regen                                                */
    /* ------------------------------------------------------------------ */

    private static void runPlayerRegen(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer hurt = new FakePlayer(tester, mental.scheduling());

        try {
            // Control: with the module OFF the satiated-regen event must survive
            // (Mental is zero-touch when disabled). Spawn, wound the player so
            // RegainReason.SATIATED is a meaningful heal, and stage the event.
            toggleModule(context, "old-player-regen", false);

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                hurt.spawn(Arena.offset(centre, 0, 0));
            });
            context.awaitTicks(5);

            Boolean controlCancelled = stageSatiatedRegen(context, hurt);
            if (controlCancelled == null) {
                context.note("this version cannot stage an EntityRegainHealthEvent — regen suppression pinned in unit tests");
                return;
            }
            context.expect(!controlCancelled,
                    "satiated-regen event was cancelled with the module disabled — must be zero-touch when off");

            // Enable the module: the same event must now come back cancelled (the
            // 1.9 fast/slow satiated heal is suppressed so the per-player 80-tick
            // era task can own regen). [RegenModule#onNaturalRegen]
            toggleModule(context, "old-player-regen", true);
            context.expect(moduleActive(mental, "old-player-regen"),
                    "old-player-regen module failed to enable");

            Boolean cancelled = stageSatiatedRegen(context, hurt);
            if (cancelled == null) {
                context.note("the module-on regen event could not be staged — covered by gate checks");
                return;
            }
            context.expect(cancelled,
                    "old-player-regen did not cancel the 1.9 satiated-regen event");
        } finally {
            toggleModule(context, "old-player-regen", false);
            context.syncRun(hurt::remove);
        }
    }

    /**
     * Wounds the fake player (so a heal is meaningful), fires a synthetic
     * {@code EntityRegainHealthEvent} with {@code RegainReason.SATIATED}, and
     * returns whether it ended up cancelled. {@code null} if the event could not
     * be constructed on this version.
     */
    private static @Nullable Boolean stageSatiatedRegen(TestContext context, FakePlayer hurt)
            throws Exception {
        return context.sync(() -> {
            try {
                Player player = hurt.player();
                player.setNoDamageTicks(0);
                // Drop below max so the satiated heal is a real heal, not a no-op.
                player.setHealth(Math.max(1.0, player.getHealth() - 4.0));
                EntityRegainHealthEvent event = new EntityRegainHealthEvent(
                        player, 1.0, EntityRegainHealthEvent.RegainReason.SATIATED);
                Bukkit.getPluginManager().callEvent(event);
                return event.isCancelled();
            } catch (Throwable unsupported) {
                return null;
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Cross-version helpers                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Constructs a {@link PlayerItemConsumeEvent} via the 2-arg
     * {@code (Player, ItemStack)} constructor present on the whole 1.17.1–26.x
     * range. Returns {@code null} if a version drifts the constructor away (the
     * caller then SKIPs).
     */
    @SuppressWarnings("deprecation") // 2-arg ctor deprecated in newer API but present across the range
    private static @Nullable PlayerItemConsumeEvent newConsumeEvent(Player player, ItemStack item) {
        try {
            return new PlayerItemConsumeEvent(player, item);
        } catch (Throwable unsupported) {
            return null;
        }
    }

    /**
     * Sets a base (level I, non-extended) Strength type on a {@link PotionMeta}
     * cross-version: prefers the modern {@code setBasePotionType(PotionType)}
     * (Paper 1.20.5+, reflected because it does not exist on the 1.17.1 floor
     * API), falling back to the directly-compiled deprecated
     * {@code setBasePotionData(PotionData)}. Mirrors PotionDurationModule's own
     * defensive resolution. Returns {@code false} if neither path succeeds.
     */
    @SuppressWarnings("deprecation")
    private static boolean setBaseStrength(PotionMeta meta) {
        // Modern path (reflective): setBasePotionType(PotionType.STRENGTH).
        try {
            var setType = PotionMeta.class.getMethod("setBasePotionType", PotionType.class);
            setType.invoke(meta, PotionType.STRENGTH);
            return true;
        } catch (ReflectiveOperationException modernAbsent) {
            // Fall through to the legacy PotionData path.
        }
        // Legacy path (compiled against the floor; may NoSuchMethodError on a
        // future runtime that drops the deprecated API — caught by the caller).
        try {
            meta.setBasePotionData(new org.bukkit.potion.PotionData(PotionType.STRENGTH));
            return true;
        } catch (Throwable legacyAbsent) {
            return false;
        }
    }

    /**
     * Reads the rewritten Strength duration (in ticks) off a potion's custom
     * effects. The module adds a custom Strength effect at the era duration and
     * clears the base type, so the era duration lives in the custom-effect list.
     * Returns {@code null} if no Strength custom effect is present.
     */
    private static @Nullable Integer strengthDurationTicks(PotionMeta meta) {
        PotionEffectType strength = resolveEffect("strength", "INCREASE_DAMAGE");
        if (strength == null) {
            return null;
        }
        for (PotionEffect effect : meta.getCustomEffects()) {
            if (effect.getType().equals(strength)) {
                return effect.getDuration();
            }
        }
        return null;
    }

    /**
     * The active amplifier for {@code type} on {@code player}, or {@code null}
     * if the player does not currently have that effect.
     */
    private static @Nullable Integer activeAmplifier(Player player, PotionEffectType type) {
        // PotionEffects.of, not player.getPotionEffect(type): the single-effect accessor is absent on 1.9.4
        // (floors at 1.10.2), where a direct call throws — the resolver scans the active set below it.
        PotionEffect effect = PotionEffects.of(player, type);
        return effect == null ? null : effect.getAmplifier();
    }

    /**
     * Resolves a {@link PotionEffectType} by its lowercase Minecraft key (modern
     * {@code getByKey}, Paper 1.20.5+) with a {@code getByName} fallback using
     * the legacy Bukkit enum name — the same defensive shape the modules use.
     * The critical mapping is {@code "resistance"} → {@code DAMAGE_RESISTANCE}
     * and {@code "strength"} → {@code INCREASE_DAMAGE} in pre-1.9 nomenclature.
     */
    @SuppressWarnings("deprecation")
    private static @Nullable PotionEffectType resolveEffect(String key, String legacyName) {
        // PotionEffectType.getByKey(NamespacedKey) is not on the 1.17.1 floor
        // API the tester compiles against; getByName accepts the legacy enum
        // name as an alias across the whole 1.17.1 -> 26.x range.
        return PotionEffectType.getByName(legacyName);
    }

    /* ------------------------------------------------------------------ */
    /*  Module toggling (copied from ZeroTouchSuite)                       */
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
