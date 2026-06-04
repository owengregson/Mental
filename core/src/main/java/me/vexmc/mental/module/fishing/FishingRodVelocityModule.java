package me.vexmc.mental.module.fishing;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.engine.CombatModule;
import me.vexmc.mental.module.knockback.KnockbackVector;
import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * 1.8 rod cast feel: launch velocity from the 1.8 formula (with its exact
 * gaussian spread), then a per-hook gravity adjustment of −0.01/tick while
 * airborne — modern hooks fall under 0.03 gravity, 1.8's fell under 0.04.
 *
 * <p>Each hook gets its own scheduled task on the hook's scheduler: on Folia
 * that is the hook's region thread and the task retires automatically with
 * the entity; on Paper it is the main thread with a validity guard. No global
 * tick loop, no shared mutable hook set beyond bookkeeping for disable.</p>
 */
public final class FishingRodVelocityModule extends CombatModule implements Listener {

    private static final double GRAVITY_ADJUSTMENT = 0.01;

    private final Set<TaskHandle> gravityTasks = ConcurrentHashMap.newKeySet();

    public FishingRodVelocityModule(@NotNull MentalServices services) {
        super(services, "rod-velocity", "Rod Velocity",
                "1.8 cast speed, spread, and in-flight hook gravity.",
                DebugCategory.FISHING);
    }

    @Override
    public boolean configEnabled() {
        return services.config().rodVelocity().enabled();
    }

    @Override
    protected void onEnable() {
        listen(this);
    }

    @Override
    protected void onDisable() {
        gravityTasks.forEach(TaskHandle::cancel);
        gravityTasks.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCast(@NotNull PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.FISHING
                || !services.config().rodVelocity().enabled()) {
            return;
        }
        FishHook hook = event.getHook();
        Location eyes = event.getPlayer().getLocation();
        KnockbackVector launch = RodLaunchMath.launch(eyes.getYaw(), eyes.getPitch());
        hook.setVelocity(launch.toBukkit());

        TaskHandle[] holder = new TaskHandle[1];
        holder[0] = services.scheduling().repeatOn(
                hook, 1L, 1L,
                () -> applyGravity(hook),
                () -> {
                    if (holder[0] != null) {
                        gravityTasks.remove(holder[0]);
                    }
                });
        gravityTasks.add(holder[0]);

        debug.log(() -> event.getPlayer().getName() + " cast at ("
                + launch.x() + ", " + launch.y() + ", " + launch.z() + ")");
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
