package me.vexmc.mental.tester.suite;

import me.vexmc.mental.platform.HandStates;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Shared clientless native-block staging, extracted from {@code BlockingSuite} so
 * the gen-3 D2 combo case (a natively blocked cadence forms a combo) reuses the
 * exact same probe technique rather than growing a third clone. A synthetic
 * {@link FakePlayer} has no client to confirm a use-item over the wire, so the
 * ordinary interact path may never light the block pose up — these helpers fire
 * the module's interact AND drive a direct {@code startUsingItem} to force the
 * real native block state, and report whether it was actually observed.
 */
public final class NativeBlockStaging {

    private NativeBlockStaging() {}

    /**
     * Whether this server runs the native {@code BLOCKS_ATTACKS} reduction tier —
     * probed by the same {@code DataComponents.BLOCKS_ATTACKS} field presence the
     * module's {@code SwordBlockComponents} uses to pick its tier (never a version
     * literal). On this tier the module applies no software reduction (the
     * component reduces {@code getFinalDamage()} natively), so a captured BASE
     * damage is unchanged.
     */
    public static boolean blocksAttacksPresent() {
        try {
            Class<?> dataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            dataComponents.getField("BLOCKS_ATTACKS");
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    /**
     * Puts the clientless fake into the REAL native block state: the module's
     * interact applies the block component, and a direct {@code startUsingItem}
     * (the probe technique) raises the use-item — a synthetic player may not do so
     * from the interact alone (no client confirms the use over the wire). Returns
     * whether the block state was actually observed.
     */
    public static boolean forceNativeBlock(TestContext context, FakePlayer blocker) throws Exception {
        Boolean staged = context.sync(() -> {
            try {
                PlayerInteractEvent event = new PlayerInteractEvent(
                        blocker.player(),
                        Action.RIGHT_CLICK_AIR,
                        blocker.player().getInventory().getItemInMainHand(),
                        null,
                        BlockFace.SELF,
                        EquipmentSlot.HAND);
                Bukkit.getPluginManager().callEvent(event);
                startUsingMainHand(blocker.player());
                return Boolean.TRUE;
            } catch (Throwable unsupported) {
                return null;
            }
        });
        if (staged == null) {
            return false;
        }
        context.awaitTicks(3);
        return context.sync(() ->
                blocker.player().isBlocking() || HandStates.isHandRaised(blocker.player()));
    }

    /** {@code LivingEntity#startUsingItem(EquipmentSlot)} is 1.21+; reflect so the 1.17.1 floor compiles. */
    public static void startUsingMainHand(Player player) {
        try {
            player.getClass().getMethod("startUsingItem", EquipmentSlot.class)
                    .invoke(player, EquipmentSlot.HAND);
        } catch (Throwable absent) {
            // Below 1.21 the interact path is the only route; the caller note-skips if unobserved.
        }
    }
}
