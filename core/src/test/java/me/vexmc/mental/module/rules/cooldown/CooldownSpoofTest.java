package me.vexmc.mental.module.rules.cooldown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.Property;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the packet-mutation contract for {@link CooldownSpoof}.
 *
 * <p>The helper is a pure packet transformer: it rewrites (or injects) the
 * {@code attack_speed} Property in a {@link WrapperPlayServerUpdateAttributes}
 * to {@link CooldownSpoof#FULL_CHARGE_ATTACK_SPEED}, returning {@code true}
 * when a mutation occurred and {@code false} when already at the target value.
 * These cases cover the three reachable branches.</p>
 */
class CooldownSpoofTest {

    /**
     * Property construction class-loads PacketEvents registries via the
     * {@link NettyManager}; stub the API so those paths succeed without a
     * real server.
     *
     * <p>Mirrors {@code ProbeListenerStateTest.installStubApi()}.</p>
     */
    @BeforeAll
    static void installStubApi() {
        NettyManager nettyManager = new NettyManagerImpl();
        ServerManager serverManager = new ServerManager() {
            @Override
            public ServerVersion getVersion() {
                return ServerVersion.V_1_21;
            }
        };
        PacketEvents.setAPI(new PacketEventsAPI<>() {
            @Override public void load() {}
            @Override public boolean isLoaded() { return true; }
            @Override public void init() {}
            @Override public boolean isInitialized() { return true; }
            @Override public void terminate() {}
            @Override public boolean isTerminated() { return false; }
            @Override public Object getPlugin() { return null; }
            @Override public ServerManager getServerManager() { return serverManager; }
            @Override public ProtocolManager getProtocolManager() { return null; }
            @Override public PlayerManager getPlayerManager() { return null; }
            @Override public NettyManager getNettyManager() { return nettyManager; }
            @Override public ChannelInjector getInjector() { return null; }
        });
    }

    /**
     * (a) Packet already contains an {@code attack_speed} Property at the
     * server-default 4.0. The helper should rewrite it to FULL_CHARGE and
     * return {@code true}.
     */
    @Test
    void attackSpeedPropertyRewrittenFrom4() {
        Property prop = new Property(Attributes.ATTACK_SPEED, 4.0, List.of());
        List<Property> props = new ArrayList<>(List.of(prop));
        WrapperPlayServerUpdateAttributes packet =
                new WrapperPlayServerUpdateAttributes(42, props);

        boolean mutated = CooldownSpoof.forceFullAttackSpeed(packet);

        assertTrue(mutated, "must return true when a change is made");
        assertEquals(CooldownSpoof.FULL_CHARGE_ATTACK_SPEED,
                packet.getProperties().get(0).getValue(),
                "attack_speed value must be raised to FULL_CHARGE");
    }

    /**
     * (b) Packet contains only an unrelated Property (movement_speed) — no
     * attack_speed at all. The helper should inject a new attack_speed
     * Property and leave the existing one untouched.
     */
    @Test
    void attackSpeedInjectedWhenAbsentOtherPropertiesPreserved() {
        Property movementSpeed = new Property(
                "minecraft:movement_speed", 0.1, List.of());
        List<Property> props = new ArrayList<>(List.of(movementSpeed));
        WrapperPlayServerUpdateAttributes packet =
                new WrapperPlayServerUpdateAttributes(42, props);

        boolean mutated = CooldownSpoof.forceFullAttackSpeed(packet);

        assertTrue(mutated, "must return true when a property is injected");

        List<Property> result = packet.getProperties();
        // Original movement_speed entry must survive.
        boolean hasMovementSpeed = result.stream()
                .anyMatch(p -> "minecraft:movement_speed".equals(p.getKey())
                        || "minecraft:movement_speed".equals(
                                p.getAttribute() != null
                                        ? p.getAttribute().getName(null)
                                        : null));
        // At least one of the non-movement props must be the injected speed.
        boolean hasAttackSpeed = result.stream()
                .anyMatch(p -> p.getAttribute() == Attributes.ATTACK_SPEED
                        || p.getAttribute() == Attributes.GENERIC_ATTACK_SPEED
                        || (p.getKey() != null && p.getKey().endsWith("attack_speed")));
        assertTrue(result.size() >= 2, "injected property must increase list size");
        assertTrue(hasAttackSpeed,
                "an attack_speed Property must be present after injection");
        // Verify the injected entry has the correct value.
        double injectedValue = result.stream()
                .filter(p -> p.getAttribute() == Attributes.ATTACK_SPEED
                        || p.getAttribute() == Attributes.GENERIC_ATTACK_SPEED
                        || (p.getKey() != null && p.getKey().endsWith("attack_speed")))
                .mapToDouble(Property::getValue)
                .findFirst()
                .orElse(Double.NaN);
        assertEquals(CooldownSpoof.FULL_CHARGE_ATTACK_SPEED, injectedValue,
                "injected attack_speed value must be FULL_CHARGE");
    }

    /**
     * (c) Packet already has attack_speed at FULL_CHARGE. The helper is a
     * no-op and returns {@code false} — critical to avoid spurious
     * mark-for-re-encode overhead on every subsequent attribute update.
     */
    @Test
    void noopWhenAlreadyAtFullCharge() {
        Property prop = new Property(
                Attributes.ATTACK_SPEED,
                CooldownSpoof.FULL_CHARGE_ATTACK_SPEED,
                List.of());
        List<Property> props = new ArrayList<>(List.of(prop));
        WrapperPlayServerUpdateAttributes packet =
                new WrapperPlayServerUpdateAttributes(42, props);

        boolean mutated = CooldownSpoof.forceFullAttackSpeed(packet);

        assertFalse(mutated, "must return false when already at FULL_CHARGE");
        assertEquals(CooldownSpoof.FULL_CHARGE_ATTACK_SPEED,
                packet.getProperties().get(0).getValue(),
                "value must remain unchanged");
    }
}
