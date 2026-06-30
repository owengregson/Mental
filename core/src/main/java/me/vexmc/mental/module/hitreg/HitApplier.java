package me.vexmc.mental.module.hitreg;

import java.util.UUID;
import java.util.function.Consumer;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.module.damage.WeaponDurability;
import me.vexmc.mental.module.ocm.OcmMechanic;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Owning-thread damage application for the fast path.
 *
 * <p>The cancelled attack packet means vanilla never runs
 * {@code Player#attack}; this re-resolves both parties, re-validates against
 * fresh state, and drives the hit through Bukkit's {@code damage(amount,
 * attacker)} — which fires the full event chain (damage events, armor,
 * invulnerability, vanilla knockback, hurt feedback) exactly as a vanilla hit
 * would. Sweep, statistics and hunger are deliberate omissions for the 1.7.10
 * target feel; weapon durability is omitted by default but restored when the
 * {@code old-tool-durability} module is on (vanilla's attack-time durability
 * never runs because the attack packet was cancelled).</p>
 */
public final class HitApplier {

    private static final double VANILLA_REACH = 6.0;
    private static final double REACH_LENIENCY = 1.0;
    private static final double MAX_REACH_SQUARED =
            (VANILLA_REACH + REACH_LENIENCY) * (VANILLA_REACH + REACH_LENIENCY);

    private final MentalServices services;

    public HitApplier(@NotNull MentalServices services) {
        this.services = services;
    }

    public void apply(@NotNull UUID attackerUuid, int targetEntityId) {
        Player attacker = Bukkit.getPlayer(attackerUuid);
        if (attacker == null || !attacker.isOnline() || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Entity entity = lookupEntity(attacker, targetEntityId);
        if (!(entity instanceof Damageable damageable)
                || damageable.isDead()
                || !isStillAttackable(attacker, damageable)
                || !isInReach(attacker, damageable)) {
            return;
        }

        damage(attacker, damageable);
    }

    /**
     * Player-vs-player application for the Folia-safe fast path: both parties
     * resolve by UUID ({@code Bukkit#getPlayer} is region-agnostic, unlike a
     * {@code World#getEntities()} scan, which throws off-region on Folia), so no
     * entity-id scan is needed. Runs on the victim's owning region thread (the
     * region that owns the damage event); for melee the attacker shares that
     * region in essentially all real play, so reading the attacker's state here
     * is region-correct.</p>
     *
     * <p>The exception is a region-boundary straddle, or an attacker that
     * pearled/teleported across a region between the netty snapshot and this
     * deferred task: the attacker reads here — {@code getGameMode}, the
     * attribute/enchant reads in {@code DamageCalculator}, and the
     * knockback-direction read inside {@code damage(amount, attacker)} — then
     * throw {@code ensureTickThread} off the victim's region. A cross-region
     * attacker is no longer within reach, so {@link #applyGuarded} drops the hit
     * with a logged skip rather than letting the scheduler surface an uncaught
     * throw; on Paper there are no regions, so any throw propagates unmasked.</p>
     */
    public void applyPlayer(@NotNull UUID attackerUuid, @NotNull UUID victimUuid) {
        applyGuarded(
                services.capabilities().folia(),
                () -> {
                    Player attacker = Bukkit.getPlayer(attackerUuid);
                    if (attacker == null
                            || !attacker.isOnline()
                            || attacker.getGameMode() == GameMode.SPECTATOR) {
                        return;
                    }
                    Player victim = Bukkit.getPlayer(victimUuid);
                    if (victim == null
                            || victim.isDead()
                            || !isStillAttackable(attacker, victim)
                            || !isInReach(attacker, victim)) {
                        return;
                    }
                    damage(attacker, victim);
                },
                crossRegion -> services.debug().log(DebugCategory.KNOCKBACK,
                        () -> "skipped cross-region melee apply for attacker " + attackerUuid
                                + ": " + crossRegion));
    }

    /**
     * Runs the resolve-validate-damage body, degrading a cross-region failure to
     * a logged skip on Folia. See {@link #applyPlayer} for why the attacker reads
     * can throw off the victim's region thread; a cross-region attacker is no
     * longer within reach, so the era-correct outcome is to drop the hit. On
     * Paper there are no regions, so a throw is a genuine bug and propagates
     * unmasked — byte-identical to the pre-guard code.
     */
    static void applyGuarded(
            boolean folia, @NotNull Runnable body, @NotNull Consumer<RuntimeException> onSkip) {
        try {
            body.run();
        } catch (RuntimeException crossRegionOrBug) {
            if (!folia) {
                throw crossRegionOrBug;
            }
            onSkip.accept(crossRegionOrBug);
        }
    }

    /**
     * Drives the resolved-and-validated hit through Bukkit's damage pipeline —
     * the shared tail of {@link #apply} and {@link #applyPlayer}.
     */
    private void damage(@NotNull Player attacker, @NotNull Damageable damageable) {
        var settings = services.config().hitReg();
        // Where OCM's damage modules govern this attacker, hand the hurt
        // pipeline vanilla-shaped damage: OCM decomposes the event into
        // vanilla components and substitutes its configured values, so it —
        // not Mental — is the damage model for the hit.
        boolean ocmShapesDamage =
                services.ocmGate().handles(OcmMechanic.TOOL_DAMAGE, attacker)
                || services.ocmGate().handles(OcmMechanic.CRITICAL_HITS, attacker);
        // Era Strength/Weakness damage VALUES (1.8: Strength ×3.5, Weakness −2)
        // apply to the weapon base before crit. Gated by old-potion-values and
        // skipped entirely when OCM shapes the damage (vanillaShape path).
        boolean oldPotionValues = services.config().potionValues().enabled();
        double amount = damageable instanceof LivingEntity living
                ? DamageCalculator.calculate(
                        attacker, living, settings.simulateCrits(), settings.legacyToolDamage(),
                        ocmShapesDamage, oldPotionValues)
                : 1.0;

        damageable.damage(amount, attacker);

        // The cancelled attack packet means vanilla's attack-time weapon
        // durability never ran (it lives in Player#attack). When the
        // old-tool-durability module is on, re-add it: 1 durability per accepted
        // hit (era 1.7→modern attack case), standard Unbreaking skip. The write
        // is the attacker's entity state, so it must land on the attacker's
        // owning region thread — mirror the sprint-clear (KnockbackModule
        // .clearVanillaSprint uses the same services.scheduling().runOn(attacker)).
        if (services.config().toolDurability().enabled()) {
            applyWeaponDurability(attacker);
        }
    }

    /**
     * Damages the attacker's main-hand weapon by 1 (Unbreaking-modified),
     * breaking it like vanilla when its durability is exhausted. The entire
     * read-roll-write runs on the attacker's owning region thread via
     * {@code Scheduling.runOn} — the same Folia-correct attacker-write the fast
     * path uses for its sprint-flag clear — so no attacker-entity state is
     * touched off-thread. The main-hand item is re-read inside the task because
     * it may have changed between the hit and the deferred task on Folia. The
     * Bukkit shell lives in {@link WeaponDurability} (which imports the meta
     * {@code Damageable} cleanly, away from the entity {@code Damageable} this
     * file uses for the target).
     */
    private void applyWeaponDurability(@NotNull Player attacker) {
        services.scheduling().runOn(
                attacker,
                () -> WeaponDurability.applyOneHit(attacker, services.debug()),
                () -> {});
    }

    /**
     * Bukkit has no public {@code World#getEntity(int)}; a bounded scan of the
     * attacker's world on the owning thread is the portable lookup, and the
     * path is already CPS-gated.
     */
    private static @Nullable Entity lookupEntity(Player attacker, int entityId) {
        for (Entity entity : attacker.getWorld().getEntities()) {
            if (entity.getEntityId() == entityId) {
                return entity;
            }
        }
        return null;
    }

    private static boolean isStillAttackable(Player attacker, Damageable target) {
        if (attacker.getWorld() != target.getWorld()) {
            return false;
        }
        if (target instanceof Player victim) {
            return victim.getGameMode() != GameMode.CREATIVE
                    && victim.getGameMode() != GameMode.SPECTATOR
                    && attacker.getWorld().getPVP();
        }
        return true;
    }

    private static boolean isInReach(Player attacker, Damageable target) {
        double dx = attacker.getLocation().getX() - target.getLocation().getX();
        double dy = attacker.getLocation().getY() - target.getLocation().getY();
        double dz = attacker.getLocation().getZ() - target.getLocation().getZ();
        return dx * dx + dy * dy + dz * dz <= MAX_REACH_SQUARED;
    }
}
