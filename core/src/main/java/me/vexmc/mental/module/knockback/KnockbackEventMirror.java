package me.vexmc.mental.module.knockback;

import java.lang.reflect.Method;
import java.util.function.Supplier;
import me.vexmc.mental.MentalServices;
import me.vexmc.mental.common.debug.DebugCategory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Makes Mental's knockback authoritative at Paper's {@code EntityKnockbackEvent}
 * (1.20.6+), the event mid-pass observers read.
 *
 * <p>Mental applies its knockback at {@code PlayerVelocityEvent}, which the
 * entity tracker fires LATER — just before the velocity packet. Vanilla computes
 * a knockback delta earlier, inside {@code LivingEntity.knockback(...)}, and
 * fires {@code EntityKnockbackEvent} there with that delta. Anything reading the
 * standard knockback event — a movement-prediction anticheat, another plugin,
 * the SimpleBoxer test harness on Folia — therefore sees VANILLA's value, never
 * Mental's. For an airborne victim vanilla keeps the falling vertical, so that
 * value reads as downward knockback on the airborne combo hit.</p>
 *
 * <p>This listener mirrors the value the velocity event will ship onto the
 * knockback event's delta: it {@linkplain KnockbackPipeline#peekMirror peeks}
 * (never consumes) the victim's head pending and sets {@code knockback =
 * target − currentVelocity}, so vanilla's {@code setDeltaMovement(current +
 * knockback)} lands exactly on Mental's value. The final wire velocity is
 * unchanged — {@code PlayerVelocityEvent} stays the single authoritative apply
 * (API event, duplicate suppressor, ledger record). Registered at {@code HIGH}
 * so it writes before {@code MONITOR} observers, {@code ignoreCancelled} so it
 * yields when a third party already cancelled the knockback.</p>
 *
 * <p>The event class postdates Mental's floor API, so it is resolved and
 * registered reflectively behind {@code Capabilities.knockbackEvent}; only
 * {@code setKnockback(Vector)} needs reflection (the victim comes from the
 * floor-API {@link EntityEvent}, the cancel from {@link Cancellable}). A no-op
 * below 1.20.6 (no listener) and whenever the knockback module is disabled.</p>
 */
public final class KnockbackEventMirror implements Listener {

    private static final String EVENT_CLASS = "io.papermc.paper.event.entity.EntityKnockbackEvent";

    private final MentalServices services;
    private final KnockbackPipeline pipeline;
    private Method setKnockback;

    public KnockbackEventMirror(@NotNull MentalServices services, @NotNull KnockbackPipeline pipeline) {
        this.services = services;
        this.pipeline = pipeline;
    }

    /**
     * Registers the reflective handler. The caller gates on
     * {@code Capabilities.knockbackEvent}; if the event is somehow still absent
     * the mirror logs and stands down — observers fall back to vanilla's delta,
     * the real client is unaffected.
     */
    public void register(@NotNull Plugin plugin) {
        try {
            Class<?> eventClass = Class.forName(EVENT_CLASS);
            this.setKnockback = eventClass.getMethod("setKnockback", Vector.class);
            @SuppressWarnings("unchecked")
            Class<? extends Event> typed = (Class<? extends Event>) eventClass;
            EventExecutor executor = (listener, event) -> handle(event);
            Bukkit.getPluginManager().registerEvent(
                    typed, this, EventPriority.HIGH, executor, plugin, /* ignoreCancelled */ true);
        } catch (ReflectiveOperationException unavailable) {
            services.plugin().getLogger().warning(
                    "EntityKnockbackEvent mirror unavailable; mid-pass observers will see vanilla"
                            + " knockback on this server: " + unavailable);
        }
    }

    private void handle(@NotNull Event event) {
        try {
            if (!services.config().knockback().enabled()) {
                return; // zero-touch: the module owns knockback only when enabled
            }
            if (!(event instanceof EntityEvent entityEvent)
                    || !(entityEvent.getEntity() instanceof Player victim)) {
                return;
            }
            KnockbackPipeline.MirrorDecision decision = pipeline.peekMirror(victim);
            if (decision.cancel()) {
                ((Cancellable) event).setCancelled(true);
                debug(() -> "mirror cancelled the knockback event for " + victim.getName()
                        + " (legacy resistance roll)");
                return;
            }
            Vector target = decision.target();
            if (target == null) {
                return; // NONE: not a Mental-owned hit — leave vanilla's knockback alone
            }
            // The event applies setDeltaMovement(current + knockback); set the
            // delta so the result is Mental's value. getVelocity() is region-safe:
            // the event fires on the victim's owning region thread.
            Vector delta = target.clone().subtract(victim.getVelocity());
            setKnockback.invoke(event, delta);
            debug(() -> "mirrored Mental knockback onto the knockback event for " + victim.getName()
                    + " -> (" + target.getX() + ", " + target.getY() + ", " + target.getZ() + ")");
        } catch (Throwable bestEffort) {
            // A missed mirror only changes what an observer sees; the real client
            // still gets Mental's value at PlayerVelocityEvent. Never let it
            // surface into the synchronous damage dispatch.
            services.debug().log(DebugCategory.KNOCKBACK,
                    () -> "EntityKnockbackEvent mirror skipped: " + bestEffort);
        }
    }

    private void debug(@NotNull Supplier<String> message) {
        services.debug().log(DebugCategory.KNOCKBACK, message);
    }
}
