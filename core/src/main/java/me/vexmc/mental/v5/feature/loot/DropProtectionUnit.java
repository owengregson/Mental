package me.vexmc.mental.v5.feature.loot;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.ServerEnvironment;
import me.vexmc.mental.platform.TaskHandle;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings.GlowColor;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Drop protection (the LOOT family, default-OFF): when a player kills another
 * player, the victim's drops are locked to the KILLER for a configurable window
 * and glow gold to the killer alone.
 *
 * <h2>Capture</h2>
 * At {@link PlayerDeathEvent} (HIGHEST, so other plugins have finalised the drop
 * list first) on a PvP kill with drops, the unit CLEARS the event's drop list
 * and re-drops each stack itself via {@code dropItemNaturally} — the deterministic
 * way to get the {@link Item} entity handles (matching stacks to entities after
 * the fact is fragile). Each is recorded in {@link DropProtectionState} and
 * glowed to the killer.
 *
 * <h2>Gate</h2>
 * A version-split pickup listener ({@link ModernPickupListener} on 1.12+, else
 * {@link LegacyPickupListener}) cancels a protected pickup by anyone but the
 * killer. A killer's own pickup ends that item's protection.
 *
 * <h2>Expiry</h2>
 * A single {@code repeatGlobal} sweep advances an opaque tick counter and
 * de-glows every entry whose window has elapsed — pure packet + map work, no
 * live-entity access, so it is Folia-safe. Everything acquired (the listeners,
 * the sweep, the glow team state) is torn down via {@code scope.task} on
 * disable, so a disabled feature does nothing (zero-touch): drops and pickups
 * are byte-identical to vanilla.
 */
public final class DropProtectionUnit implements FeatureUnit, Listener {

    /** The sweep cadence, in ticks — expiry is precise to within this period. */
    private static final long SWEEP_PERIOD = 4L;
    private static final long TICKS_PER_SECOND = 20L;

    private final Supplier<Snapshot> snapshot;
    private final Scheduling scheduling;
    private final ServerEnvironment environment;

    private final DropProtectionState state = new DropProtectionState();
    /** {@code killerId|teamName} pairs whose glow team has been created on that client (create-once). */
    private final java.util.Set<String> createdTeams = ConcurrentHashMap.newKeySet();
    /** Opaque monotonic tick, advanced only by the sweep; expiry ticks compare against it. */
    private final AtomicLong tick = new AtomicLong();

    public DropProtectionUnit(
            @NotNull Supplier<Snapshot> snapshot, @NotNull Scheduling scheduling,
            @NotNull ServerEnvironment environment) {
        this.snapshot = snapshot;
        this.scheduling = scheduling;
        this.environment = environment;
    }

    @Override
    public Feature descriptor() {
        return Feature.DROP_PROTECTION;
    }

    @Override
    public void assemble(Scope scope, Snapshot ignored) {
        scope.listen(this); // death capture + quit cleanup
        // EntityPickupItemEvent is 1.12+; below it only PlayerPickupItemEvent
        // exists — register the version-correct one so the missing type is never
        // touched on the wrong server.
        scope.listen(environment.isAtLeast(1, 12, 0)
                ? new ModernPickupListener(state)
                : new LegacyPickupListener(state));
        scope.task(() -> {
            TaskHandle handle = scheduling.repeatGlobal(SWEEP_PERIOD, SWEEP_PERIOD, this::sweep);
            return () -> {
                handle.cancel();
                deglowAll();
                createdTeams.clear();
            };
        });
    }

    /* -------------------------------- capture ------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) {
            return; // PvP only — mob / environmental / self deaths drop as vanilla
        }
        if (event.getKeepInventory() || event.getDrops().isEmpty()) {
            return; // nothing to protect
        }
        DropProtectionSettings settings = settings();
        List<ItemStack> stacks = new ArrayList<>(event.getDrops());
        event.getDrops().clear(); // we re-drop to capture the entities; the server then drops nothing

        Location where = victim.getLocation();
        World world = victim.getWorld();
        UUID killerId = killer.getUniqueId();
        GlowColor color = settings.glowColor();
        long expiry = tick.get() + (long) settings.seconds() * TICKS_PER_SECOND;

        User killerUser = PacketEvents.getAPI().getPlayerManager().getUser(killer);
        boolean createTeam = killerUser != null
                && createdTeams.add(killerId + "|" + GlowPackets.teamName(color));
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            Item item = world.dropItemNaturally(where, stack);
            int entityId = item.getEntityId();
            UUID entityUuid = item.getUniqueId();
            state.protect(entityId, entityUuid, killerId, expiry, color);
            if (killerUser != null) {
                GlowPackets.glow(killerUser, entityId, entityUuid.toString(), color, createTeam);
                createTeam = false; // only the first item in the batch creates the team
            }
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        // Drop the killer's team-created markers so a rejoin re-creates the team
        // (the client forgets teams across a relog). Their still-protected drops
        // stay locked until expiry — only they could have picked them up anyway.
        String prefix = event.getPlayer().getUniqueId() + "|";
        createdTeams.removeIf(key -> key.startsWith(prefix));
    }

    /* -------------------------------- expiry -------------------------------- */

    private void sweep() {
        long now = tick.addAndGet(SWEEP_PERIOD);
        for (DropProtectionState.Protected expired : state.expire(now)) {
            deglow(expired);
        }
    }

    private void deglowAll() {
        for (DropProtectionState.Protected entry : state.drain()) {
            deglow(entry);
        }
    }

    private void deglow(DropProtectionState.Protected entry) {
        Player killer = Bukkit.getPlayer(entry.killerId());
        if (killer == null) {
            return; // offline — nothing to unrender on their client
        }
        User user = PacketEvents.getAPI().getPlayerManager().getUser(killer);
        if (user != null) {
            GlowPackets.clear(user, entry.entityId(), entry.entityUuid().toString(), entry.color());
        }
    }

    @SuppressWarnings("unchecked")
    private DropProtectionSettings settings() {
        return snapshot.get().settings(
                (SettingsKey<DropProtectionSettings>) Feature.DROP_PROTECTION.settingsKey());
    }
}
