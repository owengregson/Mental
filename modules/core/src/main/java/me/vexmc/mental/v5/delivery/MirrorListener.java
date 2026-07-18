package me.vexmc.mental.v5.delivery;

import java.lang.reflect.Method;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.v5.CombatSession;
import me.vexmc.mental.v5.Vectors;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Mirrors Mental's pending knockback onto Paper's {@code EntityKnockbackEvent}
 * (1.20.6+) — the event mid-pass observers (anticheats, sparring bots) read
 * (spec §3.5). Mental applies at {@code PlayerVelocityEvent} (fired later), so an
 * observer of the earlier knockback event would otherwise see VANILLA's delta,
 * never Mental's; on an airborne combo hit that reads as downward knockback.
 *
 * <p>It asks the victim's desk for the SAME pending decision object
 * ({@code mirrorView()}, non-consuming) and sets {@code knockback = target −
 * current}, so it cannot diverge from the authoritative apply — there is no
 * second source of truth. It does NOT fire {@code KnockbackApplyEvent} and does
 * NOT consume the pending; the velocity event stays the single authoritative
 * apply, so the final wire velocity is unchanged (era-exact). Registered only
 * when the capability exists, reflectively (the event postdates the floor API);
 * a no-op when nothing is pending — all of 4A1 — so zero-touch holds.</p>
 */
public final class MirrorListener implements Listener {

    private static final String EVENT_CLASS = "io.papermc.paper.event.entity.EntityKnockbackEvent";

    private final SessionService sessions;
    private Method setKnockback;

    public MirrorListener(SessionService sessions) {
        this.sessions = sessions;
    }

    /**
     * Registers the reflective handler at HIGH/ignoreCancelled. The caller gates
     * on {@code Capabilities.knockbackEvent}; if the event is somehow still absent
     * the mirror logs and stands down (observers fall back to vanilla's delta; the
     * real client is unaffected).
     */
    public void register(Plugin plugin) {
        try {
            Class<?> eventClass = Class.forName(EVENT_CLASS);
            this.setKnockback = eventClass.getMethod("setKnockback", Vector.class);
            @SuppressWarnings("unchecked")
            Class<? extends Event> typed = (Class<? extends Event>) eventClass;
            EventExecutor executor = (listener, event) -> handle(event);
            Bukkit.getPluginManager().registerEvent(
                    typed, this, EventPriority.HIGH, executor, plugin, /* ignoreCancelled */ true);
        } catch (ReflectiveOperationException unavailable) {
            plugin.getLogger().warning(
                    "EntityKnockbackEvent mirror unavailable; mid-pass observers will see vanilla"
                            + " knockback on this server: " + unavailable);
        }
    }

    private void handle(Event event) {
        try {
            if (!(event instanceof EntityEvent entityEvent)
                    || !(entityEvent.getEntity() instanceof Player victim)) {
                return;
            }
            CombatSession session = sessions.sessionFor(victim.getUniqueId());
            if (session == null) {
                return;
            }
            KnockbackVector target = session.desk().mirrorView();
            if (target == null) {
                return; // no Mental-owned decision pending — leave vanilla's knockback
            }
            // The event applies setDeltaMovement(current + knockback); set the delta
            // so the result is Mental's value. getVelocity() is region-safe here —
            // the event fires on the victim's owning region thread.
            Vector delta = Vectors.toBukkit(target).subtract(victim.getVelocity());
            setKnockback.invoke(event, delta);
        } catch (Throwable bestEffort) {
            // A missed mirror only changes what an observer sees; the real client
            // still gets Mental's value at PlayerVelocityEvent. Never surface into
            // the synchronous damage dispatch.
        }
    }
}
