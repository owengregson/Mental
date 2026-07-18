package me.vexmc.mental.v5.feature.cadence;

import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.Property;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure packet-mutation helper (the retired {@code module.rules.cooldown.CooldownSpoof}
 * on the v5 seam): rewrites the {@code attack_speed} attribute to a value so high
 * that the client's cooldown delay rounds to zero, giving every swing the
 * appearance of a fully-charged hit — no charge bar, no greyed-out overlay.
 *
 * <p>The client computes the cooldown overlay from the {@code attack_speed}
 * attribute it received for <em>its own entity</em>:
 * {@code delay = (1 / attack_speed) * 20}; at 1024.0 the delay is {@code ~0.02}
 * ticks, so {@code getAttackStrengthScale} always returns 1.0. This is the CLIENT
 * presentation half of cooldown removal (mandate B5(b)); it mutates ONLY the
 * packet-local wrapper's property list (never shared/live NMS state — B10). The
 * SERVER damage-ramp half lives in {@link AttackChargeReset}.</p>
 *
 * <p>Era truth: 1.7.10 and 1.8.9 had no {@code attack_speed} attribute at all
 * ({@code generic.attackSpeed} does not exist in 1.8.9 GenericAttributes); this
 * spoof restores that feel on modern clients.</p>
 *
 * <p>Cross-version: the wire key renamed from {@code generic.attack_speed}
 * (≤ 1.21.1) to {@code attack_speed} (1.21.2+). PacketEvents
 * {@link Attributes#ATTACK_SPEED} resolves correctly for both ranges by IDENTITY
 * (never a raw string), and {@link Attributes#GENERIC_ATTACK_SPEED} is a
 * compile-time alias to the same constant, so identity comparison covers all
 * versions (mandate B5(b)).</p>
 */
public final class CooldownSpoof {

    /**
     * A value large enough to make {@code getCurrentItemAttackStrengthDelay}
     * round to zero on the client ({@code delay = (1/1024) * 20 ≈ 0.02}).
     * 1 024.0 is inside {@code double} range and is the {@code attack_speed}
     * RangedAttribute's registered ceiling on every supported version, so it is
     * never clamped lower.
     */
    public static final double FULL_CHARGE_ATTACK_SPEED = 1024.0;

    private CooldownSpoof() {}

    /**
     * Finds the {@code attack_speed} {@link Property} in {@code packet} (by the
     * {@link Attributes#ATTACK_SPEED} identity — the wire key renamed at 1.21.2)
     * and rewrites its value to {@link #FULL_CHARGE_ATTACK_SPEED}; if no such
     * property is present, injects one so the client still receives the spoof
     * value when vanilla omits it from a later attribute batch.
     *
     * @param packet the UPDATE_ATTRIBUTES wrapper to mutate in-place
     * @return {@code true} if the packet was mutated and must be re-encoded,
     *         {@code false} if it was already at FULL_CHARGE (a no-op)
     */
    public static boolean forceFullAttackSpeed(WrapperPlayServerUpdateAttributes packet) {
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
        // attribute after spawn (attribute batches that omit it).
        List<Property> mutable = new ArrayList<>(properties);
        mutable.add(new Property(Attributes.ATTACK_SPEED, FULL_CHARGE_ATTACK_SPEED, List.of()));
        packet.setProperties(mutable);
        return true;
    }

    private static boolean isAttackSpeed(Property property) {
        // Identity check first (covers both ATTACK_SPEED and GENERIC_ATTACK_SPEED,
        // which are the same constant — the wire key renamed at 1.21.2).
        if (property.getAttribute() == Attributes.ATTACK_SPEED
                || property.getAttribute() == Attributes.GENERIC_ATTACK_SPEED) {
            return true;
        }
        // Defensive fallback for exotic versions where getAttribute() may return
        // a synthesised Attribute not identical to our constant.
        String key = property.getKey();
        return key != null && key.endsWith("attack_speed");
    }
}
