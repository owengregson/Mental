package me.vexmc.mental.v5.feature.cadence;

import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.WeaponSpeedSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Combat Test 8c weapon attack speeds ({@code weapon-attack-speeds}, CT8c
 * decompile, spec §2.2). Recomputes the player's ATTACK_SPEED attribute base from
 * the held item's CT8c weapon class on join / respawn / world-change / hotbar /
 * off-hand swap (the {@link me.vexmc.mental.v5.feature.loadout.HitboxUnit}
 * reconcile pattern) via {@link Ct8cWeaponSpeedAttribute} (the {@link
 * AttackChargeReset} attribute-capture template), so a sword reads 4.5 att/s, an
 * axe 3.5, the bare hand 4.0 — the CT8c cadence.
 *
 * <p>Zero-touch: the module defaults OFF; enabled, it captures each player's
 * original base and restores every one on the scope close. The settings (the
 * per-class att/s table) are baked at assemble, so the unit opts into {@link
 * #rebuildOnSettingsChange} — an edited table re-lands on reload.</p>
 *
 * <h2>Arbitration with {@code attack-cooldown}</h2>
 * <p>The cooldown-removal module raises the same base to a fixed 1024 spoof (every
 * swing full-charge). The two are mutually hostile: with {@code attack-cooldown}
 * enabled its spoof must win, so this unit prints one warn line at assemble and
 * NO-OPS (registers nothing), decided once at assemble (re-evaluated on this
 * module's own reload). Bundles arbitrate by never enabling both.</p>
 */
public final class WeaponSpeedUnit implements FeatureUnit, Listener {

    private Scheduling scheduling;
    private Ct8cWeaponSpeedAttribute attribute;
    private WeaponSpeedSettings settings;

    @Override
    public Feature descriptor() {
        return Feature.WEAPON_ATTACK_SPEEDS;
    }

    /** The att/s table is baked at assemble — a reload must re-assemble to re-land it. */
    @Override
    public boolean rebuildOnSettingsChange() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void assemble(Scope scope, Snapshot snapshot) {
        MentalPluginV5 plugin = JavaPlugin.getPlugin(MentalPluginV5.class);

        // Mutually hostile with attack-cooldown's 1024 base spoof: cooldown wins,
        // this unit no-ops (spec §3.2 / plan Task C). Decided once at assemble.
        if (snapshot.enabled(Feature.ATTACK_COOLDOWN)) {
            plugin.getLogger().info("weapon-attack-speeds: attack-cooldown is enabled, whose full-charge "
                    + "ATTACK_SPEED spoof (1024) overrides the per-weapon CT8c cadence — this module no-ops "
                    + "while cooldown removal owns the attribute (pick one; the bundles never enable both).");
            return;
        }

        this.scheduling = plugin.scheduling();
        this.attribute = new Ct8cWeaponSpeedAttribute(scheduling);
        this.settings = snapshot.settings(
                (SettingsKey<WeaponSpeedSettings>) Feature.WEAPON_ATTACK_SPEEDS.settingsKey());

        scope.listen(this);
        // Apply to online players on enable; restore every captured base on close.
        scope.task(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                applyToHeld(player);
            }
            return attribute::disableAll;
        });
    }

    /* ------------------------------- lifecycle ------------------------------- */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        applyToHeld(event.getPlayer());
    }

    /** A respawn resets the attribute map to vanilla defaults — re-apply or the cadence returns to vanilla. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(@NotNull PlayerRespawnEvent event) {
        applyToHeld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        applyToHeld(event.getPlayer());
    }

    /** Hotbar slot change — recompute from the newly-held slot on the region thread (post-change inventory). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(@NotNull PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        int newSlot = event.getNewSlot();
        scheduling.runOn(player, () -> applyForItem(player, player.getInventory().getItem(newSlot)), () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        scheduling.runOn(player, () -> applyToHeld(player), () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        attribute.restore(event.getPlayer());
    }

    /* -------------------------------- helpers -------------------------------- */

    private void applyToHeld(@NotNull Player player) {
        applyForItem(player, player.getInventory().getItemInMainHand());
    }

    private void applyForItem(@NotNull Player player, ItemStack held) {
        String materialName = held == null ? null : held.getType().name();
        Ct8cWeapons.Kind kind = Ct8cWeapons.classify(materialName);
        attribute.apply(player, Ct8cWeapons.attributeValue(kind, settings));
    }
}
