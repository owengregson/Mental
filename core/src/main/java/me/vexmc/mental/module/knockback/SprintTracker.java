package me.vexmc.mental.module.knockback;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Sprint freshness per attacker — the signal behind a profile's
 * {@code wtap-extra} split.
 *
 * <p>An attacker is "fresh" when they have (re-)engaged sprint since their
 * last sprint hit: the START_SPRINTING that follows a w-tap (or the server's
 * own post-hit sprint reset) arms the flag, and the next sprint hit consumes
 * it. A sprint held continuously across hits therefore reads as not-fresh —
 * exactly WindSpigot's {@code isExtraKnockback} branch, derived from the only
 * honest server-side signal there is.</p>
 *
 * <p>Observation only: with every profile's {@code wtap-extra} disabled this
 * tracker changes nothing, preserving the zero-touch guarantee. Writes happen
 * on owning threads (Bukkit events); the netty pre-send merely peeks, which
 * the concurrent set makes safe.</p>
 */
public final class SprintTracker implements Listener {

    /** A faithful client's post-attack sprint drop lands within ~a tick. */
    private static final long ATTACK_STAMP_TTL_NANOS = 150_000_000L;

    private record AttackStamp(boolean sprinting, long nanos) {}

    private final Set<UUID> fresh = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, AttackStamp> attackStamps = new ConcurrentHashMap<>();

    @EventHandler
    public void onToggleSprint(@NotNull PlayerToggleSprintEvent event) {
        if (event.isSprinting()) {
            fresh.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        fresh.remove(event.getPlayer().getUniqueId());
        attackStamps.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Stamps the attacker's sprint state as the cancelled attack packet saw
     * it (the tick-frozen snapshot, netty thread). Vanilla read the flag
     * INSIDE {@code Player.attack} — ahead of the client's own post-attack
     * STOP_SPRINTING sync — while the fast path's deferred damage runs after
     * the inbound queue and would lose that race: a perfectly-timed sprint
     * hit would ship plain.
     */
    public void stampAttackSprint(@NotNull UUID attacker, boolean sprinting) {
        attackStamps.put(attacker, new AttackStamp(sprinting, System.nanoTime()));
    }

    /**
     * One-shot read of the attack-time sprint state; {@code null} when no
     * fast-path stamp exists (vanilla-path hits read the live flag, which
     * vanilla's inline attack already evaluated correctly).
     */
    public @Nullable Boolean takeAttackSprint(@NotNull UUID attacker) {
        AttackStamp stamp = attackStamps.remove(attacker);
        if (stamp == null || System.nanoTime() - stamp.nanos() > ATTACK_STAMP_TTL_NANOS) {
            return null;
        }
        return stamp.sprinting();
    }

    /** Arms freshness directly — the toggle-event path, exposed for tests. */
    public void arm(@NotNull UUID attacker) {
        fresh.add(attacker);
    }

    /** Read-only check for the netty pre-send prediction. */
    public boolean peekFresh(@NotNull UUID attacker) {
        return fresh.contains(attacker);
    }

    /** The authoritative read: reports freshness and spends it. */
    public boolean consumeFresh(@NotNull UUID attacker) {
        return fresh.remove(attacker);
    }

    public void clear() {
        fresh.clear();
    }
}
