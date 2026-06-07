package me.vexmc.mental.module.hitreg;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.config.KnockbackProfile;
import me.vexmc.mental.module.knockback.EntityState;
import me.vexmc.mental.module.knockback.GroundFriction;
import me.vexmc.mental.module.knockback.KnockbackProfiles;
import me.vexmc.mental.module.knockback.VictimMotion;
import me.vexmc.mental.module.ocm.OcmGate;
import me.vexmc.mental.module.ocm.OcmMechanic;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Enchantments;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tick-frozen per-player snapshots.
 *
 * <p>The fast path computes knockback on PacketEvents' netty loop, where
 * touching a live {@code Player} is undefined on Paper and forbidden on
 * Folia. Each player's snapshot is refreshed once per tick <em>from their own
 * owning thread</em> (per-player scheduled tasks — region-correct on Folia by
 * construction); the netty thread only ever reads immutable records out of a
 * concurrent map.</p>
 */
public final class PlayerStateCache {

    private final ConcurrentHashMap<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * Captures {@code player} now. Must run on the player's owning thread.
     * Motion comes from the {@link VictimMotion} ledger — the same legacy
     * residual the authoritative damage path feeds the engine — so the netty
     * pre-send and the owning-thread apply compute from one model.
     */
    @SuppressWarnings("deprecation") // Player#isOnGround: the client-reported value is the one
    public void update(@NotNull Player player, @NotNull VictimMotion ledger, // knockback expectations are built from.
            @NotNull OcmGate ocmGate, @NotNull KnockbackProfiles profiles) {
        Location location = player.getLocation();
        boolean onGround = player.isOnGround();
        // The boundary read: a ground transition whose packet arrived THIS
        // tick is excluded, so the frozen motion is the state as of the end
        // of the previous tick — the exact state an era server's attack slot
        // judged for a same-tick hit (the attacker's connection slot ran
        // before the victim's). Without it, a knock thrown the instant its
        // victim touches down reads post-landing equilibrium and ships the
        // grounded 0.3608 vertical where the era ships the pre-landing ~0.25.
        VictimMotion.Motion motion = ledger.currentExcludingTick(
                player.getUniqueId(),
                Bukkit.getCurrentTick(),
                System.nanoTime(),
                onGround,
                Attributes.valueOr(player, Attributes.gravity(), VictimMotion.DEFAULT_GRAVITY));
        snapshots.put(player.getUniqueId(), new Snapshot(
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(),
                motion.vx(), motion.vy(), motion.vz(),
                onGround,
                player.isSprinting(),
                clampedKnockbackResistance(player),
                mainHandKnockbackLevel(player),
                player.getNoDamageTicks(),
                player.getMaximumNoDamageTicks(),
                // OCM's API is owning-thread only; freeze the answer here so the
                // netty pre-send can consult it without touching OCM.
                ocmGate.handles(OcmMechanic.MELEE_KNOCKBACK, player),
                player.getEntityId(),
                // Resolution touches getWorld(); freeze the victim's profile
                // here so the netty pre-send computes from the same one the
                // authoritative path will resolve this tick.
                profiles.resolve(player),
                player.getPing(),
                player.getGameMode() == GameMode.CREATIVE,
                Attributes.valueOr(player, Attributes.entityInteractionRange(), 3.0),
                Attributes.valueOr(player, Attributes.gravity(), VictimMotion.DEFAULT_GRAVITY),
                // The era ground drag is slipperiness × 0.91 from the block
                // under the victim's feet — the 1.7.10 delivery decay ships
                // ×0.8918 on ice, not ×0.546 (measured: ice hit 1 = 0.3567).
                GroundFriction.under(player)));
    }

    public @Nullable Snapshot get(@NotNull UUID uuid) {
        return snapshots.get(uuid);
    }

    public void forget(@NotNull UUID uuid) {
        snapshots.remove(uuid);
    }

    public void clear() {
        snapshots.clear();
    }

    private static double clampedKnockbackResistance(Player player) {
        double value = Attributes.valueOr(player, Attributes.knockbackResistance(), 0.0);
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int mainHandKnockbackLevel(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack main = inventory.getItemInMainHand();
        if (main != null && main.getType() != Material.AIR) {
            return main.getEnchantmentLevel(Enchantments.knockback());
        }
        ItemStack off = inventory.getItemInOffHand();
        if (off == null || off.getType() == Material.AIR) {
            return 0;
        }
        return off.getEnchantmentLevel(Enchantments.knockback());
    }

    /** Immutable snapshot (primitives plus one immutable profile) — safely publishable across threads. */
    public record Snapshot(
            double x, double y, double z,
            float yaw,
            double vx, double vy, double vz,
            boolean onGround,
            boolean sprinting,
            double knockbackResistance,
            int mainHandKnockbackLevel,
            int noDamageTicks,
            int maxNoDamageTicks,
            boolean ocmOwnsMeleeKnockback,
            int entityId,
            @NotNull KnockbackProfile profile,
            int pingMillis,
            boolean creative,
            double attackReach,
            double gravity,
            double groundSlipperiness) {

        /**
         * Vanilla's double-hit guard: inside this window a fresh hit carries
         * no knockback unless it out-damages the previous one, so the fast
         * path must not pre-send velocity for it.
         *
         * <p>The {@code + 1} is the snapshot's staleness allowance. This value
         * froze at the start of the tick, BEFORE that tick's decrement, so a
         * legal perfect-cadence combo hit — thrown the instant the window
         * halves, the spam-combo norm — reads one tick high here while
         * vanilla's own judgment at processing reads exactly {@code max/2}.
         * Without the allowance every boundary hit skips its pre-send and
         * falls to the authoritative next-tick send, which computes from
         * post-landing state (measured: those hits ship the grounded 0.3608
         * vertical where the era ships the pre-landing ~0.25 — the
         * floaty-combo signature). Phantom-safe by construction: the deferred
         * damage runs at least one tick after this snapshot froze, so any hit
         * admitted here arrives there with {@code nd <= max/2} and is
         * accepted — the wire and the damage verdict cannot disagree.</p>
         */
        public boolean isDamageImmune() {
            return noDamageTicks > maxNoDamageTicks / 2 + 1;
        }

        /** The snapshot's alias for {@code onGround}, named for delivery-decay call sites. */
        public boolean grounded() {
            return onGround;
        }

        public @NotNull EntityState toEntityState() {
            return toEntityState(sprinting);
        }

        /**
         * The snapshot with its sprint flag replaced — for attacker states
         * whose sprint the wtap-registration wire view resolved in packet
         * order, ahead of this snapshot's tick-boundary freeze.
         */
        public @NotNull EntityState toEntityState(boolean sprintingOverride) {
            return new EntityState(
                    x, y, z, yaw, vx, vy, vz, onGround,
                    sprintingOverride, mainHandKnockbackLevel, knockbackResistance);
        }
    }
}
