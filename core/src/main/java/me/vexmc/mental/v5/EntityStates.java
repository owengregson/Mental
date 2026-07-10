package me.vexmc.mental.v5;

import me.vexmc.mental.kernel.ledger.MotionLedger;
import me.vexmc.mental.kernel.math.Decay;
import me.vexmc.mental.kernel.math.MeasuredReality;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * The Bukkit → kernel {@link EntityState} capture seam (the retired
 * {@code module.knockback.EntityState.capture}/{@code captureVictim}, restored
 * in core where Bukkit is available; the kernel record itself is Bukkit-free).
 *
 * <p>Captures run on the entity's owning thread. A knockback <em>victim</em>'s
 * motion comes from the single-writer {@link MotionLedger} residual — the legacy
 * server-fields model — because the server's own view of a player's motion is
 * reverted or stale on every supported version; a mob's live velocity is already
 * the legacy-correct input.</p>
 */
public final class EntityStates {

    private EntityStates() {}

    /** Live capture with the live sprint flag. */
    public static EntityState capture(LivingEntity entity) {
        return capture(entity, entity instanceof Player player && player.isSprinting());
    }

    /**
     * Live capture with an explicit sprint flag — the flag the ATTACK saw. Vanilla
     * read it inside {@code Player.attack}, ahead of the client's own post-attack
     * sprint-drop sync, so a fast-path deferred read would lose that race.
     */
    @SuppressWarnings("deprecation") // Entity#isOnGround: the client-reported value drives air multipliers
    public static EntityState capture(LivingEntity entity, boolean sprinting) {
        Location location = entity.getLocation();
        Vector velocity = entity.getVelocity();
        return new EntityState(
                location.getX(), location.getY(), location.getZ(), location.getYaw(),
                velocity.getX(), velocity.getY(), velocity.getZ(),
                entity.isOnGround(), sprinting,
                heldKnockbackLevel(entity), clampedResistance(entity),
                moveSpeedAttr(entity));
    }

    /**
     * The attacker's WALK-STANCE-NORMALIZED movement-speed attribute for
     * speed-conformal knockback — {@link Attributes#movementSpeedWalkNormalized}
     * reads {@code isSprinting()} and the effective value back-to-back on this
     * (owning) thread and divides the sprint modifier back out, so the pace
     * factor is immune to wire-vs-server stance disagreement (F1). Present 1.9+;
     * resolves to {@link EntityState#MOVE_SPEED_UNAVAILABLE} below the attribute
     * API, which pace scaling falls back to the walk baseline (factor 1.0).
     */
    private static double moveSpeedAttr(LivingEntity entity) {
        return Attributes.movementSpeedWalkNormalized(entity);
    }

    /**
     * Captures a knockback victim with no measured-vy override — the packetless /
     * suite path. Delegates with {@link Double#NaN}, so the measured-reality clamp
     * is a strict no-op and this stays byte-identical to the pre-fix capture (every
     * existing suite calls this arity; its staged ledger free-fall is untouched).
     */
    public static EntityState captureVictim(LivingEntity victim, MotionLedger ledger) {
        return captureVictim(victim, ledger, Double.NaN);
    }

    /**
     * Captures a knockback victim. A player's motion is the {@link MotionLedger}
     * residual (owning-thread read of the single-writer ledger); grounded is the
     * live client-reported flag (the air-multiplier branch). Mobs fall back to a
     * live capture.
     *
     * <p>The vertical residual is bounded below by {@code measuredVy} (the victim's
     * published per-tick position Δy) via {@link MeasuredReality#clampVy}: the ledger
     * models the era server's motY field, but its runaway free-fall past the real
     * hover is a MODEL bug that ships downward hit-2 knocks on juggled victims (the
     * ledger-vy divergence; 2026-07-10-downward-kb-and-stacking-diagnoses.md report 1,
     * root cause in 2026-07-07-ledger-vy-divergence.md). This must stay bit-identical
     * to its pre-send twin ({@code HitRegistrationUnit.preVictimState}), which reads
     * the same clamp off the same view. {@link Double#NaN} ⇒ no fresh measurement ⇒
     * the residual passes through unchanged. Horizontal residual is never touched.</p>
     */
    @SuppressWarnings("deprecation") // Entity#isOnGround: the client-reported value drives the residual decay
    public static EntityState captureVictim(LivingEntity victim, MotionLedger ledger, double measuredVy) {
        if (!(victim instanceof Player player)) {
            return capture(victim);
        }
        boolean grounded = player.isOnGround();
        Decay.Motion motion = ledger.current();
        Location location = player.getLocation();
        return new EntityState(
                location.getX(), location.getY(), location.getZ(), location.getYaw(),
                motion.vx(), MeasuredReality.clampVy(motion.vy(), measuredVy), motion.vz(),
                grounded, player.isSprinting(),
                heldKnockbackLevel(player), clampedResistance(player));
    }

    /**
     * The held Knockback enchant level (main-hand, else off-hand). Public for the
     * {@code SessionService} view freeze — the per-tick owning-thread read that lets
     * the netty pre-send carry the enchant extra ({@link
     * me.vexmc.mental.kernel.model.PlayerView#kbEnchantLevel()}). Present 1.9+;
     * {@code Enchantment.KNOCKBACK} kept its name across the whole range (the
     * platform seam's own doc), and this exact code already runs on every matrix
     * entry via the live captures, so the freeze adds no cross-version surface.
     */
    public static int heldKnockbackLevel(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return 0;
        }
        ItemStack weapon = equipment.getItemInMainHand().getType() == Material.AIR
                ? equipment.getItemInOffHand()
                : equipment.getItemInMainHand();
        if (weapon == null || weapon.getType() == Material.AIR) {
            return 0;
        }
        return weapon.getEnchantmentLevel(Enchantments.knockback());
    }

    private static double clampedResistance(LivingEntity entity) {
        double value = Attributes.valueOr(entity, Attributes.knockbackResistance(), 0.0);
        return Math.max(0.0, Math.min(1.0, value));
    }
}
