package me.vexmc.mental.v5.feature.pots;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.v5.config.settings.PotFillSettings;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

/**
 * Fills the EMPTY slots of a player's STORAGE inventory — the 36 main + hotbar
 * slots (indices 0–35), never armour (36–39) or the off-hand (40) — with a
 * freshly constructed splash Instant Health II potion, charging per potion when
 * an economy cost is configured.
 *
 * <p>The economy arithmetic ({@link #affordable}) is a pure function, unit-pinned
 * independently of Bukkit; the fill itself is a thin loop over the empty slots.
 * When a cost is set the fill is a PARTIAL fill capped at what the player can
 * afford, and the charge is exactly the count actually filled — the withdrawal is
 * taken up-front and, if the provider rejects it, nothing is filled and nothing is
 * charged (no potions handed out unpaid).</p>
 */
public final class PotFiller {

    /** Storage slots 0–35: the 27 main + 9 hotbar slots. Armour (36–39) and off-hand (40) are excluded. */
    public static final int STORAGE_SLOTS = 36;

    private final HealPotItems items;

    public PotFiller(@NotNull HealPotItems items) {
        this.items = items;
    }

    /** The result of a fill: how many were placed, what was charged, and why (if any) it stopped short. */
    public record Outcome(int filled, double charged, int emptyBefore, Reason reason) {

        /** Why the fill placed fewer than the empty-slot count (or nothing at all). */
        public enum Reason {
            /** Filled every empty slot it could afford (possibly all of them). */
            FILLED,
            /** The heal-potion item could not be constructed on this server. */
            ITEM_UNAVAILABLE,
            /** A cost is set but the player could not afford a single potion. */
            CANNOT_AFFORD,
            /** The economy withdrawal was rejected — nothing filled, nothing charged. */
            CHARGE_FAILED,
            /** There were no empty storage slots to fill. */
            NOTHING_TO_FILL
        }
    }

    /**
     * How many potions the player can be given: every empty slot when the potion
     * is free ({@code costPerPotion <= 0}), otherwise the lesser of the empty-slot
     * count and how many whole potions the balance covers. Never negative.
     */
    public static int affordable(int emptySlots, double costPerPotion, double balance) {
        if (emptySlots <= 0) {
            return 0;
        }
        if (costPerPotion <= 0.0) {
            return emptySlots;
        }
        if (balance < costPerPotion) {
            return 0;
        }
        int canBuy = (int) Math.floor(balance / costPerPotion);
        return Math.min(emptySlots, canBuy);
    }

    /**
     * Fills {@code player}'s empty storage slots under {@code settings}, charging
     * through {@code economy} when a cost is set. Must run on the player's owning
     * region thread (it mutates their inventory).
     */
    public @NotNull Outcome fill(
            @NotNull Player player, @NotNull PotFillSettings settings, @NotNull EconomyPort economy) {
        ItemStack potion = items.createSplashHealPotion();
        if (potion == null) {
            return new Outcome(0, 0.0, 0, Outcome.Reason.ITEM_UNAVAILABLE);
        }

        PlayerInventory inventory = player.getInventory();
        List<Integer> empties = emptyStorageSlots(inventory);
        int emptyBefore = empties.size();
        if (emptyBefore == 0) {
            return new Outcome(0, 0.0, 0, Outcome.Reason.NOTHING_TO_FILL);
        }

        double cost = settings.costPerPotion();
        int toFill;
        double charged = 0.0;
        if (cost <= 0.0) {
            toFill = emptyBefore;
        } else {
            toFill = affordable(emptyBefore, cost, economy.balance(player));
            if (toFill <= 0) {
                return new Outcome(0, 0.0, emptyBefore, Outcome.Reason.CANNOT_AFFORD);
            }
            charged = toFill * cost;
            if (!economy.withdraw(player, charged)) {
                return new Outcome(0, 0.0, emptyBefore, Outcome.Reason.CHARGE_FAILED);
            }
        }

        for (int i = 0; i < toFill; i++) {
            inventory.setItem(empties.get(i), potion.clone());
        }
        return new Outcome(toFill, charged, emptyBefore, Outcome.Reason.FILLED);
    }

    /** The indices (0–35) of the empty storage slots, in order — AIR or null is empty. */
    private static List<Integer> emptyStorageSlots(PlayerInventory inventory) {
        List<Integer> empties = new ArrayList<>();
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType() == Material.AIR) {
                empties.add(slot);
            }
        }
        return empties;
    }
}
