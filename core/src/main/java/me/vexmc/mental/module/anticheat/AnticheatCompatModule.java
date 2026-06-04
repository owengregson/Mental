package me.vexmc.mental.module.anticheat;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.AnticheatMode;
import me.vexmc.mental.config.AnticheatSettings;
import me.vexmc.mental.engine.CombatModule;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Detection-driven anticheat coexistence.
 *
 * <p>Movement-prediction anticheats (GrimAC, Vulcan) verify client motion
 * against the velocity the server's own pipeline produced. Every
 * gameplay-affecting velocity in Mental already flows through that pipeline;
 * the single out-of-band behavior is the netty-thread velocity pre-send.
 * This module watches the plugin list and flips the {@link AnticheatGate}
 * so the fast path suppresses exactly that one behavior while a known
 * anticheat is installed — hits still land at full speed, with feedback
 * reverting to vanilla cadence. No flags are cancelled, no exemptions are
 * requested: compatibility comes from staying server-authoritative.</p>
 */
public final class AnticheatCompatModule extends CombatModule implements Listener {

    public AnticheatCompatModule(@NotNull MentalServices services) {
        super(services, "anticheat-compat", "Anticheat Compat",
                "Suppresses the velocity pre-send while a known anticheat is installed.",
                DebugCategory.ANTICHEAT);
    }

    @Override
    public boolean configEnabled() {
        return true; // policy evaluation is always on; behavior is governed by anticheat.mode
    }

    @Override
    protected void onEnable() {
        listen(this);
        evaluate("startup");
    }

    @Override
    protected void onDisable() {
        services.anticheatGate().update(true, true, List.of());
    }

    @Override
    protected void onReload() {
        evaluate("reload");
    }

    @EventHandler
    public void onPluginEnable(@NotNull PluginEnableEvent event) {
        if (isKnownAnticheat(event.getPlugin().getName())) {
            evaluate("plugin enable: " + event.getPlugin().getName());
        }
    }

    @EventHandler
    public void onPluginDisable(@NotNull PluginDisableEvent event) {
        if (isKnownAnticheat(event.getPlugin().getName())) {
            evaluate("plugin disable: " + event.getPlugin().getName());
        }
    }

    private boolean isKnownAnticheat(String pluginName) {
        for (String known : services.config().anticheat().knownPlugins()) {
            if (known.equalsIgnoreCase(pluginName)) {
                return true;
            }
        }
        return false;
    }

    private void evaluate(String trigger) {
        AnticheatSettings settings = services.config().anticheat();
        List<String> detected = new ArrayList<>();
        for (String known : settings.knownPlugins()) {
            if (Bukkit.getPluginManager().getPlugin(known) != null) {
                detected.add(known);
            }
        }

        // One posture governs both adjustable behaviors today: with an
        // anticheat present, the pre-send is its movement-prediction hazard
        // and reach is its own department.
        boolean permissive = switch (settings.mode()) {
            case AUTO -> detected.isEmpty();
            case FORCE_SAFE -> false;
            case OFF -> true;
        };

        AnticheatGate gate = services.anticheatGate();
        boolean changed = gate.allowVelocityPreSend() != permissive
                || gate.allowReachValidation() != permissive
                || !gate.detected().equals(detected);
        gate.update(permissive, permissive, detected);

        if (changed) {
            services.plugin().getLogger().info("Anticheat policy (" + trigger + "): " + gate.describe());
        }
        debug.log(() -> "evaluated on " + trigger + " -> " + gate.describe());
    }
}
