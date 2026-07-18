package me.vexmc.mental.v5.feature.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.v5.config.settings.DropProtectionSettings.GlowColor;

/**
 * The live registry of protected item drops — a pure, thread-safe value store
 * with NO Bukkit or PacketEvents dependency, so its pickup-gate and expiry
 * logic is unit-testable in isolation. The {@link DropProtectionUnit} owns the
 * side effects (dropping, glowing, sweeping); this owns only the bookkeeping.
 *
 * <p>Keyed by the item entity's numeric id (the pickup event's cheapest handle);
 * each entry also carries the entity UUID (for the glow team packet), the
 * killer, the tick the protection lifts, and the glow colour chosen at capture.
 * Ticks are an opaque monotonic counter the sweep advances — the store never
 * reads a clock itself.</p>
 */
public final class DropProtectionState {

    /** One protected drop: its entity handles, its owner, when it frees, and its glow colour. */
    public record Protected(int entityId, UUID entityUuid, UUID killerId, long expiryTick, GlowColor color) {}

    private final ConcurrentHashMap<Integer, Protected> byEntityId = new ConcurrentHashMap<>();

    /** Records a drop as protected until {@code expiryTick}. */
    public void protect(int entityId, UUID entityUuid, UUID killerId, long expiryTick, GlowColor color) {
        byEntityId.put(entityId, new Protected(entityId, entityUuid, killerId, expiryTick, color));
    }

    /** Whether {@code entityId} is currently a protected drop. */
    public boolean isProtected(int entityId) {
        return byEntityId.containsKey(entityId);
    }

    /**
     * Whether {@code playerId} may pick up {@code entityId}: always for an
     * unprotected item, and only the KILLER for a protected one (the victim and
     * every third party are blocked until the window elapses).
     */
    public boolean mayPickup(int entityId, UUID playerId) {
        Protected entry = byEntityId.get(entityId);
        return entry == null || entry.killerId().equals(playerId);
    }

    /** Stops tracking one drop (the killer picked it up, or it despawned); returns it, or null. */
    public Protected forget(int entityId) {
        return byEntityId.remove(entityId);
    }

    /**
     * Removes and returns every entry whose window has elapsed at {@code nowTick}
     * — the sweep de-glows exactly these and leaves the rest protected.
     */
    public List<Protected> expire(long nowTick) {
        List<Protected> expired = new ArrayList<>();
        byEntityId.values().removeIf(entry -> {
            if (entry.expiryTick() <= nowTick) {
                expired.add(entry);
                return true;
            }
            return false;
        });
        return expired;
    }

    /** Removes and returns every entry (feature disable / scope teardown). */
    public List<Protected> drain() {
        List<Protected> all = new ArrayList<>(byEntityId.values());
        byEntityId.clear();
        return all;
    }

    /** The number of currently-protected drops (tests / diagnostics). */
    public int size() {
        return byEntityId.size();
    }
}
