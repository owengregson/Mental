package me.vexmc.mental.v5.feature.feedback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import java.util.logging.Logger;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.platform.ServerEnvironment;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.HitFeedbackSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;

/**
 * Assembles {@code hit-feedback}: the EDBEE MONITOR emitter ({@code scope.listen})
 * plus the mark-correlated hurt-sound suppressor ({@code scope.packets}), sharing
 * one {@link HurtSoundMarks} ring. Both halves die with the scope, so the module
 * is zero-touch when disabled.
 *
 * <p>The era-correct {@link FeedbackSoundTable} is built here, once, from the
 * running server's version: the {@code (major, minor)} tuple comes from
 * {@link ServerEnvironment} (year-scheme aware) and the {@code inlineByName} gate
 * from the PacketEvents server version at 1.19.3, below which an unknown sound
 * name has no trustworthy id. Modern block-state particle data needs 1.13+; below
 * it, block particles degrade to {@code crit}. The listener logs one boot-report
 * line per fallback or skip it engages while resolving.</p>
 */
public final class HitFeedbackUnit implements FeatureUnit {

    private final ServerEnvironment environment;
    private final TickClock clock;
    private final FeedbackTrace trace;
    private final Logger logger;

    public HitFeedbackUnit(
            ServerEnvironment environment, TickClock clock, FeedbackTrace trace, Logger logger) {
        this.environment = environment;
        this.clock = clock;
        this.trace = trace;
        this.logger = logger;
    }

    @Override
    public Feature descriptor() {
        return Feature.HIT_FEEDBACK;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void assemble(Scope scope, Snapshot snapshot) {
        HitFeedbackSettings settings = snapshot.settings(
                (SettingsKey<HitFeedbackSettings>) Feature.HIT_FEEDBACK.settingsKey());

        HurtSoundMarks marks = new HurtSoundMarks();
        boolean inlineByName = PacketEvents.getAPI().getServerManager()
                .getVersion().isNewerThanOrEquals(ServerVersion.V_1_19_3);
        boolean modernBlockData = environment.isAtLeast(1, 13, 0);
        FeedbackSoundTable table = new FeedbackSoundTable(
                environment.major(), environment.minor(), inlineByName);

        scope.listen(new HitFeedbackListener(settings, table, modernBlockData, marks, clock, trace, logger));
        scope.packets(new HurtSoundSuppressor(marks, clock));
    }
}
