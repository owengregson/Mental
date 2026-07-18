package me.vexmc.mental.v5.feature.loadout;

import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
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
 * Combat Test 8c melee reach ({@code ct8c-reach}, CT8c decompile, spec §2.2 +
 * §2.11) — the per-weapon reach lever (the {@link HitboxUnit}/{@link
 * EraReachAttribute} template). On 1.20.5+ it writes the held weapon's CT8c reach
 * (base 2.5 / sword 3.0 / hoe+trident 3.5) to the client-synced {@code
 * ENTITY_INTERACTION_RANGE} attribute via {@link Ct8cReachAttribute}, reconciled on
 * join / respawn / world-change / hotbar / off-hand swap and restored on quit /
 * disable.
 *
 * <p>Zero-touch: default OFF; enabled it captures each player's original base and
 * restores every one on the scope close.</p>
 *
 * <h2>Version posture / reported cross-boundary needs</h2>
 * <ul>
 *   <li><b>Below 1.20.5</b> the interaction-range attribute is absent — this lever
 *       is a no-op. The sub-floor reach path (≤3.0 via the fast path's rewound
 *       reach validation, and the 3.5-reach hoe/trident being impossible there)
 *       lives in {@code HitRegistrationUnit}/{@code ReachValidator} (kernel /
 *       delivery), outside this unit's ownership; the loud degrade line prints
 *       here and the enforcement feed is a reported need.</li>
 *   <li><b>The 1.21.5+ per-weapon {@code ATTACK_RANGE} component</b> would carry the
 *       CT8c min/max plus the hitbox margin, but the shared {@code AttackRangeAdapter}
 *       builds a FIXED era 3.0/0.1 component, not the per-weapon 2.5/3.0/3.5 CT8c
 *       window — a per-weapon component needs an adapter change (platform seam,
 *       outside this ownership); reported.</li>
 *   <li><b>The targeting assists (§2.11)</b> — the 0.9 hitbox inflation and
 *       through-non-solid acceptance — must live INSIDE Mental's rewound reach
 *       validation ({@code ReachValidator} consumers), never by mutating entities;
 *       that is the kernel/delivery seam, reported.</li>
 *   <li><b>The ≥195% charged reach bonus</b> published by {@code ChargedAttackUnit}
 *       feeds the same reach validation, not this per-held attribute (the bonus is
 *       per-hit, not per-held-change); reported with the charge unit.</li>
 * </ul>
 */
public final class Ct8cReachUnit implements FeatureUnit, Listener {

    private Scheduling scheduling;
    private Ct8cReachAttribute attribute;

    @Override
    public Feature descriptor() {
        return Feature.CT8C_REACH;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        MentalPluginV5 plugin = JavaPlugin.getPlugin(MentalPluginV5.class);
        this.scheduling = plugin.scheduling();
        this.attribute = new Ct8cReachAttribute(scheduling);

        if (!attribute.supported()) {
            // Below 1.20.5: no per-player reach attribute. The ≤3.0 enforcement and
            // the impossible 3.5 hoe/trident reach are the fast path's job (reported);
            // this lever writes nothing (era-benign — vanilla survival reach is ~3.0).
            plugin.getLogger().info("ct8c-reach: the ENTITY_INTERACTION_RANGE attribute is absent on this "
                    + "version (lands 1.20.5) — the per-weapon reach lever is a no-op here; the ≤3.0 reach "
                    + "and the impossible 3.5-reach hoe/trident fall to the fast path's rewound reach "
                    + "validation (a reported cross-boundary need), and vanilla's ~3.0 survival reach stands.");
        }

        scope.listen(this);
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(@NotNull PlayerRespawnEvent event) {
        applyToHeld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        applyToHeld(event.getPlayer());
    }

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
        attribute.apply(player, Ct8cReachTable.reachFor(materialName));
    }
}
