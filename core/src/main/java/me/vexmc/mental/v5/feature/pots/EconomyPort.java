package me.vexmc.mental.v5.feature.pots;

import org.bukkit.entity.Player;

/**
 * The soft economy seam {@code pot-fill} charges through — a narrow port so the
 * whole feature never compiles against Vault, and so a test can stub it directly
 * without a Vault jar on the gate classpath (the {@link PotFiller} logic is
 * exercised against a stub, not a live economy).
 *
 * <p>Production is {@link VaultEconomyPort}, which resolves Vault's
 * {@code net.milkbowl.vault.economy.Economy} service reflectively through the
 * {@code ServicesManager} — the same by-name coexistence idiom the OCM binding
 * uses. When no economy is registered {@link #present()} is false and the command
 * refuses honestly rather than filling for free.</p>
 */
public interface EconomyPort {

    /** Whether a live economy provider is available to charge against. */
    boolean present();

    /** The player's current balance, in the economy's units. */
    double balance(Player player);

    /**
     * Withdraws {@code amount} from the player. Returns true on success; a false
     * (transaction rejected) leaves the balance untouched and the caller must not
     * hand out any potions it thought it had paid for.
     */
    boolean withdraw(Player player, double amount);
}
