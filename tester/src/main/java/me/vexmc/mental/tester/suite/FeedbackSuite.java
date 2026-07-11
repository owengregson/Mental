package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.feedback.FeedbackTrace;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.jetbrains.annotations.NotNull;

/**
 * Coverage for the FEEDBACK family (hit-feedback, damage-indicators,
 * death-effects) via the {@link FeedbackTrace} decision ring.
 *
 * <p>Everything this family produces is cosmetic wire traffic, and clientless
 * {@link FakePlayer}s void all outbound packets and carry no PacketEvents user
 * — nothing the modules send is observable here. What IS observable is the
 * DECISION each listener journals into the trace ring at the moment it is
 * made, before any send: sounds resolved, low-HP layering, indicator
 * sendability, the death strike. The suites assert those decisions plus the
 * two contracts that must hold regardless of audience: zero-touch (a disabled
 * module writes no trace and changes nothing) and the hit always landing.
 * The sendable path's packet encode is unit-pinned (the feedback packet
 * tests); real-client behaviour is legacy-lab territory.</p>
 *
 * <p>The one decision a fake attacker CAN pin exactly is
 * {@code damage-indicators}' {@code UNSENDABLE}: the attacker has no
 * PacketEvents user, which is precisely the honest outcome the listener must
 * record instead of throwing or spawning an undrawable stand.</p>
 */
public final class FeedbackSuite {

    private FeedbackSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("feedback zero-touch: disabled modules write no trace", context ->
                        runDisabledWritesNoTrace(mental, tester, context)),
                new TestCase("hit-feedback records its decision and the hit still lands", context ->
                        runHitFeedbackDecision(mental, tester, context)),
                new TestCase("damage-indicators records UNSENDABLE for a clientless attacker", context ->
                        runIndicatorsUnsendable(mental, tester, context)),
                new TestCase("damage-indicators disable is clean mid-flight", context ->
                        runIndicatorsDisableMidFlight(mental, tester, context)),
                new TestCase("death-effects zero-touch: disabled writes no trace on a real death", context ->
                        runDeathEffectsZeroTouch(mental, tester, context)),
                new TestCase("death-effects signature records EMITTED on death", context ->
                        runDeathEffectsSignature(mental, tester, context)));
    }

    /* ------------------------------------------------------------------ */
    /*  1. zero-touch: OFF means not a single trace write                   */
    /* ------------------------------------------------------------------ */

    /**
     * Both hit modules ship default-OFF; with them off a staged melee hit must
     * add nothing to the trace ring — zero writes is the zero-touch proof the
     * ring was built to make assertable. The hit itself and Mental's normal
     * knockback ownership must be untouched by the disabled cosmetics.
     */
    private static void runDisabledWritesNoTrace(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            // Default-OFF is the state under test: assert it rather than force it,
            // so a leaked enable from an earlier case fails loudly here.
            context.expect(!moduleActive(mental, "hit-feedback"),
                    "hit-feedback must be OFF by default (a previous case leaked an enable?)");
            context.expect(!moduleActive(mental, "damage-indicators"),
                    "damage-indicators must be OFF by default (a previous case leaked an enable?)");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);

            trace.clear();
            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "the staged hit to land with both feedback modules disabled");
            // The knockback module's NORMAL ownership must be unchanged by the
            // disabled cosmetics: Mental still owns and applies this melee knock.
            // The apply rides the velocity event, which can trail the damage
            // event by a tick — wait on it, don't read it off the damage wait.
            context.awaitUntil(() -> captors.knockbackAppliesTo(victim.uuid()) >= 1, 40,
                    "Mental's normal knockback ownership (KnockbackApplyEvent) with the "
                            + "feedback modules disabled");

            context.expect(trace.entries().isEmpty(),
                    "disabled feedback modules wrote trace entries (zero-touch broken): " + trace.entries());
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  2. hit-feedback: the decision is made and the hit lands             */
    /* ------------------------------------------------------------------ */

    /**
     * Enabled through the real management seam, one landed melee must journal
     * exactly one {@code hit-feedback} decision. Fake players may be the only
     * audience on this server, so EMITTED and NO_VIEWERS are both valid — the
     * pin is that the decision was made (and journaled) and the hit still
     * landed; what each decision ships is unit-pinned on the packet side.
     */
    private static void runHitFeedbackDecision(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            toggleModule(context, "hit-feedback", true);
            context.expect(moduleActive(mental, "hit-feedback"),
                    "hit-feedback module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 3, -2));
                victim.spawn(Arena.offset(centre, 3, 2));
            });
            context.awaitTicks(5);

            trace.clear();
            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "the staged hit to land with hit-feedback enabled");

            context.expect(captors.damageOf(victim.uuid()) != null,
                    "the hit must still land with hit-feedback enabled");
            List<FeedbackTrace.Entry> entries = entriesFor(trace, "hit-feedback");
            context.expect(entries.size() == 1,
                    "expected exactly one hit-feedback decision for one landed hit, got "
                            + entries.size() + " (trace=" + trace.entries() + ")");
            FeedbackTrace.Entry entry = entries.get(0);
            context.expect(victim.uuid().equals(entry.victim()),
                    "the hit-feedback decision was journaled for the wrong victim: " + entry);
            context.expect("EMITTED".equals(entry.decision()) || "NO_VIEWERS".equals(entry.decision()),
                    "a full-health staged hit must decide EMITTED or NO_VIEWERS, got '"
                            + entry.decision() + "' (" + entry.detail() + ")");
        } finally {
            toggleModule(context, "hit-feedback", false);
            context.expect(!moduleActive(mental, "hit-feedback"),
                    "hit-feedback module failed to disable");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  3. damage-indicators: UNSENDABLE for a clientless attacker          */
    /* ------------------------------------------------------------------ */

    /**
     * A fake attacker has no PacketEvents user, so the indicator cannot be
     * drawn on anything — the listener must journal {@code UNSENDABLE} instead
     * of spawning an undrawable stand or throwing. That decision IS the pass:
     * the sendable path's spawn/metadata encode is unit-pinned
     * (IndicatorStandPacketsTest), out of a clientless suite's reach.
     */
    private static void runIndicatorsUnsendable(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            toggleModule(context, "damage-indicators", true);
            context.expect(moduleActive(mental, "damage-indicators"),
                    "damage-indicators module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 6, -2));
                victim.spawn(Arena.offset(centre, 6, 2));
            });
            context.awaitTicks(5);

            trace.clear();
            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "the staged hit to land with damage-indicators enabled");

            List<FeedbackTrace.Entry> entries = entriesFor(trace, "damage-indicators");
            context.expect(entries.size() == 1,
                    "expected exactly one damage-indicators decision for one landed hit, got "
                            + entries.size() + " (trace=" + trace.entries() + ")");
            context.expect("UNSENDABLE".equals(entries.get(0).decision()),
                    "a clientless attacker (no PacketEvents user) must decide UNSENDABLE, got '"
                            + entries.get(0).decision() + "' (" + entries.get(0).detail() + ")");
        } finally {
            toggleModule(context, "damage-indicators", false);
            context.expect(!moduleActive(mental, "damage-indicators"),
                    "damage-indicators module failed to disable");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  4. damage-indicators: mid-flight disable is clean                   */
    /* ------------------------------------------------------------------ */

    /**
     * Disabling right behind an attack exercises the scope-close path (driver
     * tasks, packet listeners) while a hit's aftermath is still settling. The
     * assertable half is the clean converged state and combat surviving; a
     * surfaced exception on the close path is caught by the D-9 log scan, the
     * real gate for "no exception surfaced".
     */
    private static void runIndicatorsDisableMidFlight(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            toggleModule(context, "damage-indicators", true);
            context.expect(moduleActive(mental, "damage-indicators"),
                    "damage-indicators module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, -3, -2));
                victim.spawn(Arena.offset(centre, -3, 2));
            });
            context.awaitTicks(5);

            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            // Immediately behind the hit — no settling wait — pull the module
            // down through the same management seam and let the scope close.
            toggleModule(context, "damage-indicators", false);
            context.awaitTicks(2);
            context.expect(!moduleActive(mental, "damage-indicators"),
                    "damage-indicators must be inactive after the mid-flight disable");

            // Combat must have survived the mid-flight teardown: a subsequent
            // vanilla hit lands with nothing left of the module.
            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "a vanilla hit to land after the mid-flight disable");
            context.note("mid-flight disable converged cleanly; exception-freedom is enforced "
                    + "by the D-9 log scan");
        } finally {
            toggleModule(context, "damage-indicators", false);
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  5. death-effects zero-touch: a real death, not a single write       */
    /* ------------------------------------------------------------------ */

    /**
     * Death-effects ships default-OFF too; a REAL death (not a staged event)
     * with the module off must add no {@code death-effects} entries. The kill
     * is {@code setHealth(0.0)} on the main thread — PlayerDeathEvent fires
     * synchronously inside it, so the trace read after the sync hop is already
     * past the moment a write could have happened.
     */
    private static void runDeathEffectsZeroTouch(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            context.expect(!moduleActive(mental, "death-effects"),
                    "death-effects must be OFF by default (a previous case leaked an enable?)");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                victim.spawn(Arena.offset(centre, -6, 2));
            });
            context.awaitTicks(5);

            trace.clear();
            context.syncRun(() -> victim.player().setHealth(0.0));
            context.awaitTicks(2);

            context.expect(entriesFor(trace, "death-effects").isEmpty(),
                    "disabled death-effects wrote trace entries on a real death (zero-touch broken): "
                            + trace.entries());
        } finally {
            context.syncRun(victim::remove);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  6. death-effects signature: EMITTED on death + the killing-hit rule */
    /* ------------------------------------------------------------------ */

    /**
     * The signature preset on a real melee kill: exactly one
     * {@code death-effects} EMITTED decision — and, with hit-feedback enabled
     * alongside, the killing hit's own {@code hit-feedback} decision must be
     * plain {@code EMITTED}, never {@code EMITTED+LOW_HP}: death-effects owns
     * the moment, so the low-HP extra layer is suppressed on the hit that
     * kills. A preceding NON-killing low hit pins the contrast live (the
     * layer does fire below the threshold), so the killing-hit assertion
     * cannot pass vacuously on a layer that never fires at all.
     *
     * <p>Health staging: the attacker's damage rides pinned ATTRIBUTES (the
     * BlockingSuite idiom — base 7.0, attack-speed 40.0 for a full meter every
     * hit), never a held item: a clientless fake's equipment attribute
     * modifiers apply through the living-entity equipment tick, which legacy
     * servers do not reliably run for it before the staged hit (1.9.4 landed
     * fist-tier damage off a held iron sword and the low-HP window never
     * opened). With the attribute pinned, 9.0 hp lands post-hit at 2.0 — and
     * anywhere in [2.0, 7.6] under any residual charge — inside (0, 8.0):
     * below the default 4-heart threshold, above death; 1.0 hp dies to any
     * charge (min 1.4). The preset overlay is written BEFORE the enables so
     * the assemble reads it directly — the reconciler's settings-change bounce
     * would also catch a later write, but staging first keeps the case
     * single-transition.</p>
     */
    private static void runDeathEffectsSignature(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FakePlayer afterVictim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            // The preset first (see the javadoc), then the two enables. Since the
            // 2.5.3 preset library, ONE selection key stages the whole tune: the
            // signature preset carries BOTH the death strike and hit-feedback's
            // non-empty low-HP layer, so the contrast pin observes the layer
            // actually firing — the label is gated on a non-empty effective
            // layer, and the vanilla preset would read plain EMITTED at any
            // health. Every pinned decision below is identical to the 2.5.2
            // per-module staging this replaced.
            setEffectsPreset(context, "signature");
            toggleModule(context, "death-effects", true);
            toggleModule(context, "hit-feedback", true);
            context.expect(moduleActive(mental, "death-effects"),
                    "death-effects module failed to enable");
            context.expect(moduleActive(mental, "hit-feedback"),
                    "hit-feedback module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 1));
            });
            context.awaitTicks(5);
            context.expect(context.sync(() -> armAttacker(attacker)),
                    "attack-damage/attack-speed attributes unresolved — cannot stage"
                            + " deterministic damage for the health windows");

            // The contrast hit: non-killing, post-hit below the 4-heart
            // threshold — the low-HP layer MUST fire here. setHealth and the
            // attack share one sync hop so regen cannot tick in between.
            trace.clear();
            captors.reset();
            context.syncRun(() -> {
                victim.player().setHealth(9.0);
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "the low-HP staging hit to land");
            List<FeedbackTrace.Entry> lowHpEntries = entriesFor(trace, "hit-feedback");
            context.expect(lowHpEntries.size() == 1,
                    "expected exactly one hit-feedback decision for the low-HP hit, got "
                            + lowHpEntries.size() + " (trace=" + trace.entries() + ")");
            context.expect("EMITTED+LOW_HP".equals(lowHpEntries.get(0).decision()),
                    "a non-killing hit below the threshold must layer low-HP, got '"
                            + lowHpEntries.get(0).decision() + "' (" + lowHpEntries.get(0).detail() + ")");
            context.expect(entriesFor(trace, "death-effects").isEmpty(),
                    "death-effects fired on a non-killing hit: " + trace.entries());

            // The killing hit. A short settle re-fills the meter (attack-speed
            // 40 recharges within a tick) so the pinned 7.0 lands against 1.0 hp.
            context.awaitTicks(4);
            trace.clear();
            captors.reset();
            context.syncRun(() -> {
                victim.player().setHealth(1.0);
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> !entriesFor(trace, "death-effects").isEmpty(), 40,
                    () -> "a death-effects decision on the melee kill (trace=" + trace.entries() + ")");

            List<FeedbackTrace.Entry> deathEntries = entriesFor(trace, "death-effects");
            context.expect(deathEntries.size() == 1,
                    "expected exactly one death-effects decision for one death, got "
                            + deathEntries.size() + " (trace=" + trace.entries() + ")");
            context.expect("EMITTED".equals(deathEntries.get(0).decision()),
                    "the signature preset must decide EMITTED on a witnessed death, got '"
                            + deathEntries.get(0).decision() + "' (" + deathEntries.get(0).detail() + ")");
            List<FeedbackTrace.Entry> killingHit = entriesFor(trace, "hit-feedback");
            context.expect(killingHit.size() == 1,
                    "expected exactly one hit-feedback decision for the killing hit, got "
                            + killingHit.size() + " (trace=" + trace.entries() + ")");
            context.expect("EMITTED".equals(killingHit.get(0).decision()),
                    "the killing hit must NOT layer low-HP — death-effects owns the moment "
                            + "(expected EMITTED, got '" + killingHit.get(0).decision() + "', "
                            + killingHit.get(0).detail() + ")");

            // Both back off through the seam; a plain vanilla hit must still
            // land afterward (the victim is dead — a fresh one takes the hit).
            toggleModule(context, "hit-feedback", false);
            toggleModule(context, "death-effects", false);
            context.expect(!moduleActive(mental, "hit-feedback"),
                    "hit-feedback module failed to disable");
            context.expect(!moduleActive(mental, "death-effects"),
                    "death-effects module failed to disable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                afterVictim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            captors.reset();
            context.syncRun(() -> {
                afterVictim.player().setNoDamageTicks(0);
                attacker.attack(afterVictim.player());
            });
            context.awaitUntil(() -> captors.damageOf(afterVictim.uuid()) != null, 40,
                    "a vanilla hit to land after both modules disabled");
        } finally {
            toggleModule(context, "hit-feedback", false);
            toggleModule(context, "death-effects", false);
            // The parse default IS vanilla, so writing it back restores the
            // pre-test effective config even though the overlay key persists.
            setEffectsPreset(context, "vanilla");
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
                afterVictim.remove();
            });
            captors.unregister();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Pins the attacker's damage and meter through ATTRIBUTES (the BlockingSuite
     * idiom): base 7.0 kills the 1.0-hp stage and lands the 9.0-hp stage inside
     * the low-HP window under any charge; attack-speed 40.0 re-fills the meter
     * within a tick. A held weapon is deliberately not used — its attribute
     * modifiers ride the living-entity equipment tick, which legacy servers do
     * not reliably run for a clientless fake before the staged hit.
     */
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
        attackDamage.setBaseValue(7.0);
        attackSpeed.setBaseValue(40.0);
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

    /**
     * Writes the ONE Combat Effects selection key ({@code effects.preset} — the
     * 2.5.3 preset library replaced the per-module {@code <module>.preset}
     * enums) into the machine overlay and reloads — the same overlay-wins path
     * the GUI's preset picker uses; the human YAML is never touched.
     */
    private static void setEffectsPreset(TestContext context, String preset) throws Exception {
        context.syncRun(() -> {
            MentalPluginV5 plugin = (MentalPluginV5) Bukkit.getPluginManager().getPlugin("Mental");
            plugin.overlaySet("effects.preset", preset);
            plugin.management().reload();
        });
        context.awaitTicks(1);
    }

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
