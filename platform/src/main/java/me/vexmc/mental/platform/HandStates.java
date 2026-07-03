package me.vexmc.mental.platform;

import org.bukkit.entity.HumanEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Cross-version "is the hand raised" (item-use) read.
 *
 * <p>{@code HumanEntity#isHandRaised()} — true while a player holds right-click to use an item, including
 * the shield-raise window before {@code isBlocking()} turns true — is a Bukkit API method only from 1.10.2
 * (javap-verified absent on 1.9.4). The sword-block ephemeral decoration reads it to know whether the player
 * is still holding the block, so a direct call throws {@code NoSuchMethodError} on 1.9.4 when that feature
 * is enabled.</p>
 *
 * <p>The accessor is chosen ONCE at class load (never a version parse). Where present it is used verbatim
 * (byte-identical on 1.10.2+). On 1.9.4 it degrades to {@code false}: callers pair this read with
 * {@code isBlocking()} (present on 1.9.4), so the effective condition collapses to the shield-block state
 * alone — it loses only the sub-block-delay raise window, never throws.</p>
 */
public final class HandStates {

    private static final boolean SUPPORTED = resolve();

    private HandStates() {}

    /** Whether the player is holding an item-use (hand raised); {@code false} on 1.9.4 where the API is absent. */
    public static boolean isHandRaised(@NotNull HumanEntity human) {
        return SUPPORTED && human.isHandRaised();
    }

    /** For the boot report: whether the hand-raised read uses the native method or the 1.9.4 false-degrade. */
    public static @NotNull String describe() {
        return SUPPORTED ? "HumanEntity#isHandRaised()" : "false (absent pre-1.10.2 — isBlocking() alone)";
    }

    private static boolean resolve() {
        try {
            HumanEntity.class.getMethod("isHandRaised");
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }
}
