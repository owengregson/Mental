package me.vexmc.mental.module.damage;

import java.util.concurrent.ThreadLocalRandom;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Restores the 1.8 armour-durability Unbreaking skip, so worn armour survives
 * more hits — the era "armour lasts longer" feel.
 *
 * <h2>What this overrides</h2>
 * <p>The per-hit durability <em>magnitude</em> is byte-identical 1.8↔modern
 * ({@code max(1, floor(eventDamage/4))} per worn piece — {@code wm.java:370-380},
 * ground-truth doc §4), so this module never changes it: {@code e.getDamage()} is
 * left untouched on a wear hit (OCM's flat-1 override is <em>not</em> era-exact —
 * we deliberately do not copy it). The only era difference is the Unbreaking
 * <em>skip</em> probability: 1.8 armour uses {@code 60 + 40/(level+1)}% to take
 * wear ({@code acg.java}) where modern armour uses the generic
 * {@code level/(level+1)}. On a skip the whole event is suppressed with
 * {@code setDamage(0)}. The probability math lives in {@link ArmourDurabilityMath}
 * (which documents the OCM-mirrored direction and the level-0 = never-skip
 * behaviour).</p>
 *
 * <h2>Why {@link PlayerItemDamageEvent}</h2>
 * <p>This is the only cross-version hook that fires once per worn piece per hit
 * with the vanilla wear amount in hand, and it is exactly OCM's hook. We act only
 * when the damaged item is a piece the player is currently wearing (compared
 * against {@code getArmorContents()}), excluding the elytra (it provides no
 * protection, so its wear is not era armour wear).</p>
 *
 * <h2>Explosion-suppression: deliberately omitted</h2>
 * <p>OCM additionally dedups armour-wear events that fire right after an
 * explosion damage event, using a global Bukkit scheduler timer plus a shared
 * {@code HashMap}. That machinery is not Folia-safe and only smooths an edge-case
 * double-wear during explosion bursts; it is not part of the era skip-probability
 * mechanic. We omit it rather than reach for any global/Bukkit scheduler — see
 * the Mental no-global-scheduler invariant. The per-hit roll is independent, so
 * omitting the dedup costs at most a slightly higher wear rate during the rare
 * multi-piece explosion frame.</p>
 *
 * <h2>Threading (Folia)</h2>
 * <p>{@link PlayerItemDamageEvent} fires on the player's region thread, so the
 * equipment read and {@code setDamage} are safe inline — no scheduling hop.</p>
 *
 * <h2>Zero-touch</h2>
 * <p>When disabled (the default), this module registers no listeners and leaves
 * vanilla armour durability completely untouched.</p>
 */
public final class ArmourDurabilityModule extends CombatModule implements Listener {

    /**
     * Unbreaking, resolved cross-version (modern {@code UNBREAKING} /
     * legacy {@code DURABILITY}). {@code null} only on an exotic build with the
     * field absent — then we treat every piece as level 0 (era: never skips).
     */
    private static final Enchantment UNBREAKING = Enchantments.unbreaking();

    public ArmourDurabilityModule(@NotNull MentalServices services) {
        super(services,
                "old-armour-durability",
                "Old Armour Durability",
                "Restores the 1.8 armour Unbreaking skip (60 + 40/(level+1)% wear chance), "
                        + "keeping vanilla's unchanged per-hit magnitude, so armour lasts longer.",
                DebugCategory.CONFIG);
    }

    @Override
    public boolean configEnabled() {
        return services.config().armourDurability().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    @Override
    protected void onDisable() {
        // Zero-touch: listeners are unregistered by CombatModule; vanilla armour
        // durability resumes immediately.
    }

    /**
     * Applies the 1.8 armour Unbreaking skip to a worn-armour wear hit, leaving
     * the vanilla wear magnitude intact on a non-skip.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemDamage(@NotNull PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (!isWornArmour(player, item)) {
            return; // Not a currently-worn armour piece — leave vanilla wear.
        }

        int unbreakingLevel = UNBREAKING == null ? 0 : item.getEnchantmentLevel(UNBREAKING);
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (ArmourDurabilityMath.skipsDamage(unbreakingLevel, roll)) {
            event.setDamage(0); // Skip this hit's wear; vanilla magnitude discarded.
            debug.log(() -> "old-armour-durability skipped wear on " + item.getType()
                    + " (unbreaking=" + unbreakingLevel + ", roll=" + roll + ")");
        }
        // Otherwise leave event.getDamage() — the unchanged vanilla magnitude.
    }

    /**
     * Whether {@code item} is a piece of armour {@code player} is currently
     * wearing (matched by material against the worn set), excluding the elytra.
     * Mirrors OCM's worn-piece check.
     */
    private static boolean isWornArmour(@NotNull Player player, @NotNull ItemStack item) {
        Material type = item.getType();
        if (type == Material.ELYTRA) {
            return false; // Elytra provides no protection — not era armour wear.
        }
        for (ItemStack worn : player.getInventory().getArmorContents()) {
            if (worn != null && worn.getType() == type && type != Material.ELYTRA) {
                return true;
            }
        }
        return false;
    }
}
