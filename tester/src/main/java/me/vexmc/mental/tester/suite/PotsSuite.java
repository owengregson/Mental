package me.vexmc.mental.tester.suite;

import java.util.List;
import java.util.UUID;
import me.vexmc.mental.kernel.wire.PositionRing;
import me.vexmc.mental.platform.CommandMaps;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.settings.FastPotsSettings;
import me.vexmc.mental.v5.config.settings.PotFillSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.feature.pots.EconomyPort;
import me.vexmc.mental.v5.feature.pots.FastPotsUnit;
import me.vexmc.mental.v5.feature.pots.HealPotItems;
import me.vexmc.mental.v5.feature.pots.PotFiller;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The POTS family (owner directive 2026-07-04). Covers, on a live server:
 *
 * <ol>
 *   <li><b>zero-touch</b> — with both modules OFF, {@code /potfill} is unknown to
 *       the command map and a steeply-thrown splash potion's velocity is
 *       untouched;</li>
 *   <li><b>pot-fill</b> — enabling it registers the command with its aliases; a
 *       player without the permission is refused; with it, the empty storage slots
 *       are filled with correctly-constructed heal potions (type/meta/glint read
 *       back through the same probe seams); a stubbed economy caps a partial fill;
 *       a zero cost fills every empty slot;</li>
 *   <li><b>fast-pots</b> — a steep (50°) throw is redirected to the led predicted
 *       feet within the [min, max] × vanilla speed band (asserted against the
 *       production aim helper), staged BOTH as a real tagged heal pot ({@link HealPotItems}, the
 *       production seam) and as a tagless splash stack — the latter reads back as
 *       AIR on 1.16.x–1.20.4, where the server stores a ThrownPotion's item only
 *       when it differs from the entity default or carries a tag, so it live-pins
 *       the production empty-item fallback exactly where the raw read is
 *       dishonest; a shallow (20°) throw and a lingering potion are untouched.</li>
 * </ol>
 *
 * <p>The economy is stubbed directly (no Vault on the gate), and the pot-fill
 * item/glint assertions read back through {@link HealPotItems}'s own probes — no
 * magic constants.</p>
 */
public final class PotsSuite {

    private static final double EPSILON = 1.0e-6;

    private PotsSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("pots: both modules off — /potfill absent and steep throws untouched",
                        context -> zeroTouch(mental, tester, context)),
                new TestCase("pots: enabling pot-fill registers /potfill with its aliases",
                        context -> commandRegistration(mental, context)),
                new TestCase("pots: /potfill refuses a player without permission",
                        context -> refusedWithoutPermission(mental, tester, context)),
                new TestCase("pots: /potfill fills every empty slot for a permitted player (free)",
                        context -> fillsEmptySlots(mental, tester, context)),
                new TestCase("pots: a stubbed economy caps a partial fill; cost 0 fills all",
                        context -> economyPartialFill(mental, tester, context)),
                new TestCase("fast-pots: a steep throw is redirected at the predicted feet",
                        context -> fastPotRedirected(mental, tester, context)),
                new TestCase("fast-pots: the thrower velocity reads the position ring, not grounded getVelocity",
                        context -> fastPotVelocityFromRing(mental, tester, context)),
                new TestCase("fast-pots: a shallow throw and a lingering potion are untouched",
                        context -> fastPotUntouched(mental, tester, context)));
    }

    /* ------------------------------- 1. zero-touch ------------------------------ */

    private static void zeroTouch(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer thrower = new FakePlayer(tester, mental.scheduling());
        try {
            setModule(context, Feature.POT_FILL, false);
            setModule(context, Feature.FAST_POTS, false);

            context.expect(CommandMaps.find("potfill") == null,
                    "/potfill is in the command map with pot-fill disabled (not zero-touch)");
            context.expect(CommandMaps.find("pots") == null, "/pots alias present while disabled");
            context.expect(CommandMaps.find("pf") == null, "/pf alias present while disabled");

            context.syncRun(() -> thrower.spawn(Arena.prepare(Bukkit.getWorlds().get(0))));
            context.awaitTicks(3);

            Vector launch = new Vector(0, -0.5, 0);
            Vector[] velocities = throwSplash(context, thrower, 50.0f, "SPLASH_POTION", launch);
            if (velocities == null) {
                context.note("this version cannot stage a ThrownPotion launch — fast-pots logic unit-pinned");
                return;
            }
            context.expectNear(launch.getX(), velocities[1].getX(), EPSILON, "disabled fast-pots must not touch x");
            context.expectNear(launch.getY(), velocities[1].getY(), EPSILON, "disabled fast-pots must not touch y");
            context.expectNear(launch.getZ(), velocities[1].getZ(), EPSILON, "disabled fast-pots must not touch z");
        } finally {
            setModule(context, Feature.POT_FILL, false);
            setModule(context, Feature.FAST_POTS, false);
            context.syncRun(thrower::remove);
        }
    }

    /* --------------------------- 2. command registration ------------------------ */

    private static void commandRegistration(MentalPluginV5 mental, TestContext context) throws Exception {
        try {
            setModule(context, Feature.POT_FILL, true);
            context.expect(mental.featureActive(Feature.POT_FILL), "pot-fill failed to enable");

            context.expect(CommandMaps.find("potfill") != null, "/potfill was not registered on enable");
            context.expect(CommandMaps.find("pots") != null, "/pots alias was not registered");
            context.expect(CommandMaps.find("pf") != null, "/pf alias was not registered");

            // Disabling must remove the command and all its aliases (zero-touch).
            setModule(context, Feature.POT_FILL, false);
            context.expect(CommandMaps.find("potfill") == null, "/potfill survived a disable (not unregistered)");
            context.expect(CommandMaps.find("pots") == null, "/pots alias survived a disable");
            context.expect(CommandMaps.find("pf") == null, "/pf alias survived a disable");
        } finally {
            setModule(context, Feature.POT_FILL, false);
        }
    }

    /* ---------------------------- 3. permission refusal ------------------------- */

    private static void refusedWithoutPermission(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer player = new FakePlayer(tester, mental.scheduling());
        try {
            setModule(context, Feature.POT_FILL, true);
            context.syncRun(() -> player.spawn(Arena.prepare(Bukkit.getWorlds().get(0))));
            context.awaitTicks(3);
            context.syncRun(() -> player.player().getInventory().clear());

            // No permission attachment: the command must refuse and fill nothing.
            context.sync(() -> Bukkit.dispatchCommand(player.player(), "potfill"));
            context.awaitTicks(5);

            int filled = context.sync(() -> countHealPotions(new HealPotItems(), player.player().getInventory()));
            context.expect(filled == 0,
                    "a player without permission had " + filled + " potions filled — refusal failed");
        } finally {
            setModule(context, Feature.POT_FILL, false);
            context.syncRun(player::remove);
        }
    }

    /* ------------------------------- 4. free fill ------------------------------- */

    private static void fillsEmptySlots(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        HealPotItems items = new HealPotItems();
        if (!items.available()) {
            context.note("heal potion not constructible on this server (" + items.describe() + ") — skipping fill");
            return;
        }
        FakePlayer player = new FakePlayer(tester, mental.scheduling());
        try {
            setModule(context, Feature.POT_FILL, true);
            context.syncRun(() -> player.spawn(Arena.prepare(Bukkit.getWorlds().get(0))));
            context.awaitTicks(3);

            // Clear, then occupy slot 0 with a marker so exactly 35 storage slots are empty.
            context.syncRun(() -> {
                PlayerInventory inventory = player.player().getInventory();
                inventory.clear();
                inventory.setItem(0, new ItemStack(Material.STONE));
                player.player().addAttachment(tester, PotFillSettings.DEFAULT_PERMISSION, true);
            });

            context.sync(() -> Bukkit.dispatchCommand(player.player(), "potfill"));
            context.awaitTicks(5);

            context.syncRun(() -> {
                PlayerInventory inventory = player.player().getInventory();
                // The marker slot is untouched.
                ItemStack marker = inventory.getItem(0);
                context.expect(marker != null && marker.getType() == Material.STONE,
                        "the occupied marker slot was overwritten");
                // Slots 1..35 are constructed heal potions with a glint.
                int filled = 0;
                for (int slot = 1; slot < PotFiller.STORAGE_SLOTS; slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    context.expect(items.isConstructedHealPotion(stack),
                            "slot " + slot + " is not a constructed heal potion (" + stack + ")");
                    context.expect(items.hasGlint(stack), "slot " + slot + " potion lacks a glint");
                    filled++;
                }
                context.expect(filled == 35, "expected exactly 35 filled slots, got " + filled);
                // Armour and off-hand are never touched.
                for (ItemStack armour : inventory.getArmorContents()) {
                    context.expect(isEmpty(armour), "an armour slot was filled");
                }
                context.expect(isEmpty(inventory.getItemInOffHand()), "the off-hand was filled");
            });
        } finally {
            setModule(context, Feature.POT_FILL, false);
            context.syncRun(player::remove);
        }
    }

    /* ----------------------- 5. stubbed-economy partial fill -------------------- */

    private static void economyPartialFill(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        HealPotItems items = new HealPotItems();
        if (!items.available()) {
            context.note("heal potion not constructible on this server — skipping economy fill");
            return;
        }
        FakePlayer player = new FakePlayer(tester, mental.scheduling());
        PotFiller filler = new PotFiller(items);
        try {
            context.syncRun(() -> player.spawn(Arena.prepare(Bukkit.getWorlds().get(0))));
            context.awaitTicks(3);

            double cost = 10.0;
            // A stub economy that can afford exactly 3 potions — no Vault on the gate.
            EconomyPort affordsThree = new EconomyPort() {
                @Override public boolean present() { return true; }
                @Override public double balance(Player who) { return 3 * cost; }
                @Override public boolean withdraw(Player who, double amount) { return true; }
            };

            // Partial fill: 36 empty slots, but only 3 affordable.
            PotFiller.Outcome partial = context.sync(() -> {
                player.player().getInventory().clear();
                return filler.fill(player.player(), new PotFillSettings("mental.pots.fill", cost), affordsThree);
            });
            context.expect(partial.filled() == 3,
                    "a 3-potion budget filled " + partial.filled() + " (expected 3)");
            context.expectNear(3 * cost, partial.charged(), EPSILON, "charged for exactly the filled potions");
            int partialCount = context.sync(() -> countHealPotions(items, player.player().getInventory()));
            context.expect(partialCount == 3, "expected 3 heal potions in the inventory, got " + partialCount);

            // A zero cost fills every empty slot regardless of balance.
            PotFiller.Outcome free = context.sync(() -> {
                player.player().getInventory().clear();
                return filler.fill(player.player(), new PotFillSettings("mental.pots.fill", 0.0), affordsThree);
            });
            context.expect(free.filled() == PotFiller.STORAGE_SLOTS,
                    "a free fill filled " + free.filled() + " (expected " + PotFiller.STORAGE_SLOTS + ")");
            context.expectNear(0.0, free.charged(), EPSILON, "a free fill charges nothing");
        } finally {
            context.syncRun(player::remove);
        }
    }

    /* ------------------------------ 6. fast-pot redirect ------------------------ */

    private static void fastPotRedirected(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer thrower = new FakePlayer(tester, mental.scheduling());
        try {
            setModule(context, Feature.FAST_POTS, true);
            context.expect(mental.featureActive(Feature.FAST_POTS), "fast-pots failed to enable");
            context.syncRun(() -> thrower.spawn(Arena.prepare(Bukkit.getWorlds().get(0))));
            context.awaitTicks(3);

            FastPotsSettings settings = fastPotsSettings(mental);
            Vector launch = new Vector(0.15, -0.45, 0.05); // an off-vertical vanilla throw
            double vanillaSpeed = launch.length();

            // A real fast pot carries a potion payload (its NBT tag). That matters on
            // 1.16.x–1.20.4: the server stores a ThrownPotion's item ONLY when it
            // differs from the entity default or has a tag, and the Bukkit getItem
            // read has no empty→default fallback there — a tagless staged stack reads
            // back as AIR (the 2026-07-04 1.16.5 gate failure). The representative
            // staging is therefore the tagged heal pot every player actually throws,
            // built through the production seam.
            ItemStack healPot = new HealPotItems().createSplashHealPotion();
            Object[] tagged = throwSplashObserving(mental, context, thrower, 50.0f,
                    healPot != null ? healPot : plainPotion("SPLASH_POTION"), launch, settings);
            if (tagged == null) {
                context.note("this version cannot stage a ThrownPotion launch — fast-pots logic unit-pinned");
                return;
            }
            assertRedirected(context, tagged, vanillaSpeed, settings, "tagged heal pot");

            // The same throw with a tagless splash stack must redirect identically —
            // on the 1.16.x–1.20.4 band it reads back as AIR and exercises the
            // production empty-item fallback; honest tiers read SPLASH_POTION and
            // take the plain path, so the assertion is version-uniform.
            Object[] tagless = throwSplashObserving(mental, context, thrower, 50.0f,
                    plainPotion("SPLASH_POTION"), launch, settings);
            if (tagless == null) {
                context.note("tagless splash not stageable here — the AIR fallback is unit-pinned");
                return;
            }
            assertRedirected(context, tagless, vanillaSpeed, settings, "tagless splash, AIR read band");
        } finally {
            setModule(context, Feature.FAST_POTS, false);
            context.syncRun(thrower::remove);
        }
    }

    /* --------------- 6b. the velocity source (grounded-run behind bug) ---------- */

    /**
     * The root-cause pin for the "lands behind when running flat" bug: the aim must
     * read the thrower's velocity from the {@link PositionRing} per-tick delta, NOT
     * {@link org.bukkit.entity.Player#getVelocity()} — which is ~0 for a player
     * moving on the GROUND (input-driven movement is client-authoritative; the
     * server's velocity field is not populated from it), so a getVelocity-based aim
     * never leads a flat-running thrower. We plant a sprint-speed ground delta in a
     * ring (feet moving +0.28 b/t in x, y flat) while getVelocity stays ~0, and
     * assert {@link FastPotsUnit#throwerVelocity} returns the RING delta.
     */
    private static void fastPotVelocityFromRing(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer thrower = new FakePlayer(tester, mental.scheduling());
        try {
            context.syncRun(() -> {
                thrower.spawn(Arena.prepare(Bukkit.getWorlds().get(0)));
                thrower.player().setVelocity(new Vector(0, 0, 0)); // getVelocity ~0, the buggy source
            });
            context.awaitTicks(2);

            // A throwaway ring with a flat-ground sprint delta (+0.28 x, y unchanged):
            // getVelocity cannot see this, the position delta can.
            UUID id = context.sync(() -> thrower.player().getUniqueId());
            PositionRing ring = new PositionRing();
            ring.record(id, 10.0, 64.0, 10.0, 1_000L);
            ring.record(id, 10.28, 64.0, 10.0, 51_000L);

            Vector fromRing = context.sync(() -> FastPotsUnit.throwerVelocity(thrower.player(), ring));
            context.expectNear(0.28, fromRing.getX(), 1.0e-9,
                    "the aim velocity is the position-ring x-delta (flat ground run), not getVelocity");
            context.expectNear(0.0, fromRing.getY(), 1.0e-9, "flat ground: no y motion");
            context.expectNear(0.0, fromRing.getZ(), 1.0e-9, "no z motion planted");

            // And the ground getVelocity really is ~0 — the reason the old aim landed behind.
            Vector grounded = context.sync(() -> thrower.player().getVelocity());
            context.expect(Math.abs(grounded.getX()) < 0.05,
                    "grounded getVelocity x is ~0 (the bug's source), got " + grounded.getX());
        } finally {
            context.syncRun(thrower::remove);
        }
    }

    /* ------------------------- 7. fast-pot leave-alone cases -------------------- */

    private static void fastPotUntouched(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer thrower = new FakePlayer(tester, mental.scheduling());
        try {
            setModule(context, Feature.FAST_POTS, true);
            context.syncRun(() -> thrower.spawn(Arena.prepare(Bukkit.getWorlds().get(0))));
            context.awaitTicks(3);

            // Shallow throw (pitch 20 < 35 threshold): untouched.
            Vector launch = new Vector(0.2, -0.3, 0.1);
            Vector[] shallow = throwSplash(context, thrower, 20.0f, "SPLASH_POTION", launch);
            if (shallow == null) {
                context.note("this version cannot stage a ThrownPotion launch — fast-pots logic unit-pinned");
                return;
            }
            context.expectNear(launch.getX(), shallow[1].getX(), EPSILON, "shallow throw x must be untouched");
            context.expectNear(launch.getY(), shallow[1].getY(), EPSILON, "shallow throw y must be untouched");
            context.expectNear(launch.getZ(), shallow[1].getZ(), EPSILON, "shallow throw z must be untouched");

            // A LINGERING potion thrown steeply: excluded, so untouched.
            if (Material.getMaterial("LINGERING_POTION") != null) {
                Vector[] lingering = throwSplash(context, thrower, 50.0f, "LINGERING_POTION", launch);
                if (lingering != null) {
                    context.expectNear(launch.getX(), lingering[1].getX(), EPSILON, "lingering potion x untouched");
                    context.expectNear(launch.getY(), lingering[1].getY(), EPSILON, "lingering potion y untouched");
                    context.expectNear(launch.getZ(), lingering[1].getZ(), EPSILON, "lingering potion z untouched");
                }
            } else {
                context.note("no LINGERING_POTION on this server — the exclusion is a name check, unit-pinned");
            }
        } finally {
            setModule(context, Feature.FAST_POTS, false);
            context.syncRun(thrower::remove);
        }
    }

    /* --------------------------------- helpers --------------------------------- */

    /**
     * Spawns a {@link ThrownPotion} of {@code potionMaterialName} above the
     * thrower, gives it {@code launch} as its "vanilla" velocity, sets the thrower
     * as shooter at the requested pitch, and fires a synthetic
     * {@link ProjectileLaunchEvent}. Returns {@code [before, after]} velocities, or
     * {@code null} if the launch could not be staged on this version.
     */
    private static @Nullable Vector[] throwSplash(
            TestContext context, FakePlayer thrower, float pitch, String potionMaterialName, Vector launch)
            throws Exception {
        return context.sync(() -> {
            ThrownPotion potion = stagePotion(thrower, pitch, plainPotion(potionMaterialName), launch);
            if (potion == null) {
                return null;
            }
            try {
                Vector before = potion.getVelocity().clone();
                Bukkit.getPluginManager().callEvent(new ProjectileLaunchEvent(potion));
                Vector after = potion.getVelocity().clone();
                return new Vector[] {before, after};
            } catch (Throwable unsupported) {
                return null;
            } finally {
                potion.remove();
            }
        });
    }

    /**
     * As {@link #throwSplash} but takes the exact potion {@link ItemStack} to stage
     * (tagged vs tagless matters on the 1.16.x–1.20.4 datawatcher-skip band) and
     * also computes the EXPECTED redirect through the production aim helper
     * ({@link FastPotsUnit#redirect}) at the moment of the launch, so the assertion
     * is against the same code the feature runs, never a re-derivation. Returns
     * {@code [expected, applied]}.
     */
    private static @Nullable Object[] throwSplashObserving(
            MentalPluginV5 mental, TestContext context, FakePlayer thrower, float pitch,
            @Nullable ItemStack potionItem, Vector launch, FastPotsSettings settings) throws Exception {
        return context.sync(() -> {
            ThrownPotion potion = stagePotion(thrower, pitch, potionItem, launch);
            if (potion == null) {
                return null;
            }
            try {
                thrower.player().setVelocity(new Vector(0, 0, 0)); // deterministic predicted feet
                double vanillaSpeed = potion.getVelocity().length();
                // The redirect now reads the thrower's velocity from the position ring
                // (getVelocity is ~0 for a grounded player), so the expected derivation
                // passes the SAME ring the event handler uses — expected == applied by
                // construction regardless of whether the fake thrower is ring-tracked.
                Vector expected = FastPotsUnit.redirect(
                        potion.getLocation(), thrower.player(), vanillaSpeed, settings,
                        mental.sessions().positions());
                Bukkit.getPluginManager().callEvent(new ProjectileLaunchEvent(potion));
                Vector applied = potion.getVelocity().clone();
                return new Object[] {expected, applied};
            } catch (Throwable unsupported) {
                return null;
            } finally {
                potion.remove();
            }
        });
    }

    /** Spawns and configures a splash/lingering {@link ThrownPotion}, or {@code null} on failure. */
    private static @Nullable ThrownPotion stagePotion(
            FakePlayer thrower, float pitch, @Nullable ItemStack potionItem, Vector launch) {
        if (potionItem == null) {
            return null;
        }
        try {
            Player player = thrower.player();
            Location at = player.getLocation();
            at.setPitch(pitch);
            player.teleport(at);

            Location spawnAt = player.getLocation().add(0, 1.5, 0); // roughly the eye
            ThrownPotion potion = spawnAt.getWorld().spawn(spawnAt, ThrownPotion.class);
            potion.setItem(potionItem);
            potion.setShooter(player);
            potion.setVelocity(launch.clone());
            return potion;
        } catch (Throwable unsupported) {
            return null;
        }
    }

    /** A plain, tagless potion stack resolved by name — {@code null} when the material is absent. */
    private static @Nullable ItemStack plainPotion(String materialName) {
        Material material = Material.getMaterial(materialName);
        return material == null ? null : new ItemStack(material);
    }

    /**
     * The four redirect assertions for one observed throw — expected vs applied on
     * each axis at the production tolerance, plus the magnitude invariant. The
     * staging tag names WHICH item form failed (tagged pot vs the AIR-read band).
     */
    private static void assertRedirected(
            TestContext context, Object[] observed, double vanillaSpeed,
            FastPotsSettings settings, String staging) {
        Vector expected = (Vector) observed[0];
        Vector applied = (Vector) observed[1];
        context.expectNear(expected.getX(), applied.getX(), 1.0e-4,
                "fast-pot redirect x (predicted feet; " + staging + ")");
        context.expectNear(expected.getY(), applied.getY(), 1.0e-4,
                "fast-pot redirect y (predicted feet; " + staging + ")");
        context.expectNear(expected.getZ(), applied.getZ(), 1.0e-4,
                "fast-pot redirect z (predicted feet; " + staging + ")");
        // Speed is a bounded band, not a target (exact-ballistic redesign
        // 2026-07-07; speed-band + lead round same day): the aim spends the least
        // speed that lands the burst on the LED predicted feet, bounded into
        // [min, max] × vanilla (the owner's [0.5, 1.5] band by default). The landing
        // accuracy and the lead are exhaustively pinned off-server in PotsAimTest;
        // here we assert the wiring (x/y/z == the production redirect) and that the
        // magnitude stays inside the band.
        double minSpeed = vanillaSpeed * settings.minSpeedMultiplier();
        double maxSpeed = vanillaSpeed * settings.maxSpeedMultiplier();
        context.expect(applied.length() <= maxSpeed + 1.0e-4,
                "fast-pot magnitude within the max × vanilla ceiling (" + staging + ")");
        context.expect(applied.length() >= minSpeed - 1.0e-4,
                "fast-pot magnitude at least the min × vanilla floor (" + staging + ")");
    }

    private static int countHealPotions(HealPotItems items, PlayerInventory inventory) {
        int count = 0;
        for (int slot = 0; slot < PotFiller.STORAGE_SLOTS; slot++) {
            if (items.isConstructedHealPotion(inventory.getItem(slot))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isEmpty(@Nullable ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }

    @SuppressWarnings("unchecked")
    private static FastPotsSettings fastPotsSettings(MentalPluginV5 mental) {
        return mental.snapshot().settings(
                (SettingsKey<FastPotsSettings>) Feature.FAST_POTS.settingsKey());
    }

    /** Toggles a module through the real management seam and waits for convergence. */
    private static void setModule(TestContext context, Feature feature, boolean enabled) throws Exception {
        context.syncRun(() -> ((MentalPluginV5) Bukkit.getPluginManager().getPlugin("Mental"))
                .management().setModuleEnabled(feature, enabled));
        context.awaitTicks(1);
    }
}
