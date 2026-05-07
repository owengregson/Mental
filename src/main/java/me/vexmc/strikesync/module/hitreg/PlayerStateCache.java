package me.vexmc.strikesync.module.hitreg;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player tick-frozen snapshot of the small slice of server state needed to
 * reproduce a knockback computation off the main thread.
 *
 * <h2>Why this exists</h2>
 * The fast hit-registration path needs to compute and send a velocity packet
 * from PacketEvents' netty event loop, which is not the main thread. Reading
 * a {@code Player}'s position, velocity, attributes, or inventory from a
 * non-owning thread is undefined behavior on Paper (and outright
 * thread-isolated on Folia). Instead, the {@link HitRegModule} updates this
 * cache on every tick from the player's owning region thread; the netty
 * thread only ever reads from immutable snapshots.
 *
 * <p>The cache is populated for <em>every</em> online player, every tick. The
 * cost is small (a few field reads + one inventory access per player) and the
 * convenience pays off: the netty thread never has to ask "is this player
 * tracked?" — the answer is always yes.
 */
public final class PlayerStateCache {

    private final ConcurrentHashMap<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    /** Capture {@code player}'s current state. Must be called on the player's owning thread. */
    @SuppressWarnings("deprecation") // Player#isOnGround intentionally chosen — it's the
                                      // client-reported value, which is what we want for
                                      // matching client-side knockback expectations.
    public void update(Player player) {
        var loc = player.getLocation();
        var vel = player.getVelocity();
        snapshots.put(player.getUniqueId(), new Snapshot(
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(),
                vel.getX(), vel.getY(), vel.getZ(),
                player.isOnGround(),
                player.isSprinting(),
                readKnockbackResistance(player),
                readMainHandKnockbackLevel(player),
                player.getEntityId()
        ));
    }

    /** Read the most-recent snapshot for {@code uuid}, or {@code null} if never seen. */
    public Snapshot get(UUID uuid) {
        return snapshots.get(uuid);
    }

    /** Drop a player's snapshot — called on quit. */
    public void forget(UUID uuid) {
        snapshots.remove(uuid);
    }

    /** Drop everything (used on disable). */
    public void clear() {
        snapshots.clear();
    }

    private static double readKnockbackResistance(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        return attr == null ? 0.0D : Math.max(0.0D, Math.min(1.0D, attr.getValue()));
    }

    private static int readMainHandKnockbackLevel(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack main = inv.getItemInMainHand();
        if (main == null || main.getType() == Material.AIR) {
            ItemStack off = inv.getItemInOffHand();
            if (off == null || off.getType() == Material.AIR) return 0;
            return off.getEnchantmentLevel(Enchantment.KNOCKBACK);
        }
        return main.getEnchantmentLevel(Enchantment.KNOCKBACK);
    }

    /**
     * Immutable per-tick snapshot. All fields are primitives so the record can
     * be safely published across threads via {@link ConcurrentHashMap}.
     */
    public record Snapshot(
            double x, double y, double z,
            float yaw,
            double vx, double vy, double vz,
            boolean onGround,
            boolean sprinting,
            double knockbackResistance,
            int mainHandKnockbackLevel,
            int entityId
    ) {
        public Vector velocity() {
            return new Vector(vx, vy, vz);
        }
    }
}
