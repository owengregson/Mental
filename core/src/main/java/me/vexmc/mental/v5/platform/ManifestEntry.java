package me.vexmc.mental.v5.platform;

import org.jetbrains.annotations.NotNull;

/**
 * One typed line in the {@link PlatformProfile} manifest (spec §9). Every
 * boot-time platform resolution — an attribute or enchantment handle, a
 * capability flag, an item-component adapter — is declared as an entry so the
 * profile is the single owner: nothing resolves to a bare {@code @Nullable} at a
 * call site, and every resolution carries its own presence and self-description
 * for the one-line boot report.
 *
 * <p>Two flavours, matching the two honest outcomes a cross-version probe has:
 * {@link Required} (a handle that MUST exist on every supported server; a miss is
 * a mapping break) and {@link OptionalSince} (a handle that is EXPECTED to be
 * absent below a declared version; a miss below it is a typed, quiet fallback).</p>
 */
public sealed interface ManifestEntry permits Required, OptionalSince {

    /** The stable manifest name, e.g. {@code attribute:attack_speed}. Never blank. */
    @NotNull String name();

    /** Whether the underlying handle/flag resolved on this server. */
    boolean present();

    /** A self-contained one-fragment description for the boot report. Never blank. */
    @NotNull String describe();
}
