package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import me.vexmc.mental.kernel.ledger.MotionLedger;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * End-to-end knockback delivery: the velocity a victim's velocity event
 * carries must equal the engine's vector pushed through the profile's wire
 * delivery (legacy-1.7 default: the full stamp — the measured 1.7.10 wire),
 * with combo residuals served by the live per-session ledger.
 *
 * <p>v5: the expectation math is the kernel {@link KnockbackEngine}/{@link Decay}
 * authority and the victim capture is the production {@link EntityStates}
 * capture, so the suite and the shipped values share one source; combo residuals
 * are read from the session's own {@link MotionLedger} (tick-based).</p>
 */
public final class KnockbackSuite {

    private static final double EPSILON = 1.0e-3;

    /** How far below the victim's feet to probe for the supporting block (just inside it for a rester). */
    private static final double GROUND_PROBE_DEPTH = 0.05;
    /** Settle threshold for |velY| — generous enough to admit the −0.0784 grounded equilibrium. */
    private static final double SETTLE_VELOCITY_EPSILON = 0.1;

    private static final Logger LOG = Logger.getLogger(KnockbackSuite.class.getName());

    private KnockbackSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("knockback: plain hit matches engine + tracker delivery", context ->
                        runScenario(mental, tester, context, false)),
                new TestCase("knockback: sprint hit matches engine + tracker delivery", context ->
                        runScenario(mental, tester, context, true)),
                new TestCase("knockback: second hit stacks the ledger residual (1.7.10 combos)", context ->
                        runComboScenario(mental, tester, context)),
                new TestCase("knockback: a packetless attacker's sprint hit never poisons its ledger feed",
                        context -> runDomainPoisonScenario(mental, tester, context)),
                new TestCase("knockback: a packetless victim's server-side melee always journals a SHIP",
                        context -> runPacketlessMeleeJournalScenario(mental, tester, context)),
                new TestCase("knockback: an airborne leak-class hit on a practice preset never ships a downward knock",
                        context -> runAirborneFloorScenario(mental, tester, context, "kohi")),
                new TestCase("knockback: an airborne leak-class hit on a legacy preset never ships a downward knock",
                        context -> runAirborneFloorScenario(mental, tester, context, "legacy-1.8")));
    }

    /**
     * The region-path no-velocity-event net (F1 regression pin). The vanilla-melee
     * (REGISTERED) delivery path relies wholly on the victim's {@code
     * PlayerVelocityEvent} resolving the desk decision. A packetless victim (every
     * tester {@link FakePlayer}) can have that event published LATE — on 1.20.6 the
     * knock routes through {@code Player.attack}'s synchronous restore block, which
     * resets {@code hurtMarked} and can land the velocity event a tick behind the
     * desk's two-tick sweep — and the era knock is then dropped as {@code
     * no-velocity-event} with NO journal SHIP. This is the exact shape of the F1
     * failure ("the server-sprinting hit journalled no SHIP", 1.20.6 only, the sole
     * tested build in the [1.20.5, 1.21) band). The
     * {@code SessionService.ensureStrandedPacketlessMelee} net delivers the stranded
     * knock a tick before the sweep would drop it, so the invariant here is
     * unconditional and version-blind: a packetless victim's server-side melee hit
     * ALWAYS journals a SHIP. Where the velocity event is prompt the net never fires
     * (the desk decision is already resolved) and the SHIP comes from it instead —
     * the assertion holds either way.
     */
    private static void runPacketlessMeleeJournalScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5); // land and settle; spawn invulnerability clears at spawn
            int shipsBefore = context.sync(() -> countShips(mental, victim));
            context.syncRun(() -> {
                attacker.player().setSprinting(true);
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player()); // server-side NMS melee — the region path
            });
            JournalEntry ship = awaitNewShip(context, mental, victim, shipsBefore);
            context.expect(ship != null && ship.shipped() != null,
                    "a packetless victim's server-side melee hit journalled no SHIP — the region-path"
                            + " no-velocity-event net regressed (F1)");
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
        }
    }

    /**
     * The 2.4.7 downward-knock pin (the owner's "2nd airborne combo hit knocks
     * DOWN" bug). The ADD vertical (vy × friction.y + base.vertical) goes
     * negative once the victim's LEDGER vy falls past −(base + extra)/friction.y
     * — kohi sprint: −0.87 — which a free-falling airborne ledger reaches
     * ~15–20 ticks after an airborne hit. velt/signature were immune
     * (friction.y 0.1); every practice preset leaked. The fix floors the five
     * practice presets' final vertical at 0.0 (limits.vertical-min), and this
     * stages exactly the leak class live and asserts the SHIP through the
     * journal — the "what did we actually ship" seam: y ≥ 0, exactly the
     * floored 0.0, and exactly the engine expectation (formula parity survives
     * the floor; the floored value is also tick-slack-immune, since one more
     * decay tick only deepens the pre-floor negative).
     *
     * <p>Staging: a clientless fake never moves from a knock and never lands its
     * ledger through the rim, so the ledger is driven airborne PHYSICALLY — the
     * per-tick upward motion (the ComboSuite float pattern) keeps the honest
     * combat-ground feed reading airborne, hit 1 records its stamp on the
     * airborne ledger branch, and the ledger then free-falls
     * (vy ← (vy − 0.08) × 0.98) into the leak zone while the victim stays
     * airborne. Latency compensation is disabled for the case so the region
     * path carries no vy override and the in-suite engine expectation (null
     * override) is exact.</p>
     */
    private static void runAirborneFloorScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context, String preset)
            throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            context.syncRun(() -> {
                mental.overlaySet("modules.latency-compensation", false);
                mental.reloadAll();
                context.expect(mental.management().setGlobalProfile(preset), preset + " preset missing");
            });
            context.awaitTicks(3);
            context.expect(context.sync(() ->
                            profileFor(mental, victim).limits().verticalMin() == 0.0),
                    preset + " must carry the vertical floor (vertical-min 0.0)");

            // Float the victim so the honest feed reads airborne every tick and
            // hit 1 records on the airborne ledger branch (the ComboSuite pattern).
            context.syncRun(() -> victim.preTick(() -> victim.setMotion(0.0, 0.42, 0.0)));
            context.awaitTicks(4);

            // Hit 1: an airborne sprint hit — the ledger now carries the shipped
            // stamp and free-falls tick by tick.
            context.syncRun(() -> {
                attacker.player().setSprinting(true);
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });

            // Wait for the free-fall to cross the leak threshold with margin:
            // kohi sprint ships negative below vy −0.87 (−1.0 gives a pre-floor
            // y of −1.0 × 0.5 + 0.35 + 0.085 = −0.065 — unambiguously
            // leak-class); legacy-1.8 sprint sits exactly at its −1.0 threshold
            // and every further decay tick goes negative — either way the SHIP
            // must be the floored 0.0 (2.4.8 extended the floor to legacy).
            CombatSession session = mental.sessions().sessionFor(victim.uuid());
            context.expect(session != null, "no combat session for the victim");
            context.awaitUntil(() -> {
                try {
                    return context.sync(() ->
                            EntityStates.captureVictim(victim.player(), session.ledger()).vy() <= -1.0);
                } catch (Exception failure) {
                    return false;
                }
            }, 80, "the airborne ledger to free-fall into the leak zone (vy <= -1.0)");

            // Hit 2 — the reported downward hit. Same-tick engine expectation
            // (null override: latency compensation is off for this case).
            KnockbackVector[] expected = new KnockbackVector[1];
            double[] ledgerVy = new double[1];
            int shipsBefore = context.sync(() -> {
                EntityState attackerState = EntityStates.capture(attacker.player());
                EntityState victimState = EntityStates.captureVictim(victim.player(), session.ledger());
                ledgerVy[0] = victimState.vy();
                KnockbackProfile profile = profileFor(mental, victim);
                expected[0] = SuiteDelivery.melee(
                        KnockbackEngine.compute(attackerState, victimState, profile, null),
                        profile, victimState.grounded());
                victim.player().setNoDamageTicks(0);
                int before = countShips(mental, victim);
                attacker.attack(victim.player());
                return before;
            });
            context.expect(ledgerVy[0] <= -1.0,
                    "staging failed — the ledger vy must be leak-class (got " + ledgerVy[0] + ")");

            JournalEntry ship = awaitNewShip(context, mental, victim, shipsBefore);
            context.expect(ship != null && ship.shipped() != null,
                    "the airborne leak-class hit journaled no SHIP");
            context.expect(ship.shipped().y() >= 0.0,
                    "a practice-preset knock must NEVER point down (shipped y "
                            + ship.shipped().y() + " off ledger vy " + ledgerVy[0] + ")");
            context.expectNear(0.0, ship.shipped().y(), 1.0e-9,
                    "the leak-class hit must ship exactly the floored 0.0 vertical");
            context.expect(expected[0] != null, "engine returned no vector for the staged hit");
            context.expectNear(expected[0].y(), ship.shipped().y(), 1.0e-9,
                    "the journaled SHIP must equal the engine expectation (formula parity)");
        } finally {
            context.syncRun(() -> {
                victim.preTick(null);
                mental.overlaySet("modules.latency-compensation", true);
                mental.reloadAll();
                mental.management().setGlobalProfile("legacy-1.7");
                attacker.remove();
                victim.remove();
            });
        }
    }

    /** SHIP entries (a delivered vector) currently in the victim's desk journal. */
    private static int countShips(MentalPluginV5 mental, FakePlayer victim) {
        CombatSession session = mental.sessions().sessionFor(victim.uuid());
        if (session == null) {
            return 0;
        }
        int ships = 0;
        for (JournalEntry entry : session.desk().journal()) {
            if (entry.shipped() != null) {
                ships++;
            }
        }
        return ships;
    }

    /** Polls the victim's desk journal for a NEW SHIP beyond {@code shipsBefore} (well past the 2-tick sweep). */
    private static JournalEntry awaitNewShip(
            TestContext context, MentalPluginV5 mental, FakePlayer victim, int shipsBefore) throws Exception {
        for (int round = 0; round < 12; round++) {
            JournalEntry ship = context.sync(() -> {
                CombatSession session = mental.sessions().sessionFor(victim.uuid());
                if (session == null) {
                    return null;
                }
                int ships = 0;
                JournalEntry lastShip = null;
                for (JournalEntry entry : session.desk().journal()) {
                    if (entry.shipped() != null) {
                        ships++;
                        lastShip = entry;
                    }
                }
                return ships > shipsBefore ? lastShip : null;
            });
            if (ship != null) {
                return ship;
            }
            context.awaitTicks(2);
        }
        return null;
    }

    /**
     * The 2.4.4 domain-poisoning regression pin. Every tester {@link FakePlayer} is
     * exactly the pathological case: a real Bukkit player whose inbound packets never
     * reach the parse rim (no PacketEvents user), so it must NEVER own a
     * {@link me.vexmc.mental.v5.rim.ConnectionDomains} entry. Before the fix, landing
     * an accepted SPRINT hit as attacker created one as a side effect of the post-hit
     * sprint-clear obligation ({@code KnockbackUnit.applyAttackerObligations →
     * domainFor}); that spurious domain then permanently stood the session ground
     * sampler down, the player's {@code MotionLedger} never landed again, and its melee
     * verticals free-fell to ~0 — the field report's "boxers take zero vertical
     * knockback".
     *
     * <p>The discriminating guard is {@code !domains.has(attacker)} after the sprint
     * hit: it fails hard under the old code (domain created) and passes under the fix
     * (the {@code domains.has()} guard skips the packetless clear). The behavioural
     * belt then confirms the attacker's ledger feed is intact — becoming a victim, it
     * still receives a healthy era vertical, never the collapsed ~0. The FULL free-fall
     * collapse needs a client-brained bot that physically flies (the SimpleBoxer vlab
     * repro: a healthy standing opener rises 1.485 blocks on legacy-1.7); a clientless
     * fake cannot be flown, so the matrix pins the mechanism, not the flight.</p>
     */
    private static void runDomainPoisonScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            // Land and settle — spawn invulnerability is cleared at spawn.
            context.awaitTicks(5);

            // A packetless fake never reaches the parse rim: no domain before it acts.
            context.expect(!mental.domains().has(attacker.uuid()),
                    "a packetless attacker already owns a connection domain before attacking");

            // Land one accepted SPRINT hit as attacker — the old poison trigger.
            context.syncRun(() -> {
                attacker.player().setSprinting(true);
                victim.player().setNoDamageTicks(0);
                captors.reset();
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the sprint hit's velocity event");
            context.awaitTicks(3); // let the deferred sprint-clear obligation run

            // THE PIN: the sprint-clear obligation must not have created a domain for
            // the packetless attacker (the fix guards domainFor with domains.has()).
            context.expect(!mental.domains().has(attacker.uuid()),
                    "landing a sprint hit created a connection domain for a packetless attacker"
                            + " — its ground sampler is now silenced and its ledger will free-fall"
                            + " to a zero vertical (2.4.4 domain-poisoning regression)");

            // Belt: the attacker's own ledger feed is intact — taking a fresh sprint
            // hit still ships a healthy era vertical, not the collapsed ~0.
            context.syncRun(() -> {
                victim.player().setSprinting(true);
                attacker.player().setNoDamageTicks(0);
                captors.reset();
                victim.attack(attacker.player());
            });
            context.awaitUntil(() -> captors.velocityOf(attacker.uuid()) != null, 40,
                    "the retaliation hit's velocity event");
            Vector applied = captors.velocityOf(attacker.uuid());
            context.expect(applied != null,
                    "no velocity event for the formerly-poisoned attacker as victim");
            context.expect(applied.getY() > 0.3,
                    "formerly-poisoned attacker took a collapsed vertical (" + applied.getY()
                            + ") — a healthy era sprint knock is ~0.4608");
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    private static void runScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context, boolean sprinting)
            throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            // Land and settle — spawn invulnerability is cleared at spawn.
            context.awaitTicks(5);

            KnockbackVector expected = context.sync(() -> {
                attacker.player().setSprinting(sprinting);
                victim.player().setNoDamageTicks(0);
                EntityState attackerState = EntityStates.capture(attacker.player());
                // A fresh grounded victim reads the gravity equilibrium —
                // the era servers ticked player physics, so motY parked at
                // −0.0784, never zero (measured: standing hits are 0.3608-
                // based, sprint 0.4608, before the wire decay).
                EntityState victimState = restingVictim(victim);
                KnockbackProfile profile = profileFor(mental, victim);
                KnockbackVector vector = KnockbackEngine.compute(
                        attackerState, victimState, profile, null);
                // Spawn placement can emit its own velocity event; clear it
                // so the next captured event is the knock's, not stale state.
                captors.reset();
                attacker.attack(victim.player());
                return SuiteDelivery.melee(vector, profile, victimState.grounded());
            });
            context.expect(expected != null, "engine returned no vector for an unresisted hit");

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the knock's velocity event");

            Vector applied = captors.velocityOf(victim.uuid());
            context.expect(applied != null,
                    "no PlayerVelocityEvent observed for the victim — knockback never applied"
                            + " (damage event: " + captors.damageOf(victim.uuid()) + ")");
            context.expectNear(expected.x(), applied.getX(), EPSILON, "knockback x");
            context.expectNear(expected.y(), applied.getY(), EPSILON, "knockback y");
            context.expectNear(expected.z(), applied.getZ(), EPSILON, "knockback z");
            if (sprinting) {
                // the full-stamp era wire for a standing sprint hit — what
                // 1.8.9 always shipped and 1.7.10 shipped to victims who
                // joined before their attacker (measured 0.4607 on both)
                context.expectNear(0.4608, applied.getY(), 5.0e-3,
                        "sprint hit vertical (era wire value)");
            }
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /**
     * The 1.7.10 combo: the second hit computes from the live per-session ledger
     * — which, after the first knock, holds the delivered residual decaying at
     * ground friction, so the second base compounds it. The expectation reads the
     * same ledger the knockback unit consumes, through the production victim
     * capture; the unit's owning-thread read runs synchronously inside
     * {@code attack()} on the same tick, so a single candidate matches (a
     * one-tick-later decay candidate absorbs any scheduling slack).
     */
    private static void runComboScenario(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        int gapTicks = 4;

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            // Land and settle — spawn invulnerability is cleared at spawn.
            context.awaitTicks(5);

            KnockbackProfile profile = context.sync(() -> profileFor(mental, victim));

            KnockbackVector first = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                EntityState victimState = restingVictim(victim);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityStates.capture(attacker.player()), victimState, profile, null);
                captors.reset();
                attacker.attack(victim.player());
                return SuiteDelivery.melee(vector, profile, victimState.grounded());
            });
            context.expect(first != null, "engine returned no vector for the first hit");

            context.awaitTicks(gapTicks);
            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the first hit's velocity event");

            Vector appliedFirst = captors.velocityOf(victim.uuid());
            context.expect(appliedFirst != null, "first hit produced no velocity event");
            context.expectNear(first.y(), appliedFirst.getY(), EPSILON, "first hit y");
            captors.reset();

            CombatSession session = mental.sessions().sessionFor(victim.uuid());
            context.expect(session != null, "no combat session for the victim");
            MotionLedger ledger = session.ledger();
            List<KnockbackVector> candidates = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                EntityState attackerState = EntityStates.capture(attacker.player());
                // The production victim capture over the session ledger — exactly
                // what the knockback unit reads on the same tick. It already
                // carries the first knock decaying at ground friction.
                EntityState victimState = EntityStates.captureVictim(victim.player(), ledger);
                List<KnockbackVector> expected = new ArrayList<>();
                expected.add(SuiteDelivery.melee(
                        KnockbackEngine.compute(attackerState, victimState, profile, null),
                        profile, victimState.grounded()));
                // One-tick-later alignment: the unit's read can land a session
                // tick after this one under scheduling slack.
                Decay.Motion later = Decay.decayOnce(
                        victimState.vx(), victimState.vy(), victimState.vz(),
                        victimState.grounded(), Decay.DEFAULT_GRAVITY);
                EntityState laterState = new EntityState(
                        victimState.x(), victimState.y(), victimState.z(), victimState.yaw(),
                        later.vx(), later.vy(), later.vz(),
                        victimState.grounded(), victimState.sprinting(),
                        victimState.knockbackEnchantLevel(), victimState.knockbackResistance());
                expected.add(SuiteDelivery.melee(
                        KnockbackEngine.compute(attackerState, laterState, profile, null),
                        profile, laterState.grounded()));
                attacker.attack(victim.player());
                return expected;
            });

            context.awaitUntil(() -> captors.velocityOf(victim.uuid()) != null, 40,
                    "the second hit's velocity event");
            Vector appliedSecond = captors.velocityOf(victim.uuid());
            context.expect(appliedSecond != null, "second hit produced no velocity event");

            double bestDelta = Double.MAX_VALUE;
            for (KnockbackVector candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                double delta = Math.max(
                        Math.abs(candidate.x() - appliedSecond.getX()),
                        Math.max(Math.abs(candidate.y() - appliedSecond.getY()),
                                Math.abs(candidate.z() - appliedSecond.getZ())));
                bestDelta = Math.min(bestDelta, delta);
            }
            context.expect(bestDelta < EPSILON,
                    "second hit did not match any ledger candidate (best delta " + bestDelta
                            + ", applied " + appliedSecond + ")");
            // The residual margin after the ground-friction decay is small when
            // the victim reads grounded at both hits (legacy versions keep
            // onGround true while rising) — strictly-greater proves the
            // compounding; the candidate match above pins the exact value.
            context.expect(appliedSecond.getZ() > appliedFirst.getZ() + 1.0e-3,
                    "second hit must stack past the first (z " + appliedFirst.getZ()
                            + " -> " + appliedSecond.getZ() + ")");
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** The knockback profile governing the victim's world (the v5 snapshot resolution). */
    static KnockbackProfile profileFor(MentalPluginV5 mental, FakePlayer victim) {
        return mental.snapshot().profileFor(victim.player().getWorld().getName());
    }

    /** The victim's engine input with a fresh ledger: grounded equilibrium motion. */
    static EntityState restingVictim(FakePlayer victim) {
        EntityState live = EntityStates.capture(victim.player());
        // Key the grounded-vs-airborne selector on physical position truth, NOT the Bukkit isOnGround()
        // flag: a clientless fake victim reads isOnGround()=false on the 1.9/1.10 NMS after settling even
        // while resting on the floor, which wrongly chose the airborne 0.0 baseline (→ expected 0.4) while
        // Mental delivered the grounded −0.0784 equilibrium (→ 0.3608). The physical check is an INDEPENDENT
        // source (not Mental's view, not the flaky flag); on every version where the flag was already
        // correct it agrees with it, so no pinned VALUE changes — only the selector becomes reliable.
        boolean grounded = physicallyGrounded(victim, live.grounded());
        double vy = grounded
                ? Decay.groundedEquilibrium(Decay.DEFAULT_GRAVITY)
                : 0.0;
        return new EntityState(
                live.x(), live.y(), live.z(), live.yaw(), 0.0, vy, 0.0,
                grounded, live.sprinting(),
                live.knockbackEnchantLevel(), live.knockbackResistance());
    }

    /**
     * Position-derived physical ground truth for a settled victim: a solid block sits directly beneath its
     * feet (what it is standing on) and its vertical velocity has settled. Reads only the victim's own
     * position and the block under it — the same region-thread read {@code SessionService} does in
     * production — so it is floor-plane-independent (the Paper arenas rest at y≈151, the Folia smoke at
     * y=100) and never trusts the Bukkit {@code isOnGround()} flag, which a clientless fake reads as false
     * after settling on the 1.9/1.10 NMS. The flag is kept as a logged diagnostic (only when it disagrees)
     * so its behaviour on each server version stays visible in the suite log.
     */
    static boolean physicallyGrounded(FakePlayer victim, boolean isOnGroundFlag) {
        Location feet = victim.player().getLocation();
        Material support = feet.clone().subtract(0.0, GROUND_PROBE_DEPTH, 0.0).getBlock().getType();
        double velY = victim.player().getVelocity().getY();
        boolean grounded = support.isSolid() && Math.abs(velY) < SETTLE_VELOCITY_EPSILON;
        if (grounded != isOnGroundFlag) {
            LOG.info("[ground-diagnostic] victim " + victim.uuid() + " footY=" + feet.getY()
                    + " support=" + support + " velY=" + velY
                    + " -> physical grounded=" + grounded
                    + ", but Bukkit isOnGround()=" + isOnGroundFlag + " (using physical truth)");
        }
        return grounded;
    }
}
