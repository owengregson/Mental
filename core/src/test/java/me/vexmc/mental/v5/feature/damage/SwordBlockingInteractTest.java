package me.vexmc.mental.v5.feature.damage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

/**
 * The S2 blockhit-re-arm interact gates (the modern-client sprint latch fix). These
 * pin the decision that lets a BORN-CANCELLED {@code RIGHT_CLICK_AIR} reach the
 * sprint re-arm, refuses a DENY-use interaction, and admits the victim-aimed entity
 * interact. The re-arm MECHANICS ({@code InputLedger.onBlockSprintReset} and its
 * held-block combo behaviour) are pinned in {@code InputLedgerSprintTest}; here we pin only
 * the event filtering that used to silently drop every air right-click.
 */
class SwordBlockingInteractTest {

    @Test
    void aBornCancelledAirRightClickReachesTheReArm() {
        // RIGHT_CLICK_AIR is born cancelled on Paper with useItemInHand=ALLOW — the gate
        // must admit it (the old ignoreCancelled=true dropped every such event, so the
        // blockhit re-arm never fired: the second half of the one-way sprint latch).
        assertTrue(SwordBlockingUnit.engagesBlock(
                Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Event.Result.ALLOW));
        // DEFAULT is the other benign result (no plugin vetoed the item use).
        assertTrue(SwordBlockingUnit.engagesBlock(
                Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, Event.Result.DEFAULT));
    }

    @Test
    void aDeniedItemUseDoesNotReachTheReArm() {
        // A protection plugin's explicit item-use DENY is honoured — no phantom re-arm.
        assertFalse(SwordBlockingUnit.engagesBlock(
                Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, Event.Result.DENY));
    }

    @Test
    void nonRightClicksAndTheOffHandFireAreIgnored() {
        assertFalse(SwordBlockingUnit.engagesBlock(
                        Action.LEFT_CLICK_AIR, EquipmentSlot.HAND, Event.Result.ALLOW),
                "a left click is not a block engage");
        assertFalse(SwordBlockingUnit.engagesBlock(
                        Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, Event.Result.ALLOW),
                "the off-hand fire is deduped away — only the main-hand fire engages");
    }

    @Test
    void theEntityInteractPathReArmsOnTheMainHandOnly() {
        // Victim-aimed right-clicks fire PlayerInteractEntityEvent; the main-hand fire
        // engages the block (re-arm), the off-hand fire is the deduped twin.
        assertTrue(SwordBlockingUnit.engagesBlockEntity(EquipmentSlot.HAND));
        assertFalse(SwordBlockingUnit.engagesBlockEntity(EquipmentSlot.OFF_HAND));
    }
}
