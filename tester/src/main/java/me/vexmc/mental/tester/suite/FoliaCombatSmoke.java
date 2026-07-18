package me.vexmc.mental.tester.suite;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.vexmc.mental.api.ComboView;
import me.vexmc.mental.api.Mental;
import me.vexmc.mental.api.MentalCombat;
import me.vexmc.mental.api.event.ComboChainEvent;
import me.vexmc.mental.api.event.ComboEndEvent;
import me.vexmc.mental.api.event.ComboStartEvent;
import me.vexmc.mental.kernel.delivery.DeliveryDesk;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * The Folia combat smoke — the FIRST live combat coverage Mental has ever had
 * on Folia (historically the tester ran the boot suite only there, because the
 * Paper-shaped suites drive cross-region state from one global context, which
 * Folia forbids). Discovery (Phase 5 Task 5.6) proved a same-region pair IS
 * drivable when every action is scheduled on its owning region thread:
 *
 * <ul>
 *   <li><b>Spawn</b> on the target location's OWNING REGION thread
 *       ({@link Scheduling#runAt}) — Folia's {@code PlayerList.placeNewPlayer}
 *       reads {@code ServerLevel.getCurrentWorldData()}, the region-thread-local
 *       world data (null off a region tick → NPE on the global thread).</li>
 *   <li><b>Attack</b> on the attacker's region thread ({@link Scheduling#runOn})
 *       — the tester's suite-delivery seam ({@code FakePlayer.attack}).</li>
 *   <li><b>Journal read + teardown</b> on the victim's / player's region thread
 *       — an off-region entity removal trips Folia's main-thread check.</li>
 * </ul>
 *
 * <p>The two-realm engine itself needed <em>no</em> Folia change: the session
 * ticks on the entity scheduler, the {@code KnockbackUnit} runs on the victim's
 * region thread inside the damage pass, the {@code DeskRouter} resolves the
 * velocity event on that same thread, and the desk journal records the
 * byte-exact era vector. The single tester-side accommodation is
 * {@code FakePlayer}'s Folia-only {@code teleportAsync} at spawn (Paper keeps the
 * byte-identical sync teleport).</p>
 *
 * <p>The assertion is a <b>journal-recorded desk delivery</b>: the victim's
 * {@link DeliveryDesk} journal — the single "what did we actually ship" seam —
 * must carry the canonical standing vector the kernel {@link KnockbackEngine}
 * computes (same authority the Paper {@code KnockbackSuite} uses), and with the
 * knockback module disabled it must record nothing (zero-touch).</p>
 *
 * <p><b>Cross-region melee</b> (the intended melee drop) is not live-drivable via
 * a fake player: {@code FakePlayer.attack} routes through NMS
 * {@code Player.attack(victim)}, which reads the victim's live state and so
 * itself throws Folia's {@code ensureTickThread} before any Mental code runs when
 * the two are in different regions. Real melee therefore always shares a region;
 * the {@code KnockbackUnit}'s {@code isOwnedByCurrentRegion(attacker)} guard is a
 * defensive belt for boundary straddles / dispatch-tick pearls. This suite pins
 * that the same-region ownership check is consulted and returns true.</p>
 */
public final class FoliaCombatSmoke {

    private static final double EPSILON = 1.0e-3;
    /** Poll budget for the desk delivery to land under concurrent-matrix load. */
    private static final int JOURNAL_POLL_ROUNDS = 20;

    private FoliaCombatSmoke() {}

    public static List<TestCase> tests(MentalPluginV5 mental, MentalTesterPlugin tester) {
        return List.of(
                new TestCase("folia: same-region hit records the canonical standing vector on the victim desk",
                        ctx -> sameRegionHit(mental, tester, ctx)),
                new TestCase("folia: disabled knockback module records no desk decision (zero-touch)",
                        ctx -> zeroTouch(mental, tester, ctx)),
                new TestCase("folia: combo events on the region thread + off-thread query (§11.7)",
                        ctx -> comboRegionEvents(mental, tester, ctx)));
    }

    /* ------------------------------------------------------------------ */

    private static void sameRegionHit(MentalPluginV5 mental, MentalTesterPlugin tester, TestContext ctx)
            throws Exception {
        Scheduling sched = mental.scheduling();
        World world = Bukkit.getWorlds().get(0);
        FakePlayer attacker = new FakePlayer(tester, sched);
        FakePlayer victim = new FakePlayer(tester, sched);
        Location base = buildFloor(sched, world);

        try {
            spawnPair(sched, attacker, victim, base);
            ctx.awaitTicks(6);

            // Both sessions exist (join fired on the region thread) and have
            // ticked at least once (repeatOn entity scheduler published a view).
            ctx.expect(mental.sessions().sessionFor(attacker.uuid()) != null
                    && mental.sessions().sessionFor(victim.uuid()) != null,
                    "no combat session created for a fake player on Folia");
            ctx.expect(mental.sessions().viewOf(victim.uuid()) != null,
                    "the victim session never ticked on its region thread (no published view)");

            // Same-region proof + the canonical standing vector the desk must ship.
            boolean sameRegion = callOnBlocking(sched, attacker.player(),
                    () -> sched.isOwnedByCurrentRegion(victim.player()));
            ctx.expect(sameRegion, "the fake pair are not in one region — cannot drive a same-region hit");

            runOnBlocking(sched, victim.player(), () -> victim.player().setNoDamageTicks(0));
            KnockbackVector expected = callOnBlocking(sched, attacker.player(), () -> {
                EntityState attackerState = EntityStates.capture(attacker.player(), false);
                EntityState victimState = KnockbackSuite.restingVictim(victim);
                KnockbackProfile profile = mental.snapshot().profileFor(victim.player().getWorld().getName());
                KnockbackVector vector = KnockbackEngine.compute(attackerState, victimState, profile, null);
                return SuiteDelivery.melee(vector, profile, victimState.grounded());
            });
            ctx.expect(expected != null, "engine returned no vector for an unresisted standing hit");

            // Drive the hit on the ATTACKER's region thread (the suite-delivery seam).
            runOnBlocking(sched, attacker.player(), () -> attacker.attack(victim.player()));

            CombatSession session = mental.sessions().sessionFor(victim.uuid());
            ctx.expect(session != null, "victim session vanished before the desk read");
            JournalEntry shipped = awaitJournalEntry(ctx, sched, victim.player(), session);
            ctx.expect(shipped != null,
                    "no desk delivery recorded on the victim journal after a same-region hit");
            ctx.expect(shipped.shipped() != null,
                    "the desk journalled a suppression, not a delivery (reason "
                            + shipped.suppressReason() + ")");

            ctx.expectNear(expected.x(), shipped.shipped().x(), EPSILON, "journal shipped x");
            ctx.expectNear(expected.y(), shipped.shipped().y(), EPSILON, "journal shipped y");
            ctx.expectNear(expected.z(), shipped.shipped().z(), EPSILON, "journal shipped z");
            // The era standing-hit signature (the Paper KnockbackSuite pins the
            // sprint 0.4608; this is its non-sprint sibling — 0.3608 vertical, the
            // 0.4 base horizontal along the knock bearing).
            ctx.expectNear(0.3608, shipped.shipped().y(), 5.0e-3, "standing hit vertical (era wire value)");
            ctx.expectNear(0.4, shipped.shipped().z(), 5.0e-3, "standing hit horizontal (era base)");
        } finally {
            removePair(sched, attacker, victim);
        }
    }

    private static void zeroTouch(MentalPluginV5 mental, MentalTesterPlugin tester, TestContext ctx)
            throws Exception {
        Scheduling sched = mental.scheduling();
        World world = Bukkit.getWorlds().get(0);
        FakePlayer attacker = new FakePlayer(tester, sched);
        FakePlayer victim = new FakePlayer(tester, sched);
        Location base = buildFloor(sched, world);
        Feature kb = Feature.byModuleId("knockback").orElseThrow();

        try {
            ctx.syncRun(() -> mental.management().setModuleEnabled(kb, false));
            ctx.awaitTicks(2);
            ctx.expect(!mental.featureActive(kb), "knockback module failed to disable");

            spawnPair(sched, attacker, victim, base);
            ctx.awaitTicks(6);

            CombatSession session = mental.sessions().sessionFor(victim.uuid());
            ctx.expect(session != null, "no victim session for the zero-touch read");
            int before = callOnBlocking(sched, victim.player(), () -> session.desk().journal().size());

            runOnBlocking(sched, victim.player(), () -> victim.player().setNoDamageTicks(0));
            runOnBlocking(sched, attacker.player(), () -> attacker.attack(victim.player()));
            ctx.awaitTicks(8);

            int after = callOnBlocking(sched, victim.player(), () -> session.desk().journal().size());
            ctx.expect(after == before,
                    "disabled knockback module still recorded a desk decision (journal " + before
                            + " -> " + after + ") — zero-touch violated on Folia");
        } finally {
            ctx.syncRun(() -> mental.management().setModuleEnabled(kb, true));
            removePair(sched, attacker, victim);
        }
    }

    /**
     * §11.7: the first live Folia coverage of the gen-3 combo surface. Combo events
     * must fire on the victim's OWNING region thread (not the global thread), and the
     * {@code comboOn} query must answer an untorn ACTIVE view from OFF a region thread
     * (the test driver). Every entity interaction stays on its owning region thread
     * per the smoke's conventions; only the combo query is deliberately off-thread —
     * that any-thread contract is exactly what §6 promises.
     */
    private static void comboRegionEvents(MentalPluginV5 mental, MentalTesterPlugin tester, TestContext ctx)
            throws Exception {
        Scheduling sched = mental.scheduling();
        World world = Bukkit.getWorlds().get(0);
        FakePlayer attacker = new FakePlayer(tester, sched);
        FakePlayer victim = new FakePlayer(tester, sched);
        Location base = buildFloor(sched, world);
        Feature comboHold = Feature.byModuleId("combo-hold").orElseThrow();
        RegionCaptor captor = null;

        try {
            // Enable combo detection through the real overlay path; widen the windows
            // so a stationary clientless victim never grounds-/gaps-out before we read.
            ctx.syncRun(() -> {
                mental.overlaySet("modules.combo-hold", true);
                mental.overlaySet("combo-hold.grounded-run-ticks", 400);
                mental.overlaySet("combo-hold.max-gap-ticks", 400);
                mental.reloadAll();
            });
            ctx.awaitTicks(2);
            ctx.expect(mental.featureActive(comboHold), "combo-hold failed to enable on Folia");

            spawnPair(sched, attacker, victim, base);
            ctx.awaitTicks(6);
            ctx.expect(mental.sessions().sessionFor(victim.uuid()) != null,
                    "no victim combat session created on Folia");

            captor = new RegionCaptor(sched, victim.uuid(), victim.player());
            RegionCaptor registered = captor;
            ctx.syncRun(() -> Bukkit.getPluginManager().registerEvents(registered, tester));

            // Two same-region hits: hit 1 opens (ComboChainEvent), hit 2 promotes (ComboStartEvent).
            for (int hit = 0; hit < 2; hit++) {
                runOnBlocking(sched, victim.player(), () -> victim.player().setNoDamageTicks(0));
                runOnBlocking(sched, attacker.player(), () -> attacker.attack(victim.player()));
                ctx.awaitTicks(8);
            }
            RegionCaptor observed = captor;
            ctx.awaitUntil(() -> observed.chainSeen.get() && observed.startSeen.get(), 40,
                    "both the ComboChainEvent and ComboStartEvent to fire");
            ctx.expect(observed.chainOnRegion.get(),
                    "the ComboChainEvent must fire on the victim's owning region thread");
            ctx.expect(observed.startOnRegion.get(),
                    "the ComboStartEvent must fire on the victim's owning region thread");

            // Off-thread (the test driver, NOT a region thread) untorn ACTIVE read: the
            // four facts must be internally consistent as one snapshot.
            ComboView view = Mental.get().combat().comboOn(victim.uuid());
            ctx.expect(view.state() == ComboView.State.ACTIVE
                            && view.attackerId() != null
                            && view.hits() >= 2
                            && view.lastKnockTick() != MentalCombat.NO_TICK
                            && view.gapDeadlineTick() != MentalCombat.NO_TICK,
                    "an off-thread comboOn must return a non-torn ACTIVE view (state=" + view.state()
                            + ", attacker=" + view.attackerId() + ", hits=" + view.hits()
                            + ", lastKnock=" + view.lastKnockTick() + ", deadline=" + view.gapDeadlineTick() + ")");
            ctx.expect(attacker.uuid().equals(view.attackerId()), "the ACTIVE view must name the attacker");

            // Toggle off and await the balanced DISABLED terminal — leave zero-touch behind.
            ctx.syncRun(() -> {
                mental.overlaySet("modules.combo-hold", false);
                mental.reloadAll();
            });
            RegionCaptor endObserved = captor;
            ctx.awaitUntil(() -> endObserved.ends.stream()
                            .anyMatch(e -> e.getReason() == ComboEndEvent.Reason.DISABLED), 40,
                    "the DISABLED ComboEndEvent terminal on module toggle-off");
        } finally {
            if (captor != null) {
                RegionCaptor teardownCaptor = captor;
                ctx.syncRun(() -> HandlerList.unregisterAll(teardownCaptor));
            }
            ctx.syncRun(() -> {
                mental.overlaySet("modules.combo-hold", false);
                mental.overlaySet("combo-hold.grounded-run-ticks", 10);
                mental.overlaySet("combo-hold.max-gap-ticks", 20);
                mental.reloadAll();
            });
            removePair(sched, attacker, victim);
        }
    }

    /**
     * Records whether each combo event fired on the victim's owning region thread.
     * The handlers run on that region thread, so {@code isOwnedByCurrentRegion} reads
     * true there; the flags let the off-thread driver assert it after the fact.
     */
    private static final class RegionCaptor implements Listener {
        private final Scheduling sched;
        private final UUID victimId;
        private final Player victim;
        final AtomicBoolean chainSeen = new AtomicBoolean();
        final AtomicBoolean startSeen = new AtomicBoolean();
        final AtomicBoolean chainOnRegion = new AtomicBoolean();
        final AtomicBoolean startOnRegion = new AtomicBoolean();
        final List<ComboEndEvent> ends = new CopyOnWriteArrayList<>();

        RegionCaptor(Scheduling sched, UUID victimId, Player victim) {
            this.sched = sched;
            this.victimId = victimId;
            this.victim = victim;
        }

        @EventHandler
        public void onChain(ComboChainEvent event) {
            if (victimId.equals(event.getVictim().getUniqueId())) {
                chainOnRegion.set(sched.isOwnedByCurrentRegion(victim));
                chainSeen.set(true);
            }
        }

        @EventHandler
        public void onStart(ComboStartEvent event) {
            if (victimId.equals(event.getVictim().getUniqueId())) {
                startOnRegion.set(sched.isOwnedByCurrentRegion(victim));
                startSeen.set(true);
            }
        }

        @EventHandler
        public void onEnd(ComboEndEvent event) {
            if (victimId.equals(event.getVictim().getUniqueId())) {
                ends.add(event);
            }
        }
    }

    /* ------------------------------------------------------------------ */

    /** Builds a small stone platform on the location's owning region (Folia-correct). */
    private static Location buildFloor(Scheduling sched, World world) throws Exception {
        Location spawn = world.getSpawnLocation();
        Location base = new Location(world, spawn.getBlockX() + 0.5, 100.0, spawn.getBlockZ() + 0.5);
        CompletableFuture<Void> done = new CompletableFuture<>();
        sched.runAt(base, () -> {
            try {
                for (int x = -3; x <= 3; x++) {
                    for (int z = -3; z <= 3; z++) {
                        world.getBlockAt(base.getBlockX() + x, 99, base.getBlockZ() + z)
                                .setType(Material.STONE, false);
                        for (int y = 100; y <= 103; y++) {
                            world.getBlockAt(base.getBlockX() + x, y, base.getBlockZ() + z)
                                    .setType(Material.AIR, false);
                        }
                    }
                }
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(30, TimeUnit.SECONDS);
        return base;
    }

    /** Spawns both fakes one block apart along z, on the base region thread. */
    private static void spawnPair(Scheduling sched, FakePlayer attacker, FakePlayer victim, Location base)
            throws Exception {
        runAtBlocking(sched, base, () -> {
            attacker.spawn(base.clone().add(0, 0, -1.0));
            victim.spawn(base.clone().add(0, 0, 1.0));
        });
    }

    /** Removes both fakes on their owning region threads (an off-region remove throws). */
    private static void removePair(Scheduling sched, FakePlayer attacker, FakePlayer victim) {
        removeOnRegion(sched, attacker);
        removeOnRegion(sched, victim);
    }

    private static void removeOnRegion(Scheduling sched, FakePlayer fake) {
        try {
            Player player = Bukkit.getPlayer(fake.uuid());
            if (player == null) {
                fake.remove();
                return;
            }
            runOnBlocking(sched, player, fake::remove);
        } catch (Throwable ignored) {
            // Teardown is best-effort; the server shutdown reclaims anything left.
        }
    }

    /** Polls (region-thread reads only) for the desk to journal a delivery. */
    private static JournalEntry awaitJournalEntry(
            TestContext ctx, Scheduling sched, Player victim, CombatSession session) throws Exception {
        for (int round = 0; round < JOURNAL_POLL_ROUNDS; round++) {
            JournalEntry last = callOnBlocking(sched, victim, () -> {
                DeliveryDesk desk = session.desk();
                List<JournalEntry> journal = desk.journal();
                return journal.isEmpty() ? null : journal.get(journal.size() - 1);
            });
            if (last != null) {
                return last;
            }
            ctx.awaitTicks(2);
        }
        return null;
    }

    /* ------------------------------------------------------------------ */

    private static void runAtBlocking(Scheduling sched, Location location, ThrowingRunnable task)
            throws Exception {
        CompletableFuture<Void> future = new CompletableFuture<>();
        sched.runAt(location, () -> {
            try {
                task.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        future.get(30, TimeUnit.SECONDS);
    }

    private static void runOnBlocking(Scheduling sched, Player entity, ThrowingRunnable task) throws Exception {
        callOnBlocking(sched, entity, () -> {
            task.run();
            return null;
        });
    }

    private static <T> T callOnBlocking(Scheduling sched, Player entity, ThrowingSupplier<T> task)
            throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        sched.runOn(entity, () -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, () -> future.completeExceptionally(
                new IllegalStateException("entity retired before its region task ran")));
        return future.get(30, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
