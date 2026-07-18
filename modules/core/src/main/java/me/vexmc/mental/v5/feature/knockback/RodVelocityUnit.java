package me.vexmc.mental.v5.feature.knockback;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import me.vexmc.mental.kernel.math.RodLaunchMath;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.v5.Vectors;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.util.Vector;

/**
 * 1.7.10 rod cast feel (the retired {@code FishingRodVelocityModule} on the v5
 * seams): the legacy launch velocity (with its exact gaussian spread) and a
 * per-hook −0.01/tick gravity adjustment while airborne — modern hooks fall under
 * 0.03 gravity, legacy hooks fell under 0.04. Each hook gets its own scheduled
 * task on the hook's scheduler (its region thread on Folia); the tasks are tracked
 * so a disable cancels them.
 */
public final class RodVelocityUnit implements FeatureUnit, Listener {

    private static final double GRAVITY_ADJUSTMENT = 0.01;

    private final Scheduling scheduling;
    private final Set<TaskHandle> gravityTasks = ConcurrentHashMap.newKeySet();

    public RodVelocityUnit(Scheduling scheduling) {
        this.scheduling = scheduling;
    }

    @Override
    public Feature descriptor() {
        return Feature.ROD_VELOCITY;
    }

    @Override
    public void assemble(Scope scope, Snapshot snapshot) {
        scope.listen(this);
        scope.task(() -> () -> {
            gravityTasks.forEach(TaskHandle::cancel);
            gravityTasks.clear();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCast(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.FISHING) {
            return;
        }
        FishHook hook = event.getHook();
        Location eyes = event.getPlayer().getLocation();
        KnockbackVector launch = RodLaunchMath.launch(eyes.getYaw(), eyes.getPitch());
        hook.setVelocity(Vectors.toBukkit(launch));

        TaskHandle[] holder = new TaskHandle[1];
        holder[0] = scheduling.repeatOn(hook, 1L, 1L,
                () -> applyGravity(hook),
                () -> {
                    if (holder[0] != null) {
                        gravityTasks.remove(holder[0]);
                    }
                });
        gravityTasks.add(holder[0]);
    }

    private void applyGravity(FishHook hook) {
        if (hook.isInWater() || hook.isOnGround()) {
            return;
        }
        Vector velocity = hook.getVelocity();
        velocity.setY(velocity.getY() - GRAVITY_ADJUSTMENT);
        hook.setVelocity(velocity);
    }
}
