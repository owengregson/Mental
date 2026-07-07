package me.vexmc.mental.v5.feature.pots;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * The production {@link EconomyPort}: a soft, reflection-only bridge to Vault's
 * {@code net.milkbowl.vault.economy.Economy} service. Mental never compiles
 * against Vault — the whole surface is resolved by name through the
 * {@code ServicesManager}, a by-name soft-dependency service lookup.
 *
 * <p>Everything degrades to "absent / no-op" rather than throwing: no Vault class,
 * no registered provider, or an unexpected provider shape all make
 * {@link #present()} false, on which the command refuses honestly (it never fills
 * for free when a cost is set). The provider is looked up fresh each call so a
 * Vault that enables after Mental is still picked up; the reflected method handles
 * are cheap {@code getMethod} calls on an already-loaded class.</p>
 */
public final class VaultEconomyPort implements EconomyPort {

    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";

    @Override
    public boolean present() {
        return provider() != null;
    }

    @Override
    public double balance(Player player) {
        Object economy = provider();
        if (economy == null) {
            return 0.0;
        }
        try {
            Method getBalance = balanceMethod(economy.getClass());
            Object result = getBalance.invoke(economy, player);
            return result instanceof Number number ? number.doubleValue() : 0.0;
        } catch (ReflectiveOperationException failure) {
            return 0.0;
        }
    }

    @Override
    public boolean withdraw(Player player, double amount) {
        Object economy = provider();
        if (economy == null) {
            return false;
        }
        try {
            Object response = withdraw(economy, player, amount);
            if (response == null) {
                return false;
            }
            Method success = response.getClass().getMethod("transactionSuccess");
            Object flag = success.invoke(response);
            return flag instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    /** The registered Vault economy provider, or {@code null} when unavailable. */
    private Object provider() {
        try {
            Class<?> economyClass = Class.forName(ECONOMY_CLASS, false, getClass().getClassLoader());
            RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration(economyClass);
            return registration == null ? null : registration.getProvider();
        } catch (ReflectiveOperationException | LinkageError absent) {
            return null;
        }
    }

    /** {@code getBalance(OfflinePlayer)} on modern Vault; {@code getBalance(Player)} as a fallback. */
    private static Method balanceMethod(Class<?> economyClass) throws NoSuchMethodException {
        try {
            return economyClass.getMethod("getBalance", OfflinePlayer.class);
        } catch (NoSuchMethodException olderVault) {
            return economyClass.getMethod("getBalance", Player.class);
        }
    }

    /**
     * {@code withdrawPlayer(OfflinePlayer, double)} on modern Vault, falling back to
     * the legacy {@code withdrawPlayer(String, double)} keyed on the player's name.
     */
    private static Object withdraw(Object economy, Player player, double amount)
            throws ReflectiveOperationException {
        try {
            Method modern = economy.getClass().getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            return modern.invoke(economy, player, amount);
        } catch (NoSuchMethodException olderVault) {
            Method legacy = economy.getClass().getMethod("withdrawPlayer", String.class, double.class);
            return legacy.invoke(economy, player.getName(), amount);
        }
    }
}
