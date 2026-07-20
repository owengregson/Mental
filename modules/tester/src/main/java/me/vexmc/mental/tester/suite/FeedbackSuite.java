package me.vexmc.mental.tester.suite;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.tester.Arena;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
                new TestCase("hit-feedback low-HP threshold is PERCENT of max health, not absolute hearts",
                        context -> runLowHealthPercentSemantics(mental, tester, context)),
                new TestCase("damage-indicators records UNSENDABLE for a clientless attacker", context ->
                        runIndicatorsUnsendable(mental, tester, context)),
                new TestCase("damage-indicators reach a MOB victim and the mob tick ships the held window",
                        context -> runIndicatorsOnMobVictim(mental, tester, context)),
                new TestCase("environmental damage draws an indicator and the low-HP cue but no combat voice",
                        context -> runEnvironmentalFeedback(mental, tester, context)),
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
    /*  2b. hit-feedback: the low-HP ceiling is PERCENT of max, not hearts  */
    /* ------------------------------------------------------------------ */

    /**
     * The F2 semantics pin: the low-HP layer fires below a PERCENT of the
     * victim's OWN maximum health, not a fixed number of hearts. Staged on a
     * {@code setMaxHealth(40.0)} throwaway window (fresh fake, removed in
     * {@code finally}) so the two readings diverge and the test can tell them
     * apart:
     * <ul>
     *   <li>post-hit 10.0 hp = 25% of the 40 max — below the 35% ceiling
     *       (40 × 0.35 = 14.0) → {@code EMITTED+LOW_HP}. Crucially 10.0 hp is
     *       ABOVE the ~7-heart figure an absolute reading of the threshold would
     *       use, so an hearts-not-percent regression reads plain EMITTED here
     *       and this case catches it.</li>
     *   <li>post-hit 16.0 hp = 40% of the 40 max — above the 14.0 ceiling →
     *       plain {@code EMITTED}.</li>
     * </ul>
     * The attacker's 7.0 rides pinned attributes (armAttacker); each hit stages
     * {@code setMaxHealth + setHealth + setNoDamageTicks(0) + attack} in ONE
     * sync hop so nothing drifts between. Only hit-feedback is enabled — leaving
     * damage-indicators off keeps the between-hit {@code setHealth} raise from
     * ever being sampled as a heal.
     */
    private static void runLowHealthPercentSemantics(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            setEffectsPreset(context, "signature");
            toggleModule(context, "hit-feedback", true);
            context.expect(moduleActive(mental, "hit-feedback"),
                    "hit-feedback module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 3, -2));
                victim.spawn(Arena.offset(centre, 3, 2));
            });
            context.awaitTicks(5);
            context.expect(context.sync(() -> armAttacker(attacker)),
                    "attack-damage/attack-speed attributes unresolved — cannot stage the 7.0 hit");

            // The 40-max window separates percent-of-max from absolute-hearts.
            boolean staged = context.sync(() -> setMaxHealth(victim, 40.0));
            if (!staged) {
                context.skip("max-health attribute absent on this server — cannot stage a 40-max "
                        + "window to separate percent-of-max from absolute-hearts");
                return;
            }

            // Sub-case A — LOW_HP fires: post-hit 17.0 − 7.0 = 10.0 hp = 25% of 40,
            // below the 35% (14.0) ceiling.
            trace.clear();
            captors.reset();
            context.syncRun(() -> {
                setMaxHealth(victim, 40.0);
                victim.player().setHealth(17.0);
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "the 25%-of-max low-HP hit to land");
            List<FeedbackTrace.Entry> lowEntries = entriesFor(trace, "hit-feedback");
            context.expect(lowEntries.size() == 1,
                    "expected exactly one hit-feedback decision for the low-HP hit, got "
                            + lowEntries.size() + " (trace=" + trace.entries() + ")");
            context.expect("EMITTED+LOW_HP".equals(lowEntries.get(0).decision()),
                    "post-hit 10.0 (25% of a 40-max) is below the 35% (14.0) ceiling — must layer "
                            + "low-HP; got '" + lowEntries.get(0).decision() + "' ("
                            + lowEntries.get(0).detail() + "). A regression reading the threshold as "
                            + "absolute hearts (~7) would wrongly read plain EMITTED at 10.0 hp.");

            // Meter recharge (attack-speed 40 fills within a tick) before the next hit.
            context.awaitTicks(4);

            // Sub-case B — plain EMITTED: post-hit 23.0 − 7.0 = 16.0 hp = 40% of 40,
            // above the 35% (14.0) ceiling.
            trace.clear();
            captors.reset();
            context.syncRun(() -> {
                setMaxHealth(victim, 40.0);
                victim.player().setHealth(23.0);
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitUntil(() -> captors.damageOf(victim.uuid()) != null, 40,
                    "the 40%-of-max plain hit to land");
            List<FeedbackTrace.Entry> plainEntries = entriesFor(trace, "hit-feedback");
            context.expect(plainEntries.size() == 1,
                    "expected exactly one hit-feedback decision for the plain hit, got "
                            + plainEntries.size() + " (trace=" + trace.entries() + ")");
            context.expect("EMITTED".equals(plainEntries.get(0).decision()),
                    "post-hit 16.0 (40% of a 40-max) is above the 35% (14.0) ceiling — must NOT "
                            + "layer low-HP; got '" + plainEntries.get(0).decision() + "' ("
                            + plainEntries.get(0).detail() + ")");
        } finally {
            toggleModule(context, "hit-feedback", false);
            setEffectsPreset(context, "signature");
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
     * of spawning an undrawable stand or throwing. Under the window model
     * (2026-07-12) that decision arrives at SHIP time, one roll hold after the
     * hit's own {@code WINDOW_HELD}: the pair, in that order, IS the pass; the
     * sendable path's spawn/metadata encode is unit-pinned
     * (IndicatorStandPacketsTest), out of a clientless suite's reach.
     */
    /**
     * Indicators cover a NON-PLAYER victim (2026-07-20). Before this the listener
     * required {@code victim instanceof Player}, so hitting a mob drew nothing at all.
     *
     * <p>The ship entry is the load-bearing assertion, not the held one. A player
     * victim's roll-hold flush comes from their CombatSession tick — a mob has no
     * session, so a mob's held window can ONLY be shipped by {@link
     * MobFeedbackTicker}. Seeing WINDOW_HELD followed by a ship decision for a COW
     * therefore proves both halves at once: the routing now admits mob victims, AND
     * the mob-side tick exists and flushes. Without that tick the hold would simply
     * never elapse and every mob hit would be silently swallowed.
     *
     * <p>The ship decides UNSENDABLE because the only viewer is a clientless fake
     * (no PacketEvents user) — the same reason {@link #runIndicatorsUnsendable}
     * pins UNSENDABLE. The decision is the seam; the encode is unit-pinned.
     */
    private static void runIndicatorsOnMobVictim(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();
        AtomicReference<LivingEntity> spawned = new AtomicReference<>();

        try {
            toggleModule(context, "damage-indicators", true);
            context.expect(moduleActive(mental, "damage-indicators"),
                    "damage-indicators module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 9, -2));
                // Spawned AFTER Arena.prepare: prepare purges nearby Monsters, and a
                // passive cow with AI off never retaliates or wanders, so the only
                // damage event in the trace is the one this case stages.
                Location at = Arena.offset(centre, 9, 1);
                LivingEntity cow = (LivingEntity) at.getWorld().spawnEntity(at, EntityType.COW);
                try {
                    cow.setAI(false);
                } catch (Throwable noAiApi) {
                    // pre-1.11 servers: a stationary cow is good enough for one staged hit
                }
                spawned.set(cow);
            });
            context.awaitTicks(5);

            LivingEntity cow = spawned.get();
            context.expect(cow != null && cow.isValid(), "the cow victim failed to spawn");
            if (cow == null || !cow.isValid()) {
                return;
            }

            trace.clear();
            context.syncRun(() -> {
                cow.setNoDamageTicks(0);
                attacker.attack(cow);
            });

            context.awaitUntil(
                    () -> entriesFor(trace, "damage-indicators").stream()
                            .anyMatch(entry -> cow.getUniqueId().equals(entry.victim())
                                    && !"WINDOW_HELD".equals(entry.decision())), 60,
                    () -> "the MOB victim's held window to ship through the mob tick (trace="
                            + trace.entries() + ")");

            List<FeedbackTrace.Entry> entries = entriesFor(trace, "damage-indicators").stream()
                    .filter(entry -> cow.getUniqueId().equals(entry.victim()))
                    .toList();
            context.expect(entries.size() >= 2,
                    "expected the mob hit to OPEN a window and then SHIP it, got " + entries.size()
                            + " decision(s) (trace=" + trace.entries() + ")");
            context.expect("WINDOW_HELD".equals(entries.get(0).decision()),
                    "the mob hit must open and hold a window first, got '"
                            + entries.get(0).decision() + "'");
            context.expect("UNSENDABLE".equals(entries.get(1).decision()),
                    "the mob window must SHIP past its roll hold — only MobFeedbackTicker can flush a "
                            + "session-less victim, so any other decision means the mob tick never ran; got '"
                            + entries.get(1).decision() + "' (" + entries.get(1).detail() + ")");
        } finally {
            context.syncRun(() -> {
                LivingEntity cow = spawned.get();
                if (cow != null) {
                    cow.remove();
                }
                attacker.remove();
            });
            toggleModule(context, "damage-indicators", false);
        }
    }

    /**
     * Environmental damage (2026-07-20): the INDICATOR shows, the combat voice does
     * NOT, and the low-health warning still fires.
     *
     * <p>The split is the owner ruling — hit sounds are for combat, so fall/lava/
     * drowning keep vanilla's cause-specific hurt sounds ({@code hurt_on_fire},
     * {@code hurt_drown}) and a player can still hear WHY they are dying; but the
     * low-health cue is a warning about HEALTH, not about the blow, so it rides every
     * cause. A source-less {@code damage()} is the lava/berry proxy (cause CUSTOM,
     * never an EntityDamageByEntityEvent), the same proxy the CT8c i-frame suite uses.
     */
    private static void runEnvironmentalFeedback(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FakePlayer bystander = new FakePlayer(tester, mental.scheduling());
        FeedbackTrace trace = mental.feedbackTrace();

        try {
            setEffectsPreset(context, "signature");
            toggleModule(context, "hit-feedback", true);
            toggleModule(context, "damage-indicators", true);
            context.expect(moduleActive(mental, "hit-feedback"), "hit-feedback module failed to enable");
            context.expect(moduleActive(mental, "damage-indicators"),
                    "damage-indicators module failed to enable");

            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                victim.spawn(Arena.offset(centre, -6, 2));
                // A bystander keeps the viewer set non-empty: the victim never sees
                // their OWN number, so with nobody else around there is nothing to draw.
                bystander.spawn(Arena.offset(centre, -6, 0));
            });
            context.awaitTicks(5);

            // Staging precondition. A clientless fake's damage() applies health loss on
            // SOME servers without firing EntityDamageEvent at all (measured: on the
            // 1.9.4 NMS health drops 6.0 -> 4.0 with no event reaching any listener).
            // Nothing downstream can then be asserted, and — worse — the "no combat
            // voice" half would pass VACUOUSLY on an event that never happened. So the
            // probe decides: no event, loud skip; event, the full assertions below run.
            // Measured rather than version-gated, so any server that CAN stage it does.
            AtomicBoolean staged = new AtomicBoolean();
            Listener probe = new Listener() {
                @EventHandler(priority = EventPriority.MONITOR)
                public void onAnyDamage(EntityDamageEvent event) {
                    if (event.getEntity().getUniqueId().equals(victim.uuid())) {
                        staged.set(true);
                    }
                }
            };
            context.syncRun(() -> Bukkit.getPluginManager().registerEvents(probe, tester));

            trace.clear();
            // Drop to a sliver, then deal source-less damage that leaves them alive and
            // under the low-health ceiling: the warning must fire, the voice must not.
            context.syncRun(() -> {
                victim.player().setHealth(6.0);
                victim.player().setNoDamageTicks(0);
                victim.player().damage(2.0);
            });
            context.awaitTicks(3);
            context.syncRun(() -> HandlerList.unregisterAll(probe));

            if (!staged.get()) {
                context.note("skipped: a clientless fake's damage() fires no EntityDamageEvent on "
                        + mental.environment().describe() + " (the health loss lands, but no listener "
                        + "ever sees it), so environmental feedback cannot be staged here and the "
                        + "no-combat-voice half would pass vacuously; the audience rule is unit-pinned "
                        + "in IndicatorViewersTest and the split is asserted live on modern servers");
                return;
            }

            context.awaitUntil(
                    () -> entriesFor(trace, "hit-feedback").stream()
                            .anyMatch(entry -> "LOW_HP_ENVIRONMENTAL".equals(entry.decision())), 60,
                    () -> "the environmental low-health cue to fire (trace=" + trace.entries() + ")");

            List<FeedbackTrace.Entry> voice = entriesFor(trace, "hit-feedback").stream()
                    .filter(entry -> entry.decision() != null && entry.decision().startsWith("EMITTED"))
                    .toList();
            context.expect(voice.isEmpty(),
                    "environmental damage must NOT play the combat voice — vanilla's cause-specific "
                            + "hurt sound is how a player hears WHY they are dying; got " + voice);

            context.awaitUntil(
                    () -> entriesFor(trace, "damage-indicators").stream()
                            .anyMatch(entry -> victim.uuid().equals(entry.victim())), 60,
                    () -> "an indicator window for the environmental damage (trace=" + trace.entries() + ")");
        } finally {
            context.syncRun(() -> {
                victim.remove();
                bystander.remove();
            });
            toggleModule(context, "damage-indicators", false);
            toggleModule(context, "hit-feedback", false);
        }
    }

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

            // The marker is HELD for roll-hold-ticks before its one ship attempt
            // (the 2026-07-12 window model), so the UNSENDABLE decision TRAILS the
            // hit's own WINDOW_HELD — wait for the ship, then pin the exact pair.
            context.awaitUntil(
                    () -> entriesFor(trace, "damage-indicators").stream()
                            .anyMatch(entry -> "UNSENDABLE".equals(entry.decision())), 40,
                    () -> "the held window to ship (journaling UNSENDABLE) past its roll hold (trace="
                            + trace.entries() + ")");

            List<FeedbackTrace.Entry> entries = entriesFor(trace, "damage-indicators");
            context.expect(entries.size() == 2,
                    "expected exactly two damage-indicators decisions for one landed hit — the held "
                            + "window and its one ship attempt — got " + entries.size()
                            + " (trace=" + trace.entries() + ")");
            context.expect("WINDOW_HELD".equals(entries.get(0).decision()),
                    "the landed hit must OPEN and HOLD its window first, got '"
                            + entries.get(0).decision() + "' (" + entries.get(0).detail() + ")");
            context.expect("UNSENDABLE".equals(entries.get(1).decision()),
                    "a clientless attacker (no PacketEvents user) must decide UNSENDABLE at ship "
                            + "time, got '" + entries.get(1).decision() + "' ("
                            + entries.get(1).detail() + ")");
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
     * opened). With the full-meter 7.0 pinned, 9.0 hp lands post-hit at 2.0 —
     * inside (0, 7.0): below the signature low-HP ceiling (the F2 percent
     * threshold, 35% of the 20-max fake = 7.0 hp, NOT the retired 4-heart
     * absolute), above death; 1.0 hp dies to any charge (min 1.4). The
     * per-hit ceiling scales with the victim's own max — pinned separately in
     * {@code runLowHealthPercentSemantics} on a 40-max window. The preset
     * overlay is written BEFORE the enables so
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
            // layer, and the DEFAULTS' empty low-HP layer would read plain
            // EMITTED at any health. Every pinned decision below is identical to
            // the 2.5.2 per-module staging this replaced.
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

            // The contrast hit: non-killing, post-hit below the 35%-of-max
            // low-HP ceiling — 9.0 − 7.0 = 2.0 hp, under the 7.0 ceiling (35% of
            // the 20-max fake) — so the low-HP layer MUST fire here. setHealth
            // and the attack share one sync hop so regen cannot tick in between.
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
            // "signature" IS the default selected preset (EffectsPreset.DEFAULT_NAME —
            // the retired "vanilla" preset no longer exists), so writing it back
            // restores the pre-test selection even though the overlay key persists.
            setEffectsPreset(context, "signature");
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

    /**
     * Widens the victim's maximum-health window through the max-health ATTRIBUTE
     * (stable 1.9.4→26.x — {@link Attributes#maxHealth()} resolves the
     * MAX_HEALTH / GENERIC_MAX_HEALTH enum either way), so the F2 low-HP ceiling
     * — a PERCENT of THIS victim's own max — scales with it. Returns false when
     * the attribute is absent, so the caller can skip rather than stage a false
     * window. The fake is a throwaway removed in {@code finally}, so no reset is
     * needed.
     */
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
