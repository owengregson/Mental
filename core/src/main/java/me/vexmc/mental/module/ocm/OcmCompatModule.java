package me.vexmc.mental.module.ocm;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.config.OcmCoordination;
import me.vexmc.mental.engine.CombatModule;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;

/**
 * Detection-driven OldCombatMechanics coexistence.
 *
 * <p>OCM and Mental divide combat cleanly — OCM owns the <em>rules</em>
 * (attack cooldown, sword blocking, regen, armour, damage values), Mental
 * owns <em>knockback and hit delivery</em> — but four OCM modules reach into
 * Mental's half (player knockback, fishing knockback, rod velocity,
 * projectile knockback) and two shape damage values Mental's fast path
 * computes itself (tool damage, critical hits). This module watches the
 * plugin list and keeps the {@link OcmGate} current, so each combat module
 * yields exactly the interactions OCM is configured to own and not one more.</p>
 *
 * <p>Binding is reflective against OCM's Bukkit service
 * ({@code OldCombatMechanicsAPI}); Mental never compiles against OCM. The
 * one method on the hot path, {@code isModuleEnabledForPlayer}, is resolved
 * once into a bound {@link MethodHandle}. Builds without the service fall
 * back to global verdicts parsed from OCM's config file.</p>
 */
public final class OcmCompatModule extends CombatModule implements Listener {

    private static final String OCM_PLUGIN_NAME = "OldCombatMechanics";
    private static final String OCM_API_CLASS = "kernitus.plugin.OldCombatMechanics.api.OldCombatMechanicsAPI";

    private final OcmGate gate;

    public OcmCompatModule(@NotNull MentalServices services, @NotNull OcmGate gate) {
        super(services, "ocm-compat", "OCM Compat",
                "Divides combat with OldCombatMechanics: OCM-owned mechanics are yielded per modeset.",
                DebugCategory.ANTICHEAT);
        this.gate = gate;
    }

    @Override
    public boolean configEnabled() {
        return true; // coordination policy is always evaluated; behavior follows compatibility.old-combat-mechanics
    }

    @Override
    protected void onEnable() {
        listen(this);
        evaluate("startup");
    }

    @Override
    protected void onDisable() {
        gate.clear();
    }

    @Override
    protected void onReload() {
        evaluate("reload");
    }

    @EventHandler
    public void onPluginEnable(@NotNull PluginEnableEvent event) {
        if (OCM_PLUGIN_NAME.equals(event.getPlugin().getName())) {
            evaluate("plugin enable: " + OCM_PLUGIN_NAME);
        }
    }

    @EventHandler
    public void onPluginDisable(@NotNull PluginDisableEvent event) {
        if (OCM_PLUGIN_NAME.equals(event.getPlugin().getName())) {
            gate.clear();
            services.plugin().getLogger().info(
                    "OldCombatMechanics disabled — Mental owns all combat mechanics again.");
        }
    }

    private void evaluate(String trigger) {
        OcmGate.Mode before = gate.mode();
        Set<OcmMechanic> coordinatedBefore = gate.coordinated();

        Plugin ocm = Bukkit.getPluginManager().getPlugin(OCM_PLUGIN_NAME);
        if (ocm == null || !ocm.isEnabled()
                || services.config().compatibility().oldCombatMechanics() == OcmCoordination.IGNORE) {
            gate.clear();
            if (before != OcmGate.Mode.ABSENT) {
                services.plugin().getLogger().info("OCM coordination (" + trigger + "): " + gate.describe());
            }
            return;
        }

        Set<OcmMechanic> staticVerdicts = scanOcmConfig(ocm);
        if (!bindService(staticVerdicts)) {
            gate.configOnly(staticVerdicts);
        }

        if (gate.mode() != before || !gate.coordinated().equals(coordinatedBefore)) {
            logCoordination(trigger);
            warnFeelOverlaps(ocm);
        }
        debug.log(() -> "evaluated on " + trigger + " -> " + gate.describe());
    }

    /**
     * The two OCM defaults that change combat feel out from under Mental's
     * profiles, surfaced loudly because neither is visible in gameplay and
     * both read as "the selected profile is wrong" (each took a wire harness
     * to find once): old-player-knockback ships in OCM's default modeset and
     * computes from the server's stale player velocity (combo verticals stop
     * declining — floaty), and attack-frequency ships playerDelay 18 where
     * the 1.8 era hit window is 20 (combo cadence, and with it combo
     * verticals, run fast even when Mental owns the knock).
     */
    private void warnFeelOverlaps(@NotNull Plugin ocm) {
        if (!services.config().knockback().enabled()) {
            return; // no Mental profile is in play; nothing to be surprised by
        }
        var logger = services.plugin().getLogger();
        if (gate.coordinated().contains(OcmMechanic.MELEE_KNOCKBACK)) {
            logger.warning("OCM's old-player-knockback governs melee knockback "
                    + (gate.mode() == OcmGate.Mode.BOUND
                            ? "for every player whose OCM modeset enables it"
                            : "globally (no service API — config verdict)")
                    + ": those hits use OCM's 1.8 formula on the server's stale velocity,"
                    + " NOT the victim's Mental knockback profile.");
            logger.warning("  To let Mental's profiles shape melee knockback, remove"
                    + " \"old-player-knockback\" from the modeset lists in"
                    + " OldCombatMechanics/config.yml.");
        }
        File ocmConfig = new File(ocm.getDataFolder(), "config.yml");
        if (!ocmConfig.isFile()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(ocmConfig);
            if (yaml.getStringList("disabled_modules").contains("attack-frequency")) {
                return;
            }
            int playerDelay = yaml.getInt("attack-frequency.playerDelay", 20);
            if (playerDelay != 20) {
                logger.warning("OCM attack-frequency sets playerDelay " + playerDelay
                        + " (OCM ships 18 by default); the 1.8 era hit window is 20 ticks."
                        + " Combo cadence — and with it combo knockback verticals — will run"
                        + " off the era values until it is set to 20.");
            }
        } catch (RuntimeException unreadable) {
            // scanOcmConfig already warned about an unreadable config
        }
    }

    /** Resolves OCM's service API and binds the per-player query; false when unavailable. */
    private boolean bindService(Set<OcmMechanic> staticVerdicts) {
        try {
            Class<?> apiClass = Class.forName(OCM_API_CLASS, false, OcmCompatModule.class.getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(apiClass);
            if (registration == null) {
                return false;
            }
            Object api = registration.getProvider();

            Method query = apiClass.getMethod(
                    "isModuleEnabledForPlayer", org.bukkit.entity.Player.class, String.class);
            MethodHandle bound = MethodHandles.lookup().unreflect(query).bindTo(api);

            // Pre-resolve which of our mechanics this OCM build actually has,
            // so the hot path never trips IllegalArgumentException.
            Method names = apiClass.getMethod("getModuleNames");
            Collection<?> moduleNames = (Collection<?>) names.invoke(api);
            Set<OcmMechanic> known = EnumSet.noneOf(OcmMechanic.class);
            for (OcmMechanic mechanic : OcmMechanic.values()) {
                if (moduleNames.contains(mechanic.ocmName())) {
                    known.add(mechanic);
                }
            }

            gate.bind(bound, known, staticVerdicts);
            return true;
        } catch (ReflectiveOperationException | ClassCastException unavailable) {
            debug.log(() -> "service API unavailable (" + unavailable + ") — using config verdicts");
            return false;
        }
    }

    private @NotNull Set<OcmMechanic> scanOcmConfig(@NotNull Plugin ocm) {
        File file = new File(ocm.getDataFolder(), "config.yml");
        if (!file.isFile()) {
            // First boot before OCM saved its config: assume its bundled
            // defaults (every overlapping mechanic reachable) until the next
            // evaluation; the service binding usually supersedes this anyway.
            return EnumSet.allOf(OcmMechanic.class);
        }
        try {
            return OcmConfigScan.verdicts(YamlConfiguration.loadConfiguration(file));
        } catch (RuntimeException unreadable) {
            services.plugin().getLogger().warning(
                    "Could not read OldCombatMechanics config (" + unreadable.getMessage()
                            + ") — conservatively yielding every overlapping mechanic.");
            return EnumSet.allOf(OcmMechanic.class);
        }
    }

    private void logCoordination(String trigger) {
        var logger = services.plugin().getLogger();
        logger.info("OCM coordination (" + trigger + "): " + gate.describe());
        if (gate.mode() == OcmGate.Mode.BOUND) {
            logger.info("  Ownership is per player: wherever a decider's OCM modeset enables one of those"
                    + " modules, Mental yields that mechanic for that interaction (attacker decides melee"
                    + " knockback, tool damage, crits; the rodder decides fishing; the victim decides"
                    + " thrown-projectile knockback).");
            logger.info("  To let Mental own a mechanic, remove the OCM module from the relevant modeset"
                    + " (or disable it in OCM's config). Mental's 1.7.10 combos, positional projectile"
                    + " knockback and ping compensation apply only to interactions Mental owns.");
        } else if (gate.mode() == OcmGate.Mode.CONFIG) {
            logger.info("  This OCM build has no service API, so coordination is global and conservative:"
                    + " every mechanic OCM could be handling anywhere is yielded everywhere. Update OCM"
                    + " for per-player (modeset) precision.");
        }
    }
}
