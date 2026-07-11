package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.feedback.FeedbackTrace;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The 2.5.5 feedback-coherence pins (the plan's §Verification item 2): the two
 * behaviours the diagnosis turns on that {@link FeedbackSuite}'s single-hit cases
 * cannot reach —
 *
 * <ul>
 *   <li><b>Immunity coherence (D1/F1/F3).</b> A no-{@code noDamageTicks}-clear
 *       barrage proves the cosmetics fire EXACTLY per accepted hit — never on a
 *       window-rejected swing (which fires no EDBEE at all), never twice — and
 *       that {@code Σ displayed damage == health actually lost} per matrix version,
 *       across whatever delta semantics the server's lineage carries for an
 *       in-window UPGRADE hit. This is the whole point of the round: every other
 *       staging site clears {@code noDamageTicks}; these deliberately do NOT.</li>
 *   <li><b>Healing indicators (F4).</b> A {@code setHealth} heal (StarEnchants-
 *       shaped) is detected, attributed to the last attacker, folded/paced, and
 *       journaled as {@code HEAL_UNSENDABLE} for a clientless (PE-user-less)
 *       attacker; an unattributed heal is silent; the module off writes nothing.</li>
 * </ul>
 *
 * <p><b>Why a suite-local listener.</b> {@link me.vexmc.mental.tester.Captors} is
 * last-write-wins (a map), so it cannot COUNT events — and counting accepted
 * EDBEEs is the coherence oracle. {@link CoherenceProbe} records every EDBEE the
 * victim takes (final damage + pre-hit health at MONITOR) AND cancels the victim's
 * {@link EntityRegainHealthEvent}s, which freezes natural regen deterministically
 * for the fold arithmetic and keeps ambient regen from inventing phantom heal
 * indicators — while a staged {@code setHealth} heal, which bypasses that event,
 * still lands and is still sampled.</p>
 *
 * <p><b>The clientless ceiling.</b> Fake attackers carry no PacketEvents user, so
 * the sendable {@code HEAL}/{@code NORMAL} decisions (and the hearts they would
 * render) are unreachable here — the attribution seam these cases pin is exactly
 * {@code HEAL_UNSENDABLE}/{@code UNSENDABLE}, which the plan designates as the
 * matrix-assertable one. The healed hearts and the fold's summed amount are
 * unit-pinned (HealFoldTest); this suite pins that the RIGHT number of decisions
 * fire, attributed to the right pair, and that displayed damage tracks health lost.</p>
 */
public final class FeedbackCoherenceSuite {

    /** The pinned per-hit damage (armAttacker base 7.0, attack-speed 40 for a full meter). */
    private static final double HIT_DAMAGE = 7.0;

    /** The upgrade-hit attribute (3b): amount 10.0 > the 7.0 lastHurt ⇒ a delta hit. */
    private static final double UPGRADE_DAMAGE = 10.0;

    /** Fold-oracle tolerance: Σ min(finalDamage, preHealth) == health lost, exact but for float slack. */
    private static final double FOLD_EPSILON = 1.0e-6;

    private FeedbackCoherenceSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("coherence: cosmetics fire exactly per accepted hit through the immunity window",
                        context -> runImmunityCoherence(mental, tester, context)),
                new TestCase("coherence: an in-window UPGRADE hit ships a delta event and stays fold-accurate",
                        context -> runUpgradeDelta(mental, tester, context)),
                new TestCase("heal-indicator: a setHealth heal attributes to the last attacker (HEAL_UNSENDABLE)",
                        context -> runHealAttribution(mental, tester, context)),
                new TestCase("heal-indicator: a combined heal folds into exactly one paced decision",
                        context -> runHealFoldPacing(mental, tester, context)),
                new TestCase("heal-indicator: sub-heart heals hold silently until the sum crosses one heart",
                        context -> runSubHeartHoldsThenShips(mental, tester, context)),
                new TestCase("heal-indicator: an unattributed heal is silent (no ambient-regen spam)",
                        context -> runUnattributedHealSilent(mental, tester, context)),
                new TestCase("heal-indicator ZERO-TOUCH: module off, a stamp+heal writes no trace",
                        context -> runHealZeroTouch(mental, tester, context)));
    }

    /* ------------------------------------------------------------------ */
    /*  3a. immunity coherence: cosmetics == accepted EDBEEs, fold-exact    */
    /* ------------------------------------------------------------------ */

    /**
     * A 12-swing barrage at a 2-tick cadence WITHOUT ever clearing
     * {@code noDamageTicks}, so vanilla's invulnerability window rejects most of
     * the swings before any Bukkit event is built (D1). The pins:
     * <ol>
     *   <li>the count of hit-feedback emit decisions == the count of accepted
     *       EDBEEs (with {@code finalDamage > 0}) — cosmetics fire once per
     *       accepted hit, never for a rejected swing;</li>
     *   <li>the count of damage-indicators {@code UNSENDABLE} decisions ==
     *       the same accepted count (the clientless-attacker indicator branch);</li>
     *   <li>the accepted count sits in the immunity band. Vanilla admits roughly
     *       one hit per {@code maxNoDamageTicks/2 ≈ 10} server ticks, but the
     *       observed count over this fixed-cadence barrage varies across the tier:
     *       a clientless victim's invulnerability counter is decremented by the
     *       harness tick driver AND — on the LEGACY tier — additionally by the
     *       server's own player tick, so the effective window is ~halved there
     *       (≈3 accepts modern / single-ticked, ≈5 legacy / double-ticked). The
     *       band [2,8] brackets that while still failing decisively for a disabled
     *       window (≈12 accepts, cosmetics on every swing) or gross over-rejection
     *       (a single accept);</li>
     *   <li>{@code Σ min(finalDamage_i, preHealth_i) == startHealth − endHealth}
     *       (F3): displayed damage is exactly the health lost, telescoping across
     *       every accepted hit — the live per-version proof of the overkill clamp
     *       and the delta semantics.</li>
     * </ol>
     * A wide 100-max window keeps every hit non-killing (up to nine accepts × 7.0 =
     * 63 &lt; 100) and above the 35% low-HP ceiling (35.0), so the decisions read
     * uniformly EMITTED/NO_VIEWERS and the fold never crosses a death boundary.
     */
    private static void runImmunityCoherence(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        CoherenceProbe probe = CoherenceProbe.register(tester, () -> mental.clock().current().value());
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            setEffectsPreset(context, "signature");
            toggleModule(context, "hit-feedback", true);
            toggleModule(context, "damage-indicators", true);
            context.expect(moduleActive(mental, "hit-feedback"), "hit-feedback failed to enable");
            context.expect(moduleActive(mental, "damage-indicators"), "damage-indicators failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            context.expect(context.sync(() -> armAttacker(attacker)),
                    "attack-damage/attack-speed attributes unresolved — cannot stage the barrage");

            probe.watch(victim.uuid());
            boolean staged = context.sync(() -> {
                if (!setMaxHealth(victim, 100.0)) {
                    return false;
                }
                victim.player().setHealth(100.0);
                return true;
            });
            if (!staged) {
                context.skip("max-health attribute absent — cannot stage a non-killing barrage window");
                return;
            }

            trace.clear();
            probe.reset();
            double startHealth = context.sync(() -> victim.player().getHealth());

            // The barrage: 12 swings, ~2-tick cadence, and — the whole point —
            // NO setNoDamageTicks(0). The first swing lands fresh (nd=0), the rest
            // race the decrementing invulnerability window exactly as a real 10 CPS
            // trade does.
            for (int swing = 0; swing < 12; swing++) {
                context.syncRun(() -> attacker.attack(victim.player()));
                context.awaitTicks(2);
            }
            context.awaitTicks(4); // let the last accepted hit's subtraction settle
            double endHealth = context.sync(() -> victim.player().getHealth());

            List<CoherenceProbe.Hit> accepted = probe.accepted();
            int acceptedCount = accepted.size();
            context.note("immunity barrage: " + acceptedCount + " of 12 swings accepted; span "
                    + probe.spanTicks() + " ticks; startHealth=" + startHealth + " endHealth=" + endHealth);

            long emitDecisions = countHitFeedbackEmits(trace);
            long unsendable = countDecision(trace, "damage-indicators", "UNSENDABLE");

            context.expect(emitDecisions == acceptedCount,
                    "hit-feedback must decide exactly once per ACCEPTED hit — got " + emitDecisions
                            + " emit decisions for " + acceptedCount + " accepted EDBEEs (trace="
                            + trace.entries() + ")");
            context.expect(unsendable == acceptedCount,
                    "damage-indicators must decide UNSENDABLE exactly once per ACCEPTED hit — got "
                            + unsendable + " for " + acceptedCount + " accepted EDBEEs (trace="
                            + trace.entries() + ")");
            context.expect(acceptedCount >= 2 && acceptedCount <= 8,
                    "12 swings over ~22 ticks must land in the immunity band [2,8] (≈3 accepts on the "
                            + "single-ticked modern tier, ≈5 on the double-ticked legacy tier); "
                            + acceptedCount + " means a " + (acceptedCount > 8
                            ? "disabled/broken (cosmetics on nearly every swing)" : "grossly over-rejecting")
                            + " window");

            double displayedSum = 0.0;
            for (CoherenceProbe.Hit hit : accepted) {
                displayedSum += Math.min(hit.finalDamage(), hit.preHealth());
            }
            context.expectNear(startHealth - endHealth, displayedSum, FOLD_EPSILON,
                    "F3: the sum of displayed (health-clamped) damage must equal the health actually "
                            + "lost across the barrage");
        } finally {
            toggleModule(context, "hit-feedback", false);
            toggleModule(context, "damage-indicators", false);
            setEffectsPreset(context, "signature");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            probe.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  3b. upgrade delta: an in-window stronger hit ships the difference   */
    /* ------------------------------------------------------------------ */

    /**
     * The window-UPGRADE case (D1's second branch): a fresh 7.0 hit, then WITHIN
     * the still-open invulnerability window (2 ticks later, no clearing) the
     * attacker's attack-damage rises to 10.0 and swings again. Vanilla admits the
     * stronger hit and subtracts only the DELTA (amount − lastHurt = 3.0); the
     * EDBEE's {@code getFinalDamage()} is that delta on both modern Paper (an
     * INVULNERABILITY_REDUCTION modifier) and legacy CraftBukkit (the delta passed
     * as the event damage). Pins: two accepted EDBEEs, the second ≈ 3.0, a second
     * indicators decision, and — the honest cross-version oracle — the displayed
     * sum still equals the health actually lost.
     */
    private static void runUpgradeDelta(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        CoherenceProbe probe = CoherenceProbe.register(tester, () -> mental.clock().current().value());
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            setEffectsPreset(context, "signature");
            toggleModule(context, "hit-feedback", true);
            toggleModule(context, "damage-indicators", true);
            context.expect(moduleActive(mental, "damage-indicators"), "damage-indicators failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            context.expect(context.sync(() -> armAttacker(attacker)),
                    "attack-damage/attack-speed attributes unresolved — cannot stage the fresh 7.0 hit");

            probe.watch(victim.uuid());
            boolean staged = context.sync(() -> {
                if (!setMaxHealth(victim, 40.0)) {
                    return false;
                }
                victim.player().setHealth(40.0);
                return true;
            });
            if (!staged) {
                context.skip("max-health attribute absent — cannot stage the upgrade window");
                return;
            }

            trace.clear();
            probe.reset();
            double startHealth = context.sync(() -> victim.player().getHealth());

            // Hit 1: cleared nd so it lands fresh at 7.0 (a determinism stamp — the
            // window it OPENS is what the upgrade below rides).
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> probe.accepted().size() >= 1, 40,
                    "the fresh 7.0 opening hit to land");

            // Raise attack-damage to 10.0 and swing AGAIN inside the window. The
            // window is RE-STAMPED in the swing's own sync run: the legacy tier
            // (≤1.10.x lineage) double-ticks noDamageTicks (the 2.5.5 finding), so
            // the ~4–6 wall ticks of staging hops here can decay a 20-tick window
            // to the max/2 boundary and flip vanilla's branch from delta-upgrade
            // to fresh-accept (full 10.0 — the CI flake this stamp killed). The
            // case pins the UPGRADE MECHANICS, not window-decay timing, so the
            // in-window state is staged deterministically on every tier.
            context.awaitTicks(2);
            boolean raised = context.sync(() -> setAttackDamage(attacker, UPGRADE_DAMAGE));
            context.expect(raised, "attack-damage attribute unresolved — cannot stage the upgrade");
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(victim.player().getMaximumNoDamageTicks());
                victim.player().setLastDamage(HIT_DAMAGE);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> probe.accepted().size() >= 2, 40,
                    () -> "the in-window upgrade hit to fire a second EDBEE (accepted="
                            + probe.accepted().size() + ")");
            context.awaitTicks(4);
            double endHealth = context.sync(() -> victim.player().getHealth());

            List<CoherenceProbe.Hit> accepted = probe.accepted();
            context.note("upgrade: accepted=" + accepted.size() + " startHealth=" + startHealth
                    + " endHealth=" + endHealth
                    + (accepted.size() >= 2 ? " secondFinalDamage=" + accepted.get(1).finalDamage() : ""));
            context.expect(accepted.size() == 2,
                    "the fresh hit and the in-window upgrade must land exactly two accepted EDBEEs, got "
                            + accepted.size());

            context.expectNear(HIT_DAMAGE, accepted.get(0).finalDamage(), 0.01,
                    "the opening hit's finalDamage must be the full 7.0");
            context.expectNear(UPGRADE_DAMAGE - HIT_DAMAGE, accepted.get(1).finalDamage(), 0.01,
                    "the in-window upgrade must fire the DELTA (10.0 − 7.0 = 3.0) as finalDamage");

            long unsendable = countDecision(trace, "damage-indicators", "UNSENDABLE");
            context.expect(unsendable == 2,
                    "each accepted hit — the opener and the upgrade — must ship a damage-indicators "
                            + "decision; got " + unsendable + " UNSENDABLE (trace=" + trace.entries() + ")");

            double displayedSum = 0.0;
            for (CoherenceProbe.Hit hit : accepted) {
                displayedSum += Math.min(hit.finalDamage(), hit.preHealth());
            }
            context.expectNear(startHealth - endHealth, displayedSum, FOLD_EPSILON,
                    "F3 (the honest cross-version oracle): displayed damage summed across the opener and "
                            + "the delta upgrade must equal the health actually lost");
        } finally {
            toggleModule(context, "hit-feedback", false);
            toggleModule(context, "damage-indicators", false);
            setEffectsPreset(context, "signature");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            probe.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  4a. heal attribution: a setHealth heal → HEAL_UNSENDABLE            */
    /* ------------------------------------------------------------------ */

    /**
     * A real melee stamps the last-hit attribution (identity only — the clientless
     * attacker has no PacketEvents user), then a later {@code setHealth(+4.0)}
     * heal is detected by the session-tick health-delta sampler, attributed to
     * that attacker, and journaled {@code HEAL_UNSENDABLE} (the attacker has no
     * client to draw the stand on). The heal and the attacker/victim identities
     * are the pin.
     *
     * <p>The healed hearts are NOT on the {@code HEAL_UNSENDABLE} detail — the plan
     * puts the rendered amount only on the sendable {@code HEAL} decision (a real
     * client, legacy-lab territory) and designates {@code HEAL_UNSENDABLE} as the
     * attribution seam; the folded amount is unit-pinned in {@code HealFoldTest}.</p>
     */
    private static void runHealAttribution(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        CoherenceProbe probe = CoherenceProbe.register(tester, () -> mental.clock().current().value());
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            setEffectsPreset(context, "signature");
            toggleModule(context, "damage-indicators", true); // signature heal-text ⇒ the sampler installs
            context.expect(moduleActive(mental, "damage-indicators"), "damage-indicators failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            context.expect(context.sync(() -> armAttacker(attacker)),
                    "attack-damage/attack-speed attributes unresolved — cannot stamp the attribution hit");

            probe.watch(victim.uuid()); // freezes ambient regen so it can't invent a phantom heal

            // Stamp attribution with one real melee (nd cleared for determinism).
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> !probe.accepted().isEmpty(), 40,
                    "the attribution melee to land (stamping the last-hit)");

            // Let the sampler's lastHealth catch the post-hit value, then heal via
            // setHealth (the StarEnchants path) in one hop. Clearing the trace first
            // isolates the heal decision from the hit's own UNSENDABLE.
            context.awaitTicks(3);
            double preHeal = context.sync(() -> victim.player().getHealth());
            trace.clear();
            context.syncRun(() -> victim.player().setHealth(preHeal + 4.0));

            context.awaitUntil(() -> countDecision(trace, "damage-indicators", "HEAL_UNSENDABLE") >= 1, 40,
                    () -> "a damage-indicators HEAL_UNSENDABLE decision after the setHealth heal (trace="
                            + trace.entries() + ")");

            List<FeedbackTrace.Entry> heals = new ArrayList<>();
            for (FeedbackTrace.Entry entry : trace.entries()) {
                if ("damage-indicators".equals(entry.module()) && "HEAL_UNSENDABLE".equals(entry.decision())) {
                    heals.add(entry);
                }
            }
            context.expect(heals.size() == 1,
                    "exactly one heal decision must fire for one setHealth heal, got " + heals.size()
                            + " (trace=" + trace.entries() + ")");
            FeedbackTrace.Entry heal = heals.get(0);
            context.expect(victim.uuid().equals(heal.victim()),
                    "the heal must be journaled for the healed victim: " + heal);
            context.expect(attacker.uuid().equals(heal.attacker()),
                    "the heal must attribute to the LAST ATTACKER, not the victim: " + heal);
            context.expect("attacker has no PacketEvents user".equals(heal.detail()),
                    "a clientless (online, PE-user-less) attacker must read the no-PE-user detail, got '"
                            + heal.detail() + "'");
            context.note("heal attributed victim→attacker; HEAL_UNSENDABLE carries no hearts by design "
                    + "(the plan puts the amount only on the sendable HEAL) — the +4.0 fold is unit-pinned "
                    + "in HealFoldTest");
        } finally {
            toggleModule(context, "damage-indicators", false);
            setEffectsPreset(context, "signature");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            probe.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  4a². the one-heart floor: sub-heart heals hold, never spam          */
    /* ------------------------------------------------------------------ */

    /**
     * The 2.6.0 one-heart floor, staged live on the StarEnchants-shaped setHealth
     * path: a single half-heart heal (+1.0 pt) in fresh combat attribution writes
     * NO heal decision — even well past the fold's pacing window, proving the
     * FLOOR (not the window) is what held it — and a second +1.0 in a later
     * sampler tick crosses {@code MIN_SHIP_HEALTH} and ships EXACTLY ONE decision
     * carrying the accumulated sum (the un-consumed hold; the arithmetic is
     * unit-pinned in HealFoldTest, this pins the live wiring end-to-end).
     */
    private static void runSubHeartHoldsThenShips(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        CoherenceProbe probe = CoherenceProbe.register(tester, () -> mental.clock().current().value());
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            setEffectsPreset(context, "signature");
            toggleModule(context, "damage-indicators", true);
            context.expect(moduleActive(mental, "damage-indicators"), "damage-indicators failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            context.expect(context.sync(() -> armAttacker(attacker)),
                    "attack-damage/attack-speed attributes unresolved — cannot stamp the attribution hit");

            probe.watch(victim.uuid()); // freezes ambient regen so it can't top up the fold

            // Stamp attribution with one real melee (nd cleared for determinism).
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> !probe.accepted().isEmpty(), 40,
                    "the attribution melee to land (stamping the last-hit)");

            // Let the sampler's lastHealth catch the post-hit value, then trickle
            // HALF a heart in one hop. The trace is cleared first so the hit's own
            // UNSENDABLE cannot masquerade as a heal decision.
            context.awaitTicks(3);
            double preHeal = context.sync(() -> victim.player().getHealth());
            trace.clear();
            context.syncRun(() -> victim.player().setHealth(preHeal + 1.0));

            // Well past the 10-tick fold window: the FLOOR is what holds it.
            context.awaitTicks(15);
            context.expect(countDecision(trace, "damage-indicators", "HEAL_UNSENDABLE") == 0,
                    "a sub-heart heal must write NO heal decision (trace=" + trace.entries() + ")");

            // The second half-heart crosses the floor: exactly one decision ships,
            // carrying the accumulated (not the incremental) amount by construction.
            context.syncRun(() -> victim.player().setHealth(preHeal + 2.0));
            context.awaitUntil(() -> countDecision(trace, "damage-indicators", "HEAL_UNSENDABLE") >= 1, 40,
                    () -> "the crossing heal to ship one decision (trace=" + trace.entries() + ")");
            context.expect(countDecision(trace, "damage-indicators", "HEAL_UNSENDABLE") == 1,
                    "exactly one decision for the accumulated heal, got trace=" + trace.entries());
            context.note("half-heart held silently past the window; the crossing trickle shipped once");
        } finally {
            toggleModule(context, "damage-indicators", false);
            setEffectsPreset(context, "signature");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            probe.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  4b. fold pacing: a combined heal ships exactly one indicator        */
    /* ------------------------------------------------------------------ */

    /**
     * The pacing guarantee (F4): the fold ships at most one heal indicator per
     * victim per {@code WINDOW_TICKS} (10) — so a combined heal is ONE stand, not
     * one-per-HP spam. Both {@code +2.0} bumps land in ONE session-tick sample, so
     * the sampler observes a single {@code +4.0} delta and the fold ships exactly
     * once, and the count stays 1 across a settle well past the fold window.
     *
     * <p><b>Why one sample.</b> The committed {@code HealFold} ships the FIRST heal
     * immediately (a pot burst is never held); two bumps spaced ACROSS sampler
     * ticks therefore ship the first at once and aggregate the rest into a second
     * ship at the window boundary — the documented "at most one per 10 ticks", not
     * "exactly one". Staging both inside one sample is the deterministic shape that
     * yields the clean single aggregated decision; the multi-window pacing math is
     * unit-pinned in HealFoldTest.</p>
     */
    private static void runHealFoldPacing(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        CoherenceProbe probe = CoherenceProbe.register(tester, () -> mental.clock().current().value());
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            setEffectsPreset(context, "signature");
            toggleModule(context, "damage-indicators", true);
            context.expect(moduleActive(mental, "damage-indicators"), "damage-indicators failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            context.expect(context.sync(() -> armAttacker(attacker)),
                    "attack-damage/attack-speed attributes unresolved — cannot stamp the attribution hit");

            probe.watch(victim.uuid());
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> !probe.accepted().isEmpty(), 40, "the attribution melee to land");
            context.awaitTicks(3);
            double preHeal = context.sync(() -> victim.player().getHealth());

            trace.clear();
            // Two +2.0 bumps in ONE hop → one +4.0 sampler delta → one paced ship.
            context.syncRun(() -> {
                victim.player().setHealth(preHeal + 2.0);
                victim.player().setHealth(victim.player().getHealth() + 2.0);
            });
            context.awaitUntil(() -> countDecision(trace, "damage-indicators", "HEAL_UNSENDABLE") >= 1, 40,
                    () -> "the folded heal to ship one decision (trace=" + trace.entries() + ")");
            // Settle well past the fold window (10 ticks): a single delta ships once
            // and re-arms empty, so no second decision ever appears.
            context.awaitTicks(15);

            long heals = countDecision(trace, "damage-indicators", "HEAL_UNSENDABLE");
            context.note("fold pacing: " + heals + " heal decision(s) for a combined +4.0 heal");
            context.expect(heals == 1,
                    "a combined heal must fold into exactly ONE paced decision (not one-per-HP), got "
                            + heals + " (trace=" + trace.entries() + ")");
        } finally {
            toggleModule(context, "damage-indicators", false);
            setEffectsPreset(context, "signature");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            probe.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  4c. unattributed silence: a heal with no fresh combat writes nothing */
    /* ------------------------------------------------------------------ */

    /**
     * A victim that was NEVER hit is healed via {@code setHealth}: the delta is
     * detected and the fold ships, but with no fresh last-hit attribution the heal
     * is dropped SILENTLY — no trace. This is the ambient-regen guard: a
     * regenerating player who is not in combat must never raise heal stands. The
     * heal is staged as a real {@code setHealth} (lower first, then raise) so the
     * case cannot pass vacuously on a victim whose health never moved.
     */
    private static void runUnattributedHealSilent(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        CoherenceProbe probe = CoherenceProbe.register(tester, () -> mental.clock().current().value());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            setEffectsPreset(context, "signature");
            toggleModule(context, "damage-indicators", true);
            context.expect(moduleActive(mental, "damage-indicators"), "damage-indicators failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            probe.watch(victim.uuid());

            // Lower health with setHealth (a drop — never a hit, so nothing stamps
            // attribution), settle so the sampler's lastHealth tracks the low value.
            context.syncRun(() -> victim.player().setHealth(10.0));
            context.awaitTicks(3);

            trace.clear();
            // Heal it — a genuine +4.0 delta, but unattributed.
            context.syncRun(() -> victim.player().setHealth(14.0));
            context.awaitTicks(15); // well past any fold window — a heal would have shipped by now

            List<FeedbackTrace.Entry> indicatorEntries = entriesFor(trace, "damage-indicators");
            context.expect(indicatorEntries.isEmpty(),
                    "an unattributed heal (the victim was never hit) must write NO damage-indicators "
                            + "trace — ambient regen must not spam. Got: " + indicatorEntries);
        } finally {
            toggleModule(context, "damage-indicators", false);
            setEffectsPreset(context, "signature");
            context.syncRun(victim::remove);
            probe.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  4d. zero-touch: module off, a stamp+heal writes not one entry       */
    /* ------------------------------------------------------------------ */

    /**
     * With damage-indicators OFF (its default), no heal sampler is installed and
     * no EDBEE listener runs, so a stamping melee AND a {@code setHealth} heal both
     * write nothing — the always-on {@code lastHealth} swap on the session tick is
     * observation-only by type, so the whole heal machinery stays dormant. The
     * melee is confirmed to LAND (via the suite probe) so the zero-touch proof
     * cannot pass vacuously on a hit that never happened.
     */
    private static void runHealZeroTouch(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        CoherenceProbe probe = CoherenceProbe.register(tester, () -> mental.clock().current().value());
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            // Assert the default-OFF state rather than force it, so a leaked enable
            // from an earlier case fails loudly here.
            context.expect(!moduleActive(mental, "damage-indicators"),
                    "damage-indicators must be OFF by default (a previous case leaked an enable?)");
            context.expect(!moduleActive(mental, "hit-feedback"),
                    "hit-feedback must be OFF by default (a previous case leaked an enable?)");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            context.expect(context.sync(() -> armAttacker(attacker)),
                    "attack-damage/attack-speed attributes unresolved — cannot stage the stamp attempt");

            probe.watch(victim.uuid());
            trace.clear();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> !probe.accepted().isEmpty(), 40,
                    "the stamp-attempt melee to land (so zero-touch is proven against a real hit)");

            context.awaitTicks(3);
            double preHeal = context.sync(() -> victim.player().getHealth());
            context.syncRun(() -> victim.player().setHealth(preHeal + 4.0));
            context.awaitTicks(15);

            context.expect(trace.entries().isEmpty(),
                    "with the feedback modules off a stamp+heal must write not one trace entry "
                            + "(zero-touch broken): " + trace.entries());
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            probe.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  The suite-local probe: count EDBEEs, freeze regen                   */
    /* ------------------------------------------------------------------ */

    /**
     * Records every EDBEE a watched victim takes (final damage + pre-hit health at
     * MONITOR, {@code ignoreCancelled = false} so nothing is missed) and cancels
     * that victim's {@link EntityRegainHealthEvent}s — the deterministic regen
     * freeze the fold arithmetic and the phantom-heal guard both need. A staged
     * {@code setHealth} heal bypasses the regain event, so it still lands.
     */
    private static final class CoherenceProbe implements Listener {

        /** One accepted EDBEE: the reported final damage, the pre-hit health, and the tick stamp. */
        record Hit(double finalDamage, double preHealth, long tick) {}

        private final Set<UUID> victims = ConcurrentHashMap.newKeySet();
        private final List<Hit> hits = new CopyOnWriteArrayList<>();
        /** The cross-version tick source (Mental's TickClock) — never {@code Bukkit.getCurrentTick()},
         *  a modern-only Paper API absent below the compile floor's runtime range. */
        private final LongSupplier tickSource;

        private CoherenceProbe(LongSupplier tickSource) {
            this.tickSource = tickSource;
        }

        static CoherenceProbe register(Plugin plugin, LongSupplier tickSource) {
            CoherenceProbe probe = new CoherenceProbe(tickSource);
            plugin.getServer().getPluginManager().registerEvents(probe, plugin);
            return probe;
        }

        void watch(UUID victim) {
            victims.add(victim);
        }

        void reset() {
            hits.clear();
        }

        void unregister() {
            HandlerList.unregisterAll(this);
        }

        /** The accepted hits (non-cancelled, {@code finalDamage > 0}) — the cosmetics' own gate. */
        List<Hit> accepted() {
            List<Hit> result = new ArrayList<>();
            for (Hit hit : hits) {
                if (hit.finalDamage() > 0.0) {
                    result.add(hit);
                }
            }
            return result;
        }

        /** The tick span between the first and last accepted hit — a diagnostic for the immunity band. */
        long spanTicks() {
            List<Hit> accepted = accepted();
            if (accepted.size() < 2) {
                return 0;
            }
            return accepted.get(accepted.size() - 1).tick() - accepted.get(0).tick();
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
        public void onDamage(EntityDamageByEntityEvent event) {
            if (!(event.getEntity() instanceof Player victim)) {
                return;
            }
            if (event.isCancelled() || !victims.contains(victim.getUniqueId())) {
                return;
            }
            // getHealth() at EDBEE MONITOR is the PRE-hit pool (the subtraction runs
            // after every handler) — the same read the cosmetics clamp against.
            hits.add(new Hit(event.getFinalDamage(), victim.getHealth(), tickSource.getAsLong()));
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        public void onRegen(EntityRegainHealthEvent event) {
            if (event.getEntity() instanceof Player victim && victims.contains(victim.getUniqueId())) {
                event.setCancelled(true); // freeze ambient regen; staged setHealth heals bypass this event
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers (the FeedbackSuite idioms, replicated)                     */
    /* ------------------------------------------------------------------ */

    /** Pins the attacker's damage and meter through attributes (the BlockingSuite idiom). */
    private static boolean armAttacker(FakePlayer attacker) {
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
        attackDamage.setBaseValue(HIT_DAMAGE);
        attackSpeed.setBaseValue(40.0);
        return true;
    }

    /** Raises the attacker's attack-damage base (3b's in-window upgrade). */
    private static boolean setAttackDamage(FakePlayer attacker, double value) {
        Attribute damageAttribute = Attributes.attackDamage();
        if (damageAttribute == null) {
            return false;
        }
        AttributeInstance attackDamage = attacker.player().getAttribute(damageAttribute);
        if (attackDamage == null) {
            return false;
        }
        attackDamage.setBaseValue(value);
        return true;
    }

    /** Widens the victim's max-health window through the stable max-health attribute (1.9.4→26.x). */
    private static boolean setMaxHealth(FakePlayer victim, double max) {
        Attribute attribute = Attributes.maxHealth();
        if (attribute == null) {
            return false;
        }
        AttributeInstance instance = victim.player().getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        instance.setBaseValue(max);
        return true;
    }

    /** The trace entries one module journaled, in ring order. */
    private static List<FeedbackTrace.Entry> entriesFor(FeedbackTrace trace, String module) {
        List<FeedbackTrace.Entry> matching = new ArrayList<>();
        for (FeedbackTrace.Entry entry : trace.entries()) {
            if (module.equals(entry.module())) {
                matching.add(entry);
            }
        }
        return matching;
    }

    /** Count of a module's entries carrying exactly this decision. */
    private static long countDecision(FeedbackTrace trace, String module, String decision) {
        long count = 0;
        for (FeedbackTrace.Entry entry : trace.entries()) {
            if (module.equals(entry.module()) && decision.equals(entry.decision())) {
                count++;
            }
        }
        return count;
    }

    /** Count of hit-feedback EMIT decisions (any EMITTED* variant, or NO_VIEWERS). */
    private static long countHitFeedbackEmits(FeedbackTrace trace) {
        long count = 0;
        for (FeedbackTrace.Entry entry : trace.entries()) {
            if (!"hit-feedback".equals(entry.module())) {
                continue;
            }
            String decision = entry.decision();
            if (decision.startsWith("EMITTED") || "NO_VIEWERS".equals(decision)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Writes the ONE Combat Effects selection key ({@code effects.preset}) into the
     * machine overlay and reloads — the overlay-wins path the GUI's picker uses.
     */
    private static void setEffectsPreset(TestContext context, String preset) throws Exception {
        context.syncRun(() -> {
            MentalPluginV5 plugin = (MentalPluginV5) Bukkit.getPluginManager().getPlugin("Mental");
            plugin.overlaySet("effects.preset", preset);
            plugin.management().reload();
        });
        context.awaitTicks(1);
    }

    /** Toggles a module through the v5 management write-back seam and waits for convergence. */
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
