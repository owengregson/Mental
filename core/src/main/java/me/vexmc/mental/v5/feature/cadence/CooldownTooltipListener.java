package me.vexmc.mental.v5.feature.cadence;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.v5.platform.WeaponTooltipAdapter;

/**
 * The tooltip-hider half of attack-cooldown removal (mandate B5(c)): strips the
 * spoofed "Attack Speed" line from outbound SET_SLOT / WINDOW_ITEMS item copies
 * via the boot-probed {@link WeaponTooltipAdapter} on the {@code PlatformProbe}.
 *
 * <p>Display-only and packet-local: the PE item is converted to a Bukkit COPY,
 * the copy's attribute set is rewritten, and the copy is converted back onto the
 * packet — the real server-side stack is never touched (B10), so it needs no
 * teardown beyond unregistration. Registered ONLY while the {@code AttackCooldownUnit}
 * scope is open (no split-brain — the packet half dies with the scope), so it
 * needs no config-flag guard. Netty-thread safe. Tooltips are client-rendered, so
 * the effect is verified by the operator's client, not the integration matrix.</p>
 */
public final class CooldownTooltipListener extends PacketListenerAbstract {

    private final WeaponTooltipAdapter adapter;

    public CooldownTooltipListener(WeaponTooltipAdapter adapter) {
        super(PacketListenerPriority.NORMAL);
        this.adapter = adapter;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        Object type = event.getPacketType();
        if (PacketType.Play.Server.SET_SLOT.equals(type)) {
            try {
                WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
                ItemStack hidden = rewrite(wrapper.getItem());
                if (hidden != null) {
                    wrapper.setItem(hidden);
                    event.markForReEncode(true);
                }
            } catch (Exception ignored) {
                // Never let a conversion failure propagate on the netty thread.
            }
            return;
        }
        if (PacketType.Play.Server.WINDOW_ITEMS.equals(type)) {
            try {
                WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
                List<ItemStack> items = wrapper.getItems();
                List<ItemStack> rewritten = new ArrayList<>(items.size());
                boolean changed = false;
                for (ItemStack item : items) {
                    ItemStack hidden = rewrite(item);
                    if (hidden != null) {
                        rewritten.add(hidden);
                        changed = true;
                    } else {
                        rewritten.add(item);
                    }
                }
                if (changed) {
                    wrapper.setItems(rewritten);
                    event.markForReEncode(true);
                }
            } catch (Exception ignored) {
                // Never let a conversion failure propagate on the netty thread.
            }
        }
    }

    /** A display copy with the attack-speed line stripped, or {@code null} for no change. */
    private ItemStack rewrite(ItemStack peStack) {
        if (peStack == null || peStack.isEmpty()) {
            return null;
        }
        org.bukkit.inventory.ItemStack bukkit = SpigotConversionUtil.toBukkitItemStack(peStack);
        if (bukkit == null) {
            return null;
        }
        org.bukkit.inventory.ItemStack stripped = adapter.stripAttackSpeed(bukkit);
        return stripped == null ? null : SpigotConversionUtil.fromBukkitItemStack(stripped);
    }
}
