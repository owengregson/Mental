package me.vexmc.mental.v5.feature.damage;

import java.util.function.ToIntFunction;
import me.vexmc.mental.kernel.math.Ct8cShieldMath;
import me.vexmc.mental.platform.Cooldowns;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Combat Test 8c shields ({@code ct8c-shields}, design spec §2.6), driven entirely
 * through Bukkit damage events + the Cooldowns API (zero-touch when disabled). Five
 * code-verified pieces the kernel {@link Ct8cShieldMath} sizes:
 *
 * <ul>
 *   <li><b>148° arc.</b> Vanilla blocks a 180° frontal cone; CT8c narrows it to
 *       ≈148° ({@code dot(view, attacker→victim)·π < −0.8726646}). A hit inside the
 *       cone is blocked; a hit vanilla blocked but CT8c would NOT (the 148°–180°
 *       margin) has its vanilla block withdrawn so the full hit lands.</li>
 *   <li><b>5-cap passthrough.</b> A blocked melee hit absorbs at most {@code 5.0}
 *       (hardcoded — no {@code shield_strength} attribute); the excess passes
 *       through. Crits do not bypass; the cap applies to whatever damage composed.</li>
 *   <li><b>Instant + crouch-to-shield.</b> An offhand {@code SHIELD} blocks while
 *       {@code onGround && sneaking} (or riding), with no right-click and no 5-tick
 *       use gate — the raised-shield pose is a client asset and is never animated
 *       server-side (spec §5 gap).</li>
 *   <li><b>0.5 knockback resistance while blocking</b> ({@link Ct8cShieldResistance})
 *       — coordinates with the ct8c profile's {@code shield-blocking-cancels:false},
 *       so a blocked hit is knock-reduced, not knock-cancelled.</li>
 *   <li><b>Axe interactions.</b> An axe hit on a blocking shield always disables it
 *       for {@code 32 + 10·Cleaving} ticks (via the item-cooldown API), and an axe
 *       attack costs 1 durability, not vanilla's 2.</li>
 * </ul>
 *
 * <p>Banner shields are intentionally NOT implemented (spec §5, owner decision).
 * Projectile/explosion blocking stays vanilla's 100% (only melee is capped); the
 * crouch-to-shield model is the CT8c-canonical blocking gesture the resistance
 * lever tracks. This unit assumes classic {@code sword-blocking} is off (the
 * bundles arbitrate the two — spec §3.3).</p>
 *
 * <p><b>Cleaving.</b> The shield-disable scaling reads the axe's Cleaving level
 * through the injected {@code cleavingLevelOf} lookup (Task D's platform
 * {@code CleavingHandle}). The no-arg constructor defaults it to level 0 — the
 * correct degrade below Cleaving's registry floor (~1.21.3, spec §4), where the
 * disable is the flat 32-tick base.</p>
 */
public final class Ct8cShieldUnit implements FeatureUnit, Listener {

    /** The axe Cleaving-level lookup (Task D's {@code CleavingHandle}); {@code →0} where Cleaving is absent. */
    private final ToIntFunction<ItemStack> cleavingLevelOf;

    /** The 0.5-knockback-resistance lever granted while a player holds the crouch-to-shield posture. */
    private final Ct8cShieldResistance resistance = new Ct8cShieldResistance();

    /**
     * The production constructor: cleaving defaults to level 0 (the correct degrade
     * below the enchant's registry floor). Task D wires the real
     * {@code CleavingHandle.levelOf} through the {@link ToIntFunction} constructor at
     * merge, so the +10-ticks/level disable scaling lands on 1.21.3+.
     */
    public Ct8cShieldUnit() {
        this(stack -> 0);
    }

    public Ct8cShieldUnit(@NotNull ToIntFunction<ItemStack> cleavingLevelOf) {
        this.cleavingLevelOf = cleavingLevelOf;
    }

    @Override
    public Feature descriptor() {
        return Feature.CT8C_SHIELDS;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
        // Guaranteed teardown of the resistance lever on disable (B12): a disabled
        // feature does nothing to the game, so every granted modifier is swept.
        scope.task(() -> resistance::stripAll);
    }

    /* ------------------------------------------------------------------ */
    /*  The block (arc + cap + axe disable)                                */
    /* ------------------------------------------------------------------ */

    /**
     * Reshapes a melee hit against a blocking CT8c shield. Runs at {@link
     * EventPriority#HIGH} (after the damage is composed, before the MONITOR knockback
     * read) over the victim's live view + the attacker's live position — the exact
     * state vanilla's own {@code isDamageSourceBlocked} reads at hit time, so a
     * fast-path hit (fired synchronously by {@code victim.damage()}) and a vanilla
     * hit see the same era-moment geometry.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(@NotNull EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getEntity() instanceof Player victim)
                || !(event.getDamager() instanceof LivingEntity attacker)) {
            return; // melee, player victim only — projectiles/explosions keep vanilla's 100% block
        }
        boolean nativeBlock = vanillaBlocking(event);
        boolean crouchShield = crouchToShieldBlocking(victim);
        if (!nativeBlock && !crouchShield) {
            return; // not blocking — leave the hit untouched (zero-touch)
        }

        boolean withinArc = blocksHit(
                victim.getEyeLocation().getDirection(),
                victim.getLocation().toVector().subtract(attacker.getLocation().toVector()));
        if (!withinArc) {
            // Outside CT8c's 148° cone. Vanilla's wider 180° block (if any) must be
            // withdrawn so the full hit lands; a crouch-shield emulation blocked
            // nothing yet, so there is nothing to undo.
            withdrawVanillaBlock(event);
            return;
        }

        applyMeleeCap(event);
        resistance.apply(victim); // belt: ensure the 0.5 KB-resist lever is present for this blocked hit
        disableOnAxe(victim, attacker);
    }

    /**
     * Reduces an axe attack's durability cost from vanilla's 2 to CT8c's 1 (spec §2.6),
     * by cancelling the second point. Attacking with an axe fires this event with a
     * damage of 2 (a {@code DiggerItem} weapon hit); block-breaking already costs 1,
     * so the {@code == 2} guard isolates the attack case and is self-correcting where
     * a modern runtime already charges 1.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemDamage(@NotNull PlayerItemDamageEvent event) {
        if (isAxe(event.getItem().getType()) && event.getDamage() == 2) {
            event.setDamage(1);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  The crouch-to-shield posture (the 0.5 KB-resist lifecycle)         */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSneak(@NotNull PlayerToggleSneakEvent event) {
        // The event's sneaking() is the POST-toggle state — reevaluate with it, since
        // player.isSneaking() still reads the pre-toggle value at this instant.
        reevaluatePosture(event.getPlayer(), event.isSneaking());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        // The offhand shield may have just moved — reevaluate the posture.
        reevaluatePosture(event.getPlayer(), event.getPlayer().isSneaking());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        resistance.remove(event.getPlayer()); // sweep any modifier a crash leaked into saved NBT
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        resistance.remove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        resistance.remove(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        resistance.remove(event.getPlayer());
    }

    /** Applies the resistance lever when the crouch-to-shield posture holds, strips it otherwise. */
    private void reevaluatePosture(@NotNull Player player, boolean sneaking) {
        if (isBlockingPosture(player, sneaking)) {
            resistance.apply(player);
        } else {
            resistance.remove(player);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Pure, event-shaped seams (unit-pinned)                             */
    /* ------------------------------------------------------------------ */

    /**
     * Whether a hit falls inside the CT8c 148° block cone, from the victim's view
     * direction and the attacker→victim vector. Mirrors vanilla
     * {@code isDamageSourceBlocked}: the push is flattened to the horizontal plane
     * and normalized (the shield is a yaw cone, pitch-blind), dotted with the view,
     * scaled by π, and tested against {@link Ct8cShieldMath#ARC_DOT_LIMIT}.
     */
    static boolean blocksHit(@NotNull Vector viewDirection, @NotNull Vector attackerToVictim) {
        return Ct8cShieldMath.withinArc(arcDotTimesPi(viewDirection, attackerToVictim));
    }

    static double arcDotTimesPi(@NotNull Vector viewDirection, @NotNull Vector attackerToVictim) {
        Vector push = new Vector(attackerToVictim.getX(), 0.0, attackerToVictim.getZ());
        if (push.lengthSquared() < 1.0e-9 || viewDirection.lengthSquared() < 1.0e-9) {
            return 0.0; // degenerate geometry (attacker directly above/below) ⇒ not blocked
        }
        return viewDirection.dot(push.normalize()) * Math.PI;
    }

    /**
     * Applies the hardcoded 5-damage cap with passthrough and returns the absorbed
     * portion. When vanilla already carries a BLOCKING modifier the reduction is
     * written there (so the excess lands through the shield); the crouch-to-shield
     * case (no vanilla block) reduces the final damage directly.
     */
    @SuppressWarnings("deprecation") // the granular BLOCKING modifier is Bukkit's only shield-absorption lever
    static double applyMeleeCap(@NotNull EntityDamageByEntityEvent event) {
        double incoming = event.getDamage();
        double blocked = Ct8cShieldMath.blockedPortion(incoming);
        if (event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, -blocked);
        } else {
            event.setDamage(Math.max(0.0, incoming - blocked));
        }
        return blocked;
    }

    /** The axe-hit shield-disable duration for a Cleaving level: {@code 32 + 10·level} ticks (spec §2.6/§2.9). */
    static int shieldDisableTicks(int cleavingLevel) {
        return Ct8cShieldMath.axeDisableTicks(Math.max(0, cleavingLevel));
    }

    /** Whether a material is an axe (a {@code _AXE} item — never a {@code _PICKAXE}, which does not end {@code _AXE}). */
    static boolean isAxe(@NotNull Material material) {
        return material.name().endsWith("_AXE");
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers (live reads — the era-moment state, per the class doc)     */
    /* ------------------------------------------------------------------ */

    /** Whether vanilla already applied a shield block to this hit (a negative BLOCKING modifier). */
    @SuppressWarnings("deprecation")
    private static boolean vanillaBlocking(@NotNull EntityDamageByEntityEvent event) {
        return event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0.0;
    }

    /** Withdraws vanilla's block (zeroes the BLOCKING modifier) so an out-of-arc hit lands in full. */
    @SuppressWarnings("deprecation")
    private static void withdrawVanillaBlock(@NotNull EntityDamageByEntityEvent event) {
        if (event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0.0);
        }
    }

    /**
     * The crouch-to-shield block (spec §2.6): an offhand {@code SHIELD} while
     * {@code onGround && sneaking} (or riding) — no right-click, no use-duration gate.
     * 8c disabled blocking while jumping, which the {@code onGround} term encodes.
     */
    private static boolean crouchToShieldBlocking(@NotNull Player victim) {
        return isBlockingPosture(victim, victim.isSneaking());
    }

    @SuppressWarnings("deprecation") // the client-reported onGround flag is the one 8c's block gate uses
    private static boolean isBlockingPosture(@NotNull Player player, boolean sneaking) {
        boolean grounded = (player.isOnGround() && sneaking) || player.isInsideVehicle();
        if (!grounded) {
            return false;
        }
        EntityEquipment equipment = player.getEquipment();
        return equipment != null && equipment.getItemInOffHand().getType() == Material.SHIELD;
    }

    /**
     * An axe hit on a blocking shield always disables it (spec §2.6) for {@code 32 +
     * 10·Cleaving} ticks, through the victim's SHIELD item cooldown. A loud no-op
     * below 1.11 (no item-cooldown API — {@link Cooldowns}); the disable there is the
     * era-native absence rather than a {@code NoSuchMethodError}.
     */
    private void disableOnAxe(@NotNull Player victim, @NotNull LivingEntity attacker) {
        if (!(attacker instanceof Player player) || !Cooldowns.itemCooldownSupported()) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!isAxe(weapon.getType())) {
            return;
        }
        victim.setCooldown(Material.SHIELD, shieldDisableTicks(cleavingLevelOf.applyAsInt(weapon)));
    }
}
