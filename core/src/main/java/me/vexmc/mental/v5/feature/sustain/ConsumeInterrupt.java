package me.vexmc.mental.v5.feature.sustain;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import me.vexmc.mental.platform.ServerEnvironment;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The boot-probed "stop the active eat/drink" seam for {@code Ct8cRegenUnit}'s
 * hit-interrupt (spec §2.7). There is no Bukkit API to abort an item-use in
 * progress, so this drops to the NMS {@code LivingEntity#stopUsingItem()} through
 * {@code CraftPlayer#getHandle()}.
 *
 * <p><b>Version posture (B10).</b> The Mojang-mapped {@code stopUsingItem} name
 * resolves on 1.20.5+ (the mappings flip); below that the runtime is
 * spigot-mapped and the method is obfuscated per revision, so the seam degrades
 * to a no-op with ONE loud line — the regen cadence, sprint gate and starvation
 * are unaffected, only the on-hit interrupt is lost there. A resolution that
 * SHOULD have succeeded (1.20.5+) but did not is logged as a mapping break.</p>
 *
 * <p>Deliberately NOT a {@link org.bukkit.event.Listener}: it holds reflective
 * {@link Method} handles and is never handed to {@code registerEvents}, so no
 * descriptor of a registered listener names an NMS type (the 2.4.1 GAP-1 rule).
 * The owning unit calls {@link #interrupt(Player)} at the body level only.</p>
 */
public final class ConsumeInterrupt {

    /** {@code net.minecraft.world.entity.LivingEntity#stopUsingItem()} — Mojang-mapped, 1.20.5+. */
    private final @Nullable Method stopUsingItem;
    private final boolean supported;

    /** {@code CraftPlayer#getHandle()}, resolved lazily from the first player's runtime class. */
    private volatile @Nullable Method craftGetHandle;

    /**
     * Boot-probes the seam and loud-logs the version posture: a mapping break on
     * 1.20.5+, or the documented sub-floor no-op below.
     */
    public static @NotNull ConsumeInterrupt probe(
            @NotNull ServerEnvironment environment, @NotNull Consumer<String> log) {
        ConsumeInterrupt seam = new ConsumeInterrupt();
        boolean expected = environment.isAtLeast(1, 20, 5);
        if (expected && !seam.supported) {
            log.accept("platform-probe: LivingEntity#stopUsingItem() did not resolve on "
                    + environment.describe()
                    + " — a mapping break; CT8c eat/drink interrupt-on-hit is unavailable (regen cadence unaffected).");
        } else if (!expected) {
            log.accept("ct8c-regen: eat/drink interrupt-on-hit needs the 1.20.5+ item-use seam; on "
                    + environment.describe()
                    + " it is a no-op — the 40-tick regen cadence and sprint gate still apply.");
        }
        return seam;
    }

    private ConsumeInterrupt() {
        Method stop = null;
        try {
            Class<?> livingEntity = Class.forName("net.minecraft.world.entity.LivingEntity");
            stop = livingEntity.getMethod("stopUsingItem");
        } catch (Throwable absent) {
            stop = null; // Spigot-mapped or NMS relocated — degrade to no-op.
        }
        this.stopUsingItem = stop;
        this.supported = stop != null;
    }

    /** Whether this server can interrupt an active item-use (1.20.5+ Mojang mappings). */
    public boolean supported() {
        return supported;
    }

    public @NotNull String describe() {
        return supported ? "LivingEntity#stopUsingItem() via nms" : "(none — <1.20.5 no-op)";
    }

    /**
     * Aborts {@code player}'s in-progress eat/drink, best-effort. A no-op where
     * the seam is unresolved or the reflective call slips — never throws, never
     * corrupts state (the worst case is simply no interrupt).
     */
    public void interrupt(@Nullable Player player) {
        if (!supported || player == null || stopUsingItem == null) {
            return;
        }
        try {
            Object handle = handleOf(player);
            if (handle != null) {
                stopUsingItem.invoke(handle);
            }
        } catch (Throwable ignored) {
            // Best-effort — a reflective slip loses one interrupt, never more.
        }
    }

    private @Nullable Object handleOf(@NotNull Player player) throws ReflectiveOperationException {
        Method getHandle = craftGetHandle;
        if (getHandle == null) {
            // Resolve from the concrete CraftPlayer class so the versioned package
            // (pre-1.20.5) or the flat one (1.20.5+) both work with no name literal.
            getHandle = player.getClass().getMethod("getHandle");
            craftGetHandle = getHandle;
        }
        return getHandle.invoke(player);
    }
}
