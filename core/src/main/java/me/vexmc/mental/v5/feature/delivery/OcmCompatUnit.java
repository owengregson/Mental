package me.vexmc.mental.v5.feature.delivery;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.vexmc.mental.kernel.coexist.CoexistWarnings;
import me.vexmc.mental.kernel.coexist.MechanicToken;
import me.vexmc.mental.v5.coexist.OcmBinding;
import me.vexmc.mental.v5.config.OcmCoordination;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Detection-driven OldCombatMechanics coexistence (the retired
 * {@code module.ocm.OcmCompatModule} on the v5 seams). Always-on infrastructure
 * ({@link Feature#OCM_COMPAT}): it watches the plugin list and keeps the
 * {@link OcmBinding} current, so each combat feature yields exactly the
 * interactions OCM is configured to own and not one more.
 *
 * <p>OCM and Mental divide combat cleanly — OCM owns the <em>rules</em>, Mental
 * owns <em>knockback and hit delivery</em> — but the six arbitrated mechanics
 * (player/fishing/rod/projectile knockback, tool damage, critical hits) can
 * reach into Mental's half; the binding settles ownership per player (or, on an
 * OCM build without the service API, globally-conservatively from OCM's config
 * file). The one hot-path method, {@code isModuleEnabledForPlayer}, is resolved
 * once into a bound {@link MethodHandle}; Mental never compiles against OCM.
 * The Mental-owned ported rules are never arbitrated — enabling the same rule
 * in both plugins double-applies it, so the {@link OcmBinding#warnings} derived
 * here warn loudly at startup but the binding never yields them.</p>
 *
 * <p>Teardown mirrors the anticheat unit: the scope unregisters the listener on
 * disable, and the OCM-goes-away case is handled by the plugin-disable handler
 * (which clears the binding). At plugin shutdown the whole tree tears down, so
 * no separate binding reset is needed.</p>
 */
public final class OcmCompatUnit implements FeatureUnit, Listener {

    private static final String OCM_PLUGIN_NAME = "OldCombatMechanics";
    private static final String OCM_API_CLASS =
            "kernitus.plugin.OldCombatMechanics.api.OldCombatMechanicsAPI";

    private final OcmBinding binding;
    private final Supplier<Snapshot> snapshot;
    private final Supplier<Set<MechanicToken>> mentalEnabled;
    private final Consumer<String> log;

    public OcmCompatUnit(
            OcmBinding binding,
            Supplier<Snapshot> snapshot,
            Supplier<Set<MechanicToken>> mentalEnabled,
            Consumer<String> log) {
        this.binding = binding;
        this.snapshot = snapshot;
        this.mentalEnabled = mentalEnabled;
        this.log = log;
    }

    @Override
    public Feature descriptor() {
        return Feature.OCM_COMPAT;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
        evaluate("startup");
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (OCM_PLUGIN_NAME.equals(event.getPlugin().getName())) {
            evaluate("plugin enable: " + OCM_PLUGIN_NAME);
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (OCM_PLUGIN_NAME.equals(event.getPlugin().getName())) {
            binding.clear();
            log.accept("OldCombatMechanics disabled — Mental owns all combat mechanics again.");
        }
    }

    private void evaluate(String trigger) {
        Plugin ocm = Bukkit.getPluginManager().getPlugin(OCM_PLUGIN_NAME);
        if (ocm == null || !ocm.isEnabled()
                || snapshot.get().ocmCoordination() == OcmCoordination.IGNORE) {
            binding.clear();
            return;
        }

        YamlConfiguration ocmConfig = readConfig(ocm);
        Set<MechanicToken> staticVerdicts = OcmBinding.scanVerdicts(ocmConfig);
        CoexistWarnings.OcmFacts facts = OcmBinding.scanFacts(ocmConfig);

        if (!bindService(staticVerdicts, facts)) {
            binding.configOnly(staticVerdicts, facts);
        }

        log.accept("OCM coordination (" + trigger + "): " + binding.mode());
        for (String warning : binding.warnings(mentalEnabled.get())) {
            log.accept("coexistence — " + warning);
        }
    }

    /**
     * Loads OCM's {@code config.yml}, or an empty configuration when it is
     * missing or unreadable. An empty scan yields no verdicts (Mental owns
     * everything), which the service binding supersedes on the common path
     * where OCM has already written its config and registered its API.
     */
    private YamlConfiguration readConfig(Plugin ocm) {
        File file = new File(ocm.getDataFolder(), "config.yml");
        if (!file.isFile()) {
            return new YamlConfiguration();
        }
        try {
            return YamlConfiguration.loadConfiguration(file);
        } catch (RuntimeException unreadable) {
            log.accept("could not read OldCombatMechanics config (" + unreadable.getMessage()
                    + ") — falling back to the service API / no verdicts.");
            return new YamlConfiguration();
        }
    }

    /** Resolves OCM's service API and binds the per-player query; false when unavailable. */
    private boolean bindService(Set<MechanicToken> staticVerdicts, CoexistWarnings.OcmFacts facts) {
        try {
            Class<?> apiClass = Class.forName(OCM_API_CLASS, false, getClass().getClassLoader());
            RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration(apiClass);
            if (registration == null) {
                return false;
            }
            Object api = registration.getProvider();

            Method query = apiClass.getMethod(
                    "isModuleEnabledForPlayer", Player.class, String.class);
            MethodHandle bound = MethodHandles.lookup().unreflect(query).bindTo(api);

            // Pre-resolve which arbitrated mechanics this OCM build actually
            // ships, so the hot path never trips an unknown-module read.
            Method names = apiClass.getMethod("getModuleNames");
            Collection<?> moduleNames = (Collection<?>) names.invoke(api);
            Set<MechanicToken> known = EnumSet.noneOf(MechanicToken.class);
            for (MechanicToken token : MechanicToken.values()) {
                if (token.arbitrated() && token.ocmKey() != null
                        && moduleNames.contains(token.ocmKey())) {
                    known.add(token);
                }
            }

            binding.bind(bound, OcmCompatUnit::onlinePlayer, known, staticVerdicts, facts);
            return true;
        } catch (ReflectiveOperationException | ClassCastException unavailable) {
            return false;
        }
    }

    /** OCM's decider is the live {@link Player}; a null (offline) decider falls to the static verdict. */
    private static Object onlinePlayer(UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }
}
