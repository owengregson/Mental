package me.vexmc.mental.v5.feature.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.vexmc.mental.v5.coexist.AnticheatPolicy;
import me.vexmc.mental.v5.config.AnticheatMode;
import me.vexmc.mental.v5.config.settings.AnticheatSettings;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * Detection-driven anticheat coexistence (the retired {@code AnticheatCompatModule}
 * on the v5 seams). Always-on infrastructure ({@link Feature#ANTICHEAT_COMPAT}):
 * it watches the plugin list and flips the {@link AnticheatPolicy} so the fast
 * path suppresses its one out-of-band behaviour — the netty velocity pre-send —
 * while a known movement-prediction anticheat is installed. Hits still land at
 * full speed; feedback reverts to vanilla cadence. No flags are cancelled and no
 * exemptions are requested: compatibility comes from staying server-authoritative.
 */
public final class AnticheatCompatUnit implements FeatureUnit, Listener {

    private final AnticheatPolicy policy;
    private final Supplier<Snapshot> snapshot;
    private final Consumer<String> log;

    public AnticheatCompatUnit(AnticheatPolicy policy, Supplier<Snapshot> snapshot, Consumer<String> log) {
        this.policy = policy;
        this.snapshot = snapshot;
        this.log = log;
    }

    @Override
    public Feature descriptor() {
        return Feature.ANTICHEAT_COMPAT;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
        evaluate("startup");
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (isKnownAnticheat(event.getPlugin().getName())) {
            evaluate("plugin enable: " + event.getPlugin().getName());
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (isKnownAnticheat(event.getPlugin().getName())) {
            evaluate("plugin disable: " + event.getPlugin().getName());
        }
    }

    private boolean isKnownAnticheat(String pluginName) {
        for (String known : snapshot.get().anticheat().knownPlugins()) {
            if (known.equalsIgnoreCase(pluginName)) {
                return true;
            }
        }
        return false;
    }

    private void evaluate(String trigger) {
        AnticheatSettings settings = snapshot.get().anticheat();
        List<String> detected = new ArrayList<>();
        for (String known : settings.knownPlugins()) {
            if (Bukkit.getPluginManager().getPlugin(known) != null) {
                detected.add(known);
            }
        }
        boolean permissive = switch (settings.mode()) {
            case AUTO -> detected.isEmpty();
            case FORCE_SAFE -> false;
            case OFF -> true;
        };
        boolean changed = policy.allowVelocityPreSend() != permissive
                || policy.allowReachValidation() != permissive
                || !policy.detected().equals(detected);
        policy.update(permissive, permissive, detected);
        if (changed) {
            log.accept("anticheat policy (" + trigger + "): " + policy.describe());
        }
    }
}
