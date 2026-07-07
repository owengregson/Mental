package me.vexmc.mental.v5.feature.knockback;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.kernel.delivery.HitTransaction;
import me.vexmc.mental.kernel.math.KnockbackEngine;
import me.vexmc.mental.kernel.model.EntityState;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.kernel.profile.KnockbackProfile;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.EntityStates;
import me.vexmc.mental.v5.Vectors;
import me.vexmc.mental.v5.config.ReelInPolicy;
import me.vexmc.mental.v5.config.settings.FishingKnockbackSettings;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.delivery.HitIds;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.util.Vector;

/**
 * 1.7.10 rod combat (the retired {@code FishingKnockbackModule} on the v5 seams).
 * A bobber striking a living entity is a real zero-damage hit: the negligible
 * damage runs through the vanilla pipeline (region/protection vetoes hold, the
 * 20-tick hurt window arms), and the knock is the engine's bare base 0.4/0.4
 * <em>away from where the angler stands</em> — the hook's own position never
 * enters the math.
 *
 * <p>The hit is minted as a typed {@link HitSource.RodPull} and placed in the
 * session's inbound slot BEFORE {@code victim.damage(rodder)} so the melee unit
 * never mistakes the ENTITY_ATTACK for melee (B6). The rod vector is submitted to
 * the desk and ensured next tick, so a version whose zero-damage hit fires no
 * velocity event still delivers.</p>
 */
public final class FishingKnockbackUnit implements FeatureUnit, Listener {

    private static final String CITIZENS_NPC_METADATA = "NPC";
    private static final double REEL_PULL_FACTOR = 0.1;
    private static final double REEL_LIFT_FACTOR = 0.08;

    private final SessionService sessions;
    private final Scheduling scheduling;
    private final Supplier<Snapshot> snapshot;
    private final HitIds ids;
    private final TickClock clock;
    private final boolean folia;

    public FishingKnockbackUnit(
            SessionService sessions, Scheduling scheduling,
            Supplier<Snapshot> snapshot, HitIds ids, TickClock clock, boolean folia) {
        this.sessions = sessions;
        this.scheduling = scheduling;
        this.snapshot = snapshot;
        this.ids = ids;
        this.clock = clock;
        this.folia = folia;
    }

    @Override
    public Feature descriptor() {
        return Feature.FISHING_KNOCKBACK;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
    }

    @SuppressWarnings("unchecked")
    private FishingKnockbackSettings settings() {
        return snapshot.get().settings(
                (SettingsKey<FishingKnockbackSettings>) Feature.FISHING_KNOCKBACK.settingsKey());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        try {
            handleRodHit(event);
        } catch (Throwable offRegionOrOther) {
            // A long cross-region cast on Folia trips the off-region guard; degrade
            // to no rod knock for that cast rather than spamming the log.
            if (!folia) {
                throw new RuntimeException(offRegionOrOther);
            }
        }
    }

    private void handleRodHit(ProjectileHitEvent event) {
        FishingKnockbackSettings settings = settings();
        if (!(event.getEntity() instanceof FishHook)
                || !(event.getEntity().getShooter() instanceof Player rodder)
                || !(event.getHitEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (victim.getUniqueId().equals(rodder.getUniqueId())
                || rodder.getGameMode() == GameMode.CREATIVE
                || victim.hasMetadata(CITIZENS_NPC_METADATA)) {
            return;
        }
        if (!(victim instanceof Player) && !settings.knockbackNonPlayerEntities()) {
            return;
        }
        if (victim instanceof Player victimPlayer
                && (victimPlayer.getGameMode() == GameMode.CREATIVE
                        || victimPlayer.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }
        if (victim.getNoDamageTicks() > victim.getMaximumNoDamageTicks() / 2.0) {
            return;
        }

        CombatSession session = victim instanceof Player p ? sessions.sessionFor(p.getUniqueId()) : null;
        HitTransaction tx = mintRodPull(rodder, victim);
        // Deal the hit through the vanilla pipeline with the RodPull slot set, so
        // the melee unit sees a typed rod source and yields (B6). Cleared on exit.
        if (session != null) {
            session.activeInbound(tx);
        }
        try {
            victim.damage(settings.damage(), rodder);
        } finally {
            if (session != null) {
                session.clearActiveInbound();
            }
        }
        if (victim.getNoDamageTicks() <= victim.getMaximumNoDamageTicks() / 2.0) {
            return; // the hit was cancelled / did not register — no knock
        }

        Location angler = rodder.getLocation();
        KnockbackProfile profile = profileFor(victim);
        EntityState victimState = victim instanceof Player pv
                ? EntityStates.captureVictim(pv, session.ledger())
                : EntityStates.capture(victim);
        KnockbackVector vector = KnockbackEngine.computeBase(
                victimState, angler.getX(), angler.getZ(), profile, null, ThreadLocalRandom.current());

        if (victim instanceof Player victimPlayer && session != null) {
            session.desk().submit(tx, vector);
            session.desk().awaitVelocityEvent(tx);
            scheduleEnsure(victimPlayer, session, tx);
        } else if (vector != null) {
            victim.setVelocity(Vectors.toBukkit(vector));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReelIn(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) {
            return;
        }
        FishingKnockbackSettings settings = settings();
        Entity caught = event.getCaught();
        if (caught == null || settings.reelIn() == ReelInPolicy.VANILLA) {
            return;
        }
        event.setCancelled(true);
        event.getHook().remove();
        if (settings.reelIn() == ReelInPolicy.CANCEL) {
            return;
        }
        Vector pull = legacyPull(event.getPlayer().getLocation(), caught.getLocation());
        caught.setVelocity(caught.getVelocity().add(pull));
    }

    private HitTransaction mintRodPull(Player rodder, LivingEntity victim) {
        HitContext context = new HitContext(
                ids.next(), new HitSource.RodPull(), rodder.getUniqueId(), victim.getUniqueId(),
                new SprintVerdict(false, null, clock.current()), false, null, clock.current());
        return new HitTransaction(context);
    }

    /**
     * Force the rod knock next tick if no vanilla velocity event delivered it.
     * The rod's {@code victim.damage(rodder)} normally sets hurtMarked, so a
     * tracker velocity event fires this tick and the {@code DeskRouter} resolves
     * it (undecayed) — this fallback then no-ops. When it IS needed, a bare
     * {@code setVelocity} would ship the value one physics tick decayed, so we
     * re-submit fresh (surviving this tick's sweep) and trigger a velocity event
     * the {@code DeskRouter} overrides to the full stamp.
     */
    private void scheduleEnsure(Player victim, CombatSession session, HitTransaction tx) {
        scheduling.runOn(victim, () -> {
            KnockbackVector base = session.desk().pendingVectorFor(tx.context().id());
            if (base == null) {
                return; // a vanilla velocity event already resolved (and shipped) it
            }
            HitTransaction fresh = new HitTransaction(new HitContext(
                    ids.next(), tx.context().source(), tx.context().attackerId(), victim.getUniqueId(),
                    new SprintVerdict(false, null, clock.current()), false, null, clock.current()));
            session.desk().submit(fresh, base);
            session.desk().awaitVelocityEvent(fresh);
            victim.setVelocity(Vectors.toBukkit(base));
        }, () -> {});
    }

    private KnockbackProfile profileFor(LivingEntity victim) {
        return snapshot.get().profileFor(victim.getWorld().getName());
    }

    /** {@code Δ × 0.1} per axis plus {@code √distance × 0.08} of lift — 1.7.10 handleHookRetraction. */
    static Vector legacyPull(Location angler, Location caught) {
        double deltaX = angler.getX() - caught.getX();
        double deltaY = angler.getY() - caught.getY();
        double deltaZ = angler.getZ() - caught.getZ();
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        return new Vector(
                deltaX * REEL_PULL_FACTOR,
                deltaY * REEL_PULL_FACTOR + Math.sqrt(distance) * REEL_LIFT_FACTOR,
                deltaZ * REEL_PULL_FACTOR);
    }
}
