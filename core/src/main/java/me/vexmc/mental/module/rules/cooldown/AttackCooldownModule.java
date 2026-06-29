package me.vexmc.mental.module.rules.cooldown;

import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.engine.CombatModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Removes the 1.9 attack cooldown — its DAMAGE ramp as well as its client-side
 * charge animation — restoring the 1.7/1.8 feel of no attack-speed attribute.
 *
 * <p>Two halves, because the cooldown lives in two places:</p>
 * <ul>
 *   <li><b>Client overlay</b> — the always-registered {@link CooldownSpoofListener}
 *       rewrites the outbound {@code UPDATE_ATTRIBUTES} {@code attack_speed} so the
 *       client renders no charge bar / greyed-out swing. (Once the server base is
 *       raised below, the wire already carries the high value; the spoof remains as
 *       a join-window backstop and the {@link WeaponAttributeTooltipHider} still
 *       hides the resulting tooltip line.)</li>
 *   <li><b>Server damage ramp</b> — {@link ServerAttackSpeed} raises the player's
 *       SERVER {@code attack_speed} base so vanilla {@code Player#attack} treats
 *       every swing as fully charged. This is what makes a spam-clicked hit deal
 *       full damage on the paths the fast path does NOT own (a non-player target on
 *       Folia, or any fast-path-OFF hit) — see {@link ServerAttackSpeed} for why
 *       the client spoof alone left those hits scaled to ~20%.</li>
 * </ul>
 *
 * <p>Lifecycle (the server half): the base is applied to online players on enable
 * and on join / respawn / world-change (a respawn resets the player's attribute
 * map to vanilla defaults, so it MUST be re-applied), and restored on quit and on
 * disable (zero-touch). The packet listener half is registered for the plugin
 * lifetime in {@code MentalPlugin} and gates on the same config flag.</p>
 *
 * <h2>Feel note: sweep</h2>
 * <p>A full server-side attack charge also satisfies vanilla's {@code scale > 0.9}
 * sweep gate, so 1.9 sweep attacks become eligible on the vanilla path (grounded,
 * non-sprint swings) — which 1.7/1.8 never had. The fast path omits sweep already;
 * to suppress it on the vanilla path too, enable the {@code no-sweep} module. The
 * modules are intentionally independent (one rule each), not auto-coupled.</p>
 */
public final class AttackCooldownModule extends CombatModule implements Listener {

    private final ServerAttackSpeed serverAttackSpeed;

    public AttackCooldownModule(@NotNull MentalServices services) {
        super(services,
                "attack-cooldown",
                "Attack Cooldown Removal",
                "Removes the 1.9 attack cooldown — both the client charge overlay "
                        + "(UPDATE_ATTRIBUTES spoof) and the server-side damage ramp (raises the "
                        + "attack_speed base so every swing is full charge, era 1.7/1.8).",
                DebugCategory.PACKETS);
        this.serverAttackSpeed = new ServerAttackSpeed(services, debug);
    }

    @Override
    public boolean configEnabled() {
        return services.config().cooldown().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
        // Apply to already-online players so toggling the module on takes effect now.
        for (Player player : Bukkit.getOnlinePlayers()) {
            serverAttackSpeed.apply(player);
        }
    }

    @Override
    protected void onDisable() {
        // Zero-touch: restore every captured base. The client packet listener
        // gates on the config flag, so it needs no teardown here.
        serverAttackSpeed.disableAll();
    }

    /* ------------------------------------------------------------------ */
    /*  Server-base lifecycle                                              */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        serverAttackSpeed.apply(event.getPlayer());
    }

    /**
     * A respawn swaps in a fresh attribute map at vanilla defaults — without
     * re-applying here the cooldown silently returns after every death.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(@NotNull PlayerRespawnEvent event) {
        serverAttackSpeed.apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        // The base is per-entity and survives a world change, but re-apply to be safe.
        serverAttackSpeed.apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        serverAttackSpeed.restore(event.getPlayer());
    }
}
