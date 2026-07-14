package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import java.util.function.Supplier;
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
    private final Supplier<Snapshot> snapshotSupplier;
    private final FeedbackTrace trace;
    private final Logger logger;

    public DeathEffectsUnit(
            ServerEnvironment environment, Scheduling scheduling, Supplier<Snapshot> snapshotSupplier,
            FeedbackTrace trace, Logger logger) {
        this.environment = environment;
        this.scheduling = scheduling;
        this.snapshotSupplier = snapshotSupplier;
        this.trace = trace;
        this.logger = logger;
    }

    @Override
    public Feature descriptor() {
        return Feature.DEATH_EFFECTS;
    }

    /** Sounds/particles/blast resolve into the listener at assemble — a settings reload must re-assemble. */
    @Override
    public boolean rebuildOnSettingsChange() {
        return true;
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
        // The kill title's wire split: 1.17 replaced the single combined title
        // packet with three dedicated Set-Title-{Times,Text,Subtitle} packets.
        // Resolve the shape ONCE here; the death path never touches the version.
        boolean splitTitlePackets = serverVersion.isNewerThanOrEquals(ServerVersion.V_1_17);
        FeedbackSoundTable table = new FeedbackSoundTable(
                environment.major(), environment.minor(), inlineByName);

        // {PROTECT_SECONDS} in the kill title reads the Drop Protection window
        // LIVE (blank when that feature is off), so a drop-protection reload is
        // reflected without re-assembling death-effects.
        Supplier<String> protectSecondsToken = () -> protectSecondsToken(snapshotSupplier.get());

        DeathLightning lightningTasks = new DeathLightning(scheduling);
        // The blast rocket's item-data flavor (1.20.5+ component vs classic NBT)
        // and its metadata-slot flavor (Optional<ItemStack> below 1.11) resolve
        // HERE, once, off the PacketEvents server version — never on the death path.
        DeathFirework firework = new DeathFirework(settings.fireworkColors(), serverVersion);
        scope.listen(new DeathEffectsListener(
                settings, table, modernBlockData, lightningSupported, lightningTasks, firework,
                splitTitlePackets, protectSecondsToken, trace, logger));
        scope.task(() -> lightningTasks); // the registry closes with the scope, cancelling pending destroys
    }

    /**
     * The {@code {PROTECT_SECONDS}} token value — the configured Drop Protection
     * window in whole seconds, or {@code ""} when that feature is off. Part B
     * (drop-protection) fills the live read; until then the feature does not
     * exist, so the token is correctly blank.
     */
    private static String protectSecondsToken(Snapshot snapshot) {
        return "";
    }
}
