package me.vexmc.mental.tester.suite;

import java.lang.reflect.Method;
import java.util.List;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.module.hitreg.HitApplier;
import me.vexmc.mental.module.knockback.EntityState;
import me.vexmc.mental.module.knockback.KnockbackEngine;
import me.vexmc.mental.module.knockback.KnockbackVector;
import me.vexmc.mental.module.ocm.OcmGate;
import me.vexmc.mental.module.ocm.OcmMechanic;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Enchantments;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.Captors;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
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
 */
public final class OcmCoexistenceSuite {

    private static final double EPSILON = 1.0e-3;

    private OcmCoexistenceSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
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

    private static void runBindingCheck(MentalPlugin mental, TestContext context) {
        OcmGate gate = mental.services().ocmGate();
        context.expect(gate.mode() == OcmGate.Mode.BOUND,
                "expected the OCM service API to bind, got " + gate.mode());
        for (OcmMechanic mechanic : OcmMechanic.values()) {
            context.expect(gate.coordinated().contains(mechanic),
                    "mechanic not coordinated under default OCM config: " + mechanic);
        }
    }

    /**
     * Default modeset ("old"): OCM owns the knock — a velocity arrives with
     * no Mental apply event. Switched to "new": Mental owns it — the apply
     * event fires and the vector matches Mental's engine exactly.
     */
    private static void runMeleeOwnership(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer freshVictim = new FakePlayer(tester, mental.services().scheduling());

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
                boolean handled = mental.services().ocmGate()
                        .handles(OcmMechanic.MELEE_KNOCKBACK, attacker.player());
                attacker.attack(victim.player());
                return handled;
            });
            context.expect(Boolean.TRUE.equals(ownedByOcm),
                    "gate must report OCM ownership for the default modeset");
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

            KnockbackVector expected = context.sync(() -> {
                freshVictim.player().setNoDamageTicks(0);
                KnockbackVector vector = KnockbackEngine.compute(
                        EntityState.capture(attacker.player()),
                        KnockbackSuite.restingVictim(freshVictim),
                        mental.services().knockbackProfiles().resolve(freshVictim.player()), null);
                attacker.attack(freshVictim.player());
                return vector;
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
     * A sharpness-5 diamond sword through Mental's fast-path applier must
     * come out of OCM's decompose-replace-recompose machinery at exactly the
     * era value: 8 (old-tool-damage) + 6.25 (old-sharpness 1.25/level) =
     * 14.25. Legacy-composed input would decompose wrongly and land at 17.5.
     */
    private static void runDamageHandoff(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer attacker = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                attacker.spawn(Arena.offset(centre, -3, -2));
                victim.spawn(Arena.offset(centre, -3, 2));
            });
            context.awaitTicks(5);

            Boolean armed = context.sync(() -> {
                ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
                Enchantment sharpness =
                        Enchantments.sharpness();
                if (sharpness == null) {
                    return false;
                }
                sword.addUnsafeEnchantment(sharpness, 5);
                attacker.player().getInventory().setItemInMainHand(sword);
                // Synthetic players skip vanilla's per-tick equipment and
                // attack-strength bookkeeping; pin what a real ticking player
                // holding this sword would read — the attribute carrying the
                // weapon's damage, and a full attack charge (the fast path
                // leaves real attackers at full charge by construction).
                Attribute damageAttribute =
                        Attributes.attackDamage();
                Attribute speedAttribute =
                        Attributes.attackSpeed();
                if (damageAttribute == null || speedAttribute == null) {
                    return false;
                }
                var attackDamage = attacker.player().getAttribute(damageAttribute);
                var attackSpeed = attacker.player().getAttribute(speedAttribute);
                if (attackDamage == null || attackSpeed == null) {
                    return false;
                }
                attackDamage.setBaseValue(7.0);
                attackSpeed.setBaseValue(40.0);
                victim.player().setNoDamageTicks(0);
                victim.player().setHealth(20.0);
                new HitApplier(mental.services()).apply(
                        attacker.uuid(), victim.player().getEntityId());
                return true;
            });
            if (!Boolean.TRUE.equals(armed)) {
                context.note("sharpness enchantment unresolvable — damage handoff covered by unit tests");
                return;
            }
            context.awaitTicks(3);

            Double damage = captors.damageOf(victim.uuid());
            context.expect(damage != null, "fast-path hit never produced a damage event");
            context.expectNear(14.25, damage, 1.0e-6,
                    "OCM-transformed fast-path damage (8 + sharpness 6.25)");
        } finally {
            context.syncRun(() -> {
                attacker.remove();
                victim.remove();
            });
            captors.unregister();
        }
    }

    /** Default OCM enables old-fishing-knockback everywhere: Mental's rod module yields. */
    private static void runRodDeferral(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer rodder = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                rodder.spawn(Arena.offset(centre, 3, -3));
                victim.spawn(Arena.offset(centre, 3, 0));
            });
            context.awaitTicks(5);

            context.expect(context.sync(() -> mental.services().ocmGate()
                            .handles(OcmMechanic.FISHING_KNOCKBACK, rodder.player())),
                    "gate must report OCM ownership of rod combat under default OCM config");

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
                context.note("this version cannot launch a bare FishHook — ownership covered by the gate check");
                return;
            }

            Double observedDamage = null;
            for (int attempt = 0; attempt < 12 && observedDamage == null; attempt++) {
                context.awaitTicks(5);
                observedDamage = captors.damageOf(victim.uuid());
            }
            if (observedDamage == null) {
                context.note("hook never struck the victim (flight physics) — ownership covered by the gate check");
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
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        Captors captors = Captors.register(tester);
        FakePlayer shooter = new FakePlayer(tester, mental.services().scheduling());
        FakePlayer victim = new FakePlayer(tester, mental.services().scheduling());

        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                shooter.spawn(Arena.offset(centre, 6, -3));
                victim.spawn(Arena.offset(centre, 6, 0));
            });
            context.awaitTicks(5);

            context.expect(context.sync(() -> mental.services().ocmGate()
                            .handles(OcmMechanic.PROJECTILE_KNOCKBACK, victim.player())),
                    "gate must report OCM ownership of projectile knockback under default OCM config");

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
                context.note("snowball never struck the victim (flight physics) — ownership covered by the gate check");
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
