package me.vexmc.mental.tester.suite;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.manage.Management.BundleApplyResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * The rules-bundle mechanism end to end on a live server (spec §3.3). A bundle is
 * a MACRO, not a mode: {@code Management.applyBundle} writes one atomic overlay
 * batch and swaps the snapshot, so applying a shipped bundle must converge the
 * exact feature set the plan pins. The pure validate-and-expand step is unit-pinned
 * ({@code RulesBundleApplyTest}, 38 keys for ct8c / 46 signature / 45 vanilla); this
 * suite asserts the LIVE convergence — every module's {@code featureActive} matches
 * after the reload, {@code vanilla} makes Mental ZeroTouch-transparent, and
 * {@code signature} round-trips the classic ruleset.
 *
 * <p>Each case CAPTURES the full non-infrastructure feature state + the active
 * knockback profile up front and RESTORES it in {@code finally} (one atomic
 * overlay write + reload), so applying the transparency-inducing {@code vanilla}
 * bundle — which turns the delivery ENGINE off too — never leaks into a trailing
 * suite.</p>
 */
public final class RulesBundleSuite {

    /** The 13 Combat Test 8c rule features (plan Task H / bundle maps). */
    private static final List<String> CT8C_KEYS = List.of(
            "weapon-attack-speeds", "charged-attacks", "ct8c-damage", "ct8c-crits", "ct8c-sweep",
            "ct8c-iframes", "ct8c-shields", "ct8c-regen", "ct8c-consumables", "ct8c-potions",
            "ct8c-reach", "ct8c-projectiles", "cleaving");

    /** The 24 classic 1.7/1.8 rule features (plan Task H / bundle maps). */
    private static final List<String> CLASSIC_KEYS = List.of(
            "old-armour-strength", "old-armour-durability", "old-critical-hits", "old-tool-durability",
            "sword-blocking", "attack-cooldown", "disable-attack-sounds", "disable-sword-sweep",
            "old-golden-apples", "disable-enderpearl-cooldown", "old-player-regen", "old-potion-durations",
            "old-potion-values", "disable-crafting", "disable-offhand", "old-hitboxes", "combo-hold",
            "combo-reach-handicap", "pot-fill", "fast-pots", "hit-feedback", "damage-indicators",
            "death-effects", "drop-protection");

    /** The 7 delivery ENGINE features (always-on by default; vanilla turns them off too). */
    private static final List<String> ENGINE_KEYS = List.of(
            "hit-registration", "wtap-registration", "knockback", "latency-compensation",
            "fishing-knockback", "rod-velocity", "projectile-knockback");

    private RulesBundleSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("bundle: ct8c converges the CT8c feature set + selects the ct8c profile", context ->
                        runCt8cBundle(mental, context)),
                new TestCase("bundle: vanilla makes Mental ZeroTouch-transparent", context ->
                        runVanillaBundle(mental, tester, context)),
                new TestCase("bundle: signature round-trips the classic ruleset", context ->
                        runSignatureBundle(mental, context)),
                new TestCase("bundle: an unknown bundle name is a clean atomic refusal", context ->
                        runUnknownBundle(mental, context)));
    }

    /* ------------------------------------------------------------------ */
    /*  ct8c — all CT8c on, all classics off, the ct8c profile             */
    /* ------------------------------------------------------------------ */

    private static void runCt8cBundle(MentalPluginV5 mental, TestContext context) throws Exception {
        Snapshot before = capture(mental);
        try {
            BundleApplyResult result = context.sync(() -> mental.management().applyBundle("ct8c"));
            context.expect(result.applied(),
                    "the ct8c bundle must apply cleanly — errors: " + result.errors());
            context.awaitTicks(2);

            for (String key : CT8C_KEYS) {
                context.expect(active(mental, key),
                        "ct8c bundle: '" + key + "' must be ACTIVE after apply");
            }
            for (String key : CLASSIC_KEYS) {
                context.expect(!active(mental, key),
                        "ct8c bundle: classic '" + key + "' must be INACTIVE after apply");
            }
            context.expect("ct8c".equals(mental.snapshot().defaultProfile()),
                    "ct8c bundle must select the ct8c knockback profile (got "
                            + mental.snapshot().defaultProfile() + ")");
        } finally {
            restore(mental, context, before);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  vanilla — everything off, Mental transparent                       */
    /* ------------------------------------------------------------------ */

    private static void runVanillaBundle(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Snapshot before = capture(mental);
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        try {
            BundleApplyResult result = context.sync(() -> mental.management().applyBundle("vanilla"));
            context.expect(result.applied(),
                    "the vanilla bundle must apply cleanly — errors: " + result.errors());
            context.awaitTicks(2);

            // Every toggleable module — CT8c, classic AND the delivery engine — is off.
            for (String key : CT8C_KEYS) {
                context.expect(!active(mental, key), "vanilla bundle: '" + key + "' must be off");
            }
            for (String key : CLASSIC_KEYS) {
                context.expect(!active(mental, key), "vanilla bundle: classic '" + key + "' must be off");
            }
            for (String key : ENGINE_KEYS) {
                context.expect(!active(mental, key),
                        "vanilla bundle: engine '" + key + "' must be off (transparency)");
            }
            context.expect("modern-vanilla".equals(mental.snapshot().defaultProfile()),
                    "vanilla bundle must select the modern-vanilla profile (got "
                            + mental.snapshot().defaultProfile() + ")");

            // ZeroTouch-grade transparency: a real hit must produce vanilla knockback
            // with NO Mental KnockbackApplyEvent (its knockback engine is off).
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
            });
            context.awaitTicks(5);
            captors.reset();
            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                attacker.attack(victim.player());
            });
            context.awaitTicks(3);
            context.expect(captors.knockbackAppliesTo(victim.uuid()) == 0,
                    "Mental applied a knock under the vanilla bundle — not transparent");
            Vector velocity = captors.velocityOf(victim.uuid());
            context.expect(velocity != null,
                    "vanilla knockback must survive the transparent bundle (no velocity event)");
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
            restore(mental, context, before);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  signature — the classic ruleset restored                           */
    /* ------------------------------------------------------------------ */

    private static void runSignatureBundle(MentalPluginV5 mental, TestContext context) throws Exception {
        Snapshot before = capture(mental);
        try {
            BundleApplyResult result = context.sync(() -> mental.management().applyBundle("signature"));
            context.expect(result.applied(),
                    "the signature bundle must apply cleanly — errors: " + result.errors());
            context.awaitTicks(2);

            for (String key : CLASSIC_KEYS) {
                context.expect(active(mental, key),
                        "signature bundle: classic '" + key + "' must be ACTIVE after apply");
            }
            for (String key : ENGINE_KEYS) {
                context.expect(active(mental, key),
                        "signature bundle: engine '" + key + "' must be ACTIVE after apply");
            }
            for (String key : CT8C_KEYS) {
                context.expect(!active(mental, key),
                        "signature bundle: CT8c '" + key + "' must be INACTIVE after apply");
            }
            context.expect("signature".equals(mental.snapshot().defaultProfile()),
                    "signature bundle must select the signature knockback profile (got "
                            + mental.snapshot().defaultProfile() + ")");
            context.note("signature effects-preset selection is pinned in RulesBundleApplyTest (46-key expansion)");
        } finally {
            restore(mental, context, before);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  unknown — a clean refusal, nothing applied                         */
    /* ------------------------------------------------------------------ */

    private static void runUnknownBundle(MentalPluginV5 mental, TestContext context) throws Exception {
        Snapshot before = capture(mental);
        BundleApplyResult result = context.sync(() -> mental.management().applyBundle("no-such-bundle-xyz"));
        context.expect(!result.applied(), "an unknown bundle name must be refused");
        context.expect(!result.errors().isEmpty(), "a refusal must carry a reason");
        // Nothing changed: the live feature set is byte-identical to before.
        Snapshot after = capture(mental);
        context.expect(before.states.equals(after.states) && before.profile.equals(after.profile),
                "a refused bundle must change NOTHING (atomicity)");
    }

    /* ------------------------------------------------------------------ */
    /*  Capture / restore (order-independent, one atomic overlay write)     */
    /* ------------------------------------------------------------------ */

    /** The full toggleable-feature state + active profile, so any bundle apply can be undone exactly. */
    private record Snapshot(Map<Feature, Boolean> states, String profile) {}

    private static Snapshot capture(MentalPluginV5 mental) {
        Map<Feature, Boolean> states = new LinkedHashMap<>();
        for (Feature feature : Feature.values()) {
            if (!feature.infrastructure()) {
                states.put(feature, mental.featureActive(feature));
            }
        }
        return new Snapshot(states, mental.snapshot().defaultProfile());
    }

    private static void restore(MentalPluginV5 mental, TestContext context, Snapshot snapshot) throws Exception {
        context.syncRun(() -> {
            Map<String, Object> overlay = new LinkedHashMap<>();
            snapshot.states.forEach((feature, enabled) -> overlay.put("modules." + feature.yamlKey(), enabled));
            overlay.put("knockback.profile", snapshot.profile);
            mental.overlaySetAll(overlay);
            mental.overlayRemove("effects.preset"); // clear any bundle-set effects tune back to the file default
            mental.reloadAll();
        });
        context.awaitTicks(2);
    }

    private static boolean active(MentalPluginV5 mental, String moduleId) {
        return Feature.byModuleId(moduleId).map(mental::featureActive).orElse(false);
    }
}
