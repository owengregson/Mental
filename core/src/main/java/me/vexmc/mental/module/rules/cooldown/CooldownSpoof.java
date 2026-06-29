package me.vexmc.mental.module.rules.cooldown;

import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.Property;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure packet-mutation helper: rewrites the {@code attack_speed} attribute to
 * a value so high that the client's cooldown delay rounds to zero, giving
 * every swing the appearance of a fully-charged hit.
 *
 * <p>The client computes the cooldown overlay from the {@code attack_speed}
 * attribute it received for <em>its own entity</em>:
 * {@code delay = (1 / attack_speed) * 20}; at 1024.0 the delay is
 * {@code ~0.02} ticks — effectively zero — so {@code getAttackStrengthScale}
 * always returns 1.0: no greyed-out swing, no charge bar.
 *
 * <p>This is only the CLIENT overlay half of cooldown removal. The DAMAGE ramp
 * lives server-side ({@code Player#attack} scales by the attack-strength), so
 * {@link ServerAttackSpeed} raises the player's SERVER {@code attack_speed} base
 * to this same {@link #FULL_CHARGE_ATTACK_SPEED} (with quit/disable teardown).
 * Once it has, the server's own {@code UPDATE_ATTRIBUTES} already carries the
 * high value to the client, so this spoof is mostly a join-window backstop —
 * but it stays registered so the overlay is suppressed even on that first sync.
 * See {@link AttackCooldownModule} for the two-halves design and
 * {@link ServerAttackSpeed} for why a client-only spoof left mob / fast-path-off
 * hits scaled to ~20%.
 *
 * <p>Era truth: 1.7.10 and 1.8.9 had no attack-speed attribute at all
 * ({@code generic.attackSpeed} does not exist in 1.8.9 GenericAttributes);
 * this spoof restores that feel on modern clients.
 *
 * <p>Cross-version: the wire key is {@code generic.attack_speed}
 * (≤ 1.21.1) vs {@code attack_speed} (1.21.2+). PacketEvents
 * {@link Attributes#ATTACK_SPEED} resolves correctly for both ranges, and
 * {@link Attributes#GENERIC_ATTACK_SPEED} is a compile-time alias to the
 * same constant, so identity comparison covers all versions.
 */
public final class CooldownSpoof {

    /**
     * A value large enough to make {@code getCurrentItemAttackStrengthDelay}
     * round to zero on the client ({@code delay = (1/1024) * 20 ≈ 0.02}).
     * 1 024.0 is well inside {@code double} range and has never been observed
     * to be clamped by PacketEvents' {@code Attribute.sanitizeValue} (which
     * only enforces the attribute's own registered minimum/maximum, neither
     * of which apply here — the test pins would catch a regression).
     */
    public static final double FULL_CHARGE_ATTACK_SPEED = 1024.0;

    private CooldownSpoof() {}

    /**
     * Finds the {@code attack_speed} {@link Property} in {@code packet} and
     * rewrites its value to {@link #FULL_CHARGE_ATTACK_SPEED}; if no such
     * property is present, injects a new one.
     *
     * <p>Matching priority:
     * <ol>
     *   <li>{@code getAttribute() == Attributes.ATTACK_SPEED} — covers both
     *       the {@code attack_speed} (1.21.2+) and the
     *       {@code generic.attack_speed} (≤ 1.21.1) wire keys because
     *       {@link Attributes#GENERIC_ATTACK_SPEED} is the same constant.
     *   <li>Fallback: {@code getKey()} non-null and ends with
     *       {@code "attack_speed"} — defensive; PacketEvents may expose a
     *       raw key string for unknown attributes on exotic versions.
     * </ol>
     *
     * @param packet the UPDATE_ATTRIBUTES wrapper to mutate in-place
     * @return {@code true} if the packet was mutated and must be re-encoded,
     *         {@code false} if it was already at FULL_CHARGE (no-op)
     */
    public static boolean forceFullAttackSpeed(
            WrapperPlayServerUpdateAttributes packet) {
        List<Property> properties = packet.getProperties();

        for (Property property : properties) {
            if (isAttackSpeed(property)) {
                if (property.getValue() == FULL_CHARGE_ATTACK_SPEED) {
                    return false; // already spoofed — no-op
                }
                property.setValue(FULL_CHARGE_ATTACK_SPEED);
                return true;
            }
        }

        // attack_speed not present in this packet — inject it so the client
        // receives the spoof value even when vanilla never re-sends the
        // attribute after spawn (e.g. attribute update batches that omit it).
        List<Property> mutable = new ArrayList<>(properties);
        mutable.add(new Property(Attributes.ATTACK_SPEED, FULL_CHARGE_ATTACK_SPEED, List.of()));
        packet.setProperties(mutable);
        return true;
    }

    private static boolean isAttackSpeed(Property property) {
        // Identity check first (covers both ATTACK_SPEED and GENERIC_ATTACK_SPEED,
        // which are the same constant).
        if (property.getAttribute() == Attributes.ATTACK_SPEED
                || property.getAttribute() == Attributes.GENERIC_ATTACK_SPEED) {
            return true;
        }
        // Defensive fallback for exotic versions where getAttribute() may
        // return a synthesised Attribute not identical to our constant.
        String key = property.getKey();
        return key != null && key.endsWith("attack_speed");
    }
}
