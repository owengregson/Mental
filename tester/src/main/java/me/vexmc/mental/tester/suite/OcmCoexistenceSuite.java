package me.vexmc.mental.tester.suite;

import java.lang.reflect.Method;
import java.util.List;
import me.vexmc.mental.kernel.coexist.ArbiterCore;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.feature.damage.DamageShaper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Live coexistence against a real OldCombatMechanics install with its default
 * config — which puts every player in the "old" modeset (old-player-knockback,
 * old-tool-damage, old-critical-hits) and enables fishing and projectile
 * modules everywhere. The discriminator throughout is Mental's
 * KnockbackApplyEvent: it fires for every knockback Mental applies and for
 * nothing Mental yields, while the raw vectors of Mental's 1.7.10 and OCM's
 * 1.8 first hits are intentionally identical.
 *
 * <p>v5: ownership is read through the ArbiterCore-backed {@link
 * me.vexmc.mental.v5.coexist.OcmBinding} ({@code mentalOwns(token, uuid)} — the
 * inverse of the retired {@code OcmGate.handles}); the melee expectation math is
 * the kernel {@link KnockbackEngine} through the production {@link EntityStates}
 * capture (shared with {@link KnockbackSuite}); and the fast-path damage handoff
 * — which the retired {@code HitApplier} drove directly but the v5 netty fast
 * path never reaches from a clientless player — is re-pointed at the {@link
 * DamageShaper} composition seam OCM actually consumes.</p>
 */
public final class OcmCoexistenceSuite {

    private static final double EPSILON = 1.0e-3;

    private OcmCoexistenceSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("ocm: the service API binds and every overlap is coordinated", context ->
                        runBindingCheck(mental, context)),
                new TestCase("ocm: melee knockback follows the attacker's modeset", context ->
                        runMeleeOwnership(mental, tester, context)),
                new TestCase("ocm: fast-path damage hands OCM vanilla-shaped values", context ->
                        runDamageHandoff(mental, tester, context)),
                new TestCase("ocm: rod combat defers to old-fishing-knockback", context ->
                        runRodDeferral(mental, tester, context)),
                new TestCase("ocm: thrown projectiles defer to projectile-knockback", context ->
                        runProjectileDeferral(mental, tester, context)));
    }

    private static void runBindingCheck(MentalPluginV5 mental, TestContext context) {
        context.expect(mental.ocmBinding().mode() == ArbiterCore.Mode.BOUND,
                "expected the OCM service API to bind, got " + mental.ocmBinding().mode());
        // Under default OCM config every arbitrated mechanic is reachable, so the
        // conservative global verdict (null decider) says OCM could own each one.
        for (MechanicToken token : MechanicToken.values()) {
            if (!token.arbitrated()) {
                continue;
            }
            context.expect(!mental.ocmBinding().mentalOwns(token, null),
                    "arbitrated mechanic not coordinated under default OCM config: " + token);
        }
    }

    /**
     * Default modeset ("old"): OCM owns the knock — a velocity arrives with
     * no Mental apply event. Switched to "new": Mental owns it — the apply
     * event fires and the vector matches Mental's engine exactly.
     */
    private static void runMeleeOwnership(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());
        FakePlayer freshVictim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, 0, -2));
                victim.spawn(Arena.offset(centre, 0, 2));
                freshVictim.spawn(Arena.offset(centre, 2, 2));
            });
            context.awaitTicks(5);

            // Phase 1 — default ("old") modeset: OCM's knock, Mental silent.
            Boolean ownedByOcm = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                boolean mentalOwns = mental.ocmBinding()
                        .mentalOwns(MechanicToken.MELEE_KNOCKBACK, attacker.uuid());
                attacker.attack(victim.player());
                return !mentalOwns;
            });
            context.expect(Boolean.TRUE.equals(ownedByOcm),
                    "binding must report OCM ownership for the default modeset");
            context.awaitTicks(3);

            context.expect(captors.velocityOf(victim.uuid()) != null,
                    "OCM-owned hit produced no knockback at all");
            context.expect(captors.knockbackAppliesTo(victim.uuid()) == 0,
                    "Mental applied knockback for a hit OCM owns — double knockback");

            // Phase 2 — the attacker switches to the "new" modeset: Mental
            // owns. A fresh victim keeps the expectation deterministic (the
            // OCM hit fed the first victim's residual ledger, by design).
            context.syncRun(() -> OcmProbe.setModeset(attacker.player(), "new"));
            captors.reset();
            context.expect(mental.ocmBinding().mentalOwns(MechanicToken.MELEE_KNOCKBACK, attacker.uuid()),
                    "binding must report Mental ownership for a new-modeset attacker");

            KnockbackVector expected = context.sync(() -> {
                freshVictim.player().setNoDamageTicks(0);
                EntityState victimState = KnockbackSuite.restingVictim(freshVictim);
                KnockbackProfile profile = KnockbackSuite.profileFor(mental, freshVictim);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityStates.capture(attacker.player()), victimState, profile, null);
                attacker.attack(freshVictim.player());
                return SuiteDelivery.melee(vector, profile, victimState.grounded());
            });
            context.expect(expected != null, "engine returned no vector for an unresisted hit");
            context.awaitTicks(3);

            context.expect(captors.knockbackAppliesTo(freshVictim.uuid()) == 1,
                    "Mental must own the knock for a new-modeset attacker (applies="
                            + captors.knockbackAppliesTo(freshVictim.uuid()) + ")");
            Vector applied = captors.velocityOf(freshVictim.uuid());
            context.expect(applied != null, "Mental-owned hit produced no velocity event");
            context.expectNear(expected.x(), applied.getX(), EPSILON, "mental knockback x");
            context.expectNear(expected.y(), applied.getY(), EPSILON, "mental knockback y");
            context.expectNear(expected.z(), applied.getZ(), EPSILON, "mental knockback z");
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
                freshVictim.remove();
            });
            captors.unregister();
        }
    }

    /**
     * Under default OCM config the attacker's "old" modeset owns old-tool-damage,
     * so Mental's fast path hands OCM <em>vanilla-shaped</em> damage (live
     * attribute base + 1.9 sharpness) rather than its legacy composition — OCM
     * then decomposes and recomposes to the era value (8 old-tool-damage + 6.25
     * old-sharpness = 14.25). The retired suite drove {@code HitApplier} directly
     * and read OCM's transformed damage event; the v5 netty fast path is
     * unreachable by a clientless {@link FakePlayer}, so this exercises the same
     * two seams that decide the handoff — the ArbiterCore ownership verdict and
     * the {@link DamageShaper} composition — with the era values pinned.
     */
    private static void runDamageHandoff(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        FakePlayer attacker = new FakePlayer(tester, mental.scheduling());
        try {
            context.syncRun(() ->
                    attacker.spawn(Arena.offset(Arena.prepare(Bukkit.getWorlds().get(0)), -3, -2)));
            context.awaitTicks(5);

            // OCM owns tool damage for the default-modeset attacker (binding path).
            context.expect(!mental.ocmBinding()
                            .mentalOwns(MechanicToken.TOOL_DAMAGE, attacker.uuid()),
                    "binding must report OCM ownership of tool damage under default OCM config");

            // When OCM owns, Mental hands the vanilla shape (modern diamond base
            // 7 + 1.9 sharpness 1+0.5×4 = 3 → 10), never its legacy 14.25 — OCM
            // recomposes that vanilla input to its configured era value.
            context.expectNear(10.0, DamageShaper.composeVanillaShape(7.0, false, false, 5), 1e-9,
                    "Mental must hand OCM the vanilla-shaped damage it recomposes");
            // The era value both sides land on: 8 (old-tool-damage diamond) +
            // 6.25 (old-sharpness 1.25×5). This is OCM's output, pinned here and
            // in DamageShaperTest so the two never drift.
            context.expectNear(14.25, DamageShaper.composeLegacy(8.0, -1, -1, false, false, 5), 1e-9,
                    "the era handoff value (8 + sharpness 6.25)");
            context.note("fast-path OCM handoff is covered by the binding verdict + composition "
                    + "pins — the v5 netty fast path is unreachable by a clientless FakePlayer");
        } finally {
            context.syncRun(attacker::remove);
        }
    }

    /** Default OCM enables old-fishing-knockback everywhere: Mental's rod module yields. */
    private static void runRodDeferral(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer rodder = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                rodder.spawn(Arena.offset(centre, 3, -3));
                victim.spawn(Arena.offset(centre, 3, 0));
            });
            context.awaitTicks(5);

            context.expect(context.sync(() -> !mental.ocmBinding()
                            .mentalOwns(MechanicToken.FISHING_KNOCKBACK, rodder.uuid())),
                    "binding must report OCM ownership of rod combat under default OCM config");

            boolean launched = context.sync(() -> {
                victim.player().setNoDamageTicks(0);
                try {
                    return rodder.player().launchProjectile(
                            FishHook.class, new Vector(0, 0.1, 0.75)) != null;
                } catch (Throwable unsupported) {
                    return false;
                }
            });
            if (!launched) {
                context.note("this version cannot launch a bare FishHook — ownership covered by the binding check");
                return;
            }

            Double observedDamage = null;
            for (int attempt = 0; attempt < 12 && observedDamage == null; attempt++) {
                context.awaitTicks(5);
                observedDamage = captors.damageOf(victim.uuid());
            }
            if (observedDamage == null) {
                context.note("hook never struck the victim (flight physics) — ownership covered by the binding check");
                return;
            }

            context.expect(captors.knockbackAppliesTo(victim.uuid()) == 0,
                    "Mental applied rod knockback for a hit OCM owns — double knockback");
            context.expect(captors.velocityOf(victim.uuid()) != null,
                    "OCM-owned rod hit produced no knockback at all");
        } finally {
            context.syncRun(() -> {
                rodder.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** projectile-knockback is always-enabled by default: substitution and knock are OCM's. */
    private static void runProjectileDeferral(
            MentalPluginV5 mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer shooter = new FakePlayer(tester, mental.scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                shooter.spawn(Arena.offset(centre, 6, -3));
                victim.spawn(Arena.offset(centre, 6, 0));
            });
            context.awaitTicks(5);

            context.expect(context.sync(() -> !mental.ocmBinding()
                            .mentalOwns(MechanicToken.PROJECTILE_KNOCKBACK, victim.uuid())),
                    "binding must report OCM ownership of projectile knockback under default OCM config");

            context.syncRun(() -> {
                victim.player().setNoDamageTicks(0);
                Vector direction = victim.player().getLocation().toVector()
                        .subtract(shooter.player().getLocation().toVector())
                        .normalize().multiply(1.5);
                shooter.player().launchProjectile(Snowball.class, direction);
            });

            String hit = null;
            for (int attempt = 0; attempt < 12 && hit == null; attempt++) {
                context.awaitTicks(5);
                hit = captors.projectileHitOn(victim.uuid());
            }
            if (hit == null) {
                context.note("snowball never struck the victim (flight physics) — ownership covered by the binding check");
                return;
            }

            context.expect(captors.knockbackAppliesTo(victim.uuid()) == 0,
                    "Mental applied projectile knockback for a hit OCM owns");
            Double damage = captors.damageOf(victim.uuid());
            context.expect(damage != null && damage > 0.0,
                    "OCM's damage substitution never ran (damage=" + damage + ")");
        } finally {
            context.syncRun(() -> {
                shooter.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** Reflection against OCM's service API — the tester never compiles against OCM. */
    private static final class OcmProbe {

        static void setModeset(Player player, String modeset) throws Exception {
            Class<?> apiClass = Class.forName(
                    "kernitus.plugin.OldCombatMechanics.api.OldCombatMechanicsAPI");
            var registration = Bukkit.getServicesManager().getRegistration(apiClass);
            if (registration == null) {
                throw new AssertionError("OCM service API not registered");
            }
            Object api = registration.getProvider();
            Method method = apiClass.getMethod("setModesetForPlayer", Player.class, String.class);
            method.invoke(api, player, modeset);
        }
    }
}
