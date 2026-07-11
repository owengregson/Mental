package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import java.util.logging.Logger;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.ServerEnvironment;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.DeathEffectsSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;

/**
 * Assembles {@code death-effects}: the PlayerDeathEvent MONITOR emitter
 * ({@code scope.listen}) plus the scope-owned {@link DeathLightning} destroy-task
 * registry ({@code scope.task}), which the scope closes as a unit — so the module
 * is zero-touch when disabled and leaves nothing scheduled.
 *
 * <p>The era-correct {@link FeedbackSoundTable} is built here, once, from the
 * running server's version (the same posture as {@code hit-feedback}): the
 * {@code (major, minor)} tuple comes from {@link ServerEnvironment} and the
 * {@code inlineByName} gate from the PacketEvents server version at 1.19.3.
 * Modern block-state / colored-dust particle data needs 1.13+; the cosmetic
 * lightning bolt needs 1.19+ — both gates resolve here and print one boot-report
 * line on the fallback path.</p>
 */
public final class DeathEffectsUnit implements FeatureUnit {

    private final ServerEnvironment environment;
    private final Scheduling scheduling;
    private final FeedbackTrace trace;
    private final Logger logger;

    public DeathEffectsUnit(
            ServerEnvironment environment, Scheduling scheduling, FeedbackTrace trace, Logger logger) {
        this.environment = environment;
        this.scheduling = scheduling;
        this.trace = trace;
        this.logger = logger;
    }

    @Override
    public Feature descriptor() {
        return Feature.DEATH_EFFECTS;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void assemble(Scope scope, Snapshot snapshot) {
        DeathEffectsSettings settings = snapshot.settings(
                (SettingsKey<DeathEffectsSettings>) Feature.DEATH_EFFECTS.settingsKey());

        ServerVersion serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        boolean inlineByName = serverVersion.isNewerThanOrEquals(ServerVersion.V_1_19_3);
        boolean modernBlockData = environment.isAtLeast(1, 13, 0);
        boolean lightningSupported = environment.isAtLeast(1, 19, 0);
        FeedbackSoundTable table = new FeedbackSoundTable(
                environment.major(), environment.minor(), inlineByName);

        DeathLightning lightningTasks = new DeathLightning(scheduling);
        // The blast rocket's item-data flavor (1.20.5+ component vs classic NBT)
        // and its metadata-slot flavor (Optional<ItemStack> below 1.11) resolve
        // HERE, once, off the PacketEvents server version — never on the death path.
        DeathFirework firework = new DeathFirework(settings.fireworkColors(), serverVersion);
        scope.listen(new DeathEffectsListener(
                settings, table, modernBlockData, lightningSupported, lightningTasks, firework,
                trace, logger));
        scope.task(() -> lightningTasks); // the registry closes with the scope, cancelling pending destroys
    }
}
