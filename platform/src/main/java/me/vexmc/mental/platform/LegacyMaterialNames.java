package me.vexmc.mental.platform;

import java.util.Map;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * Normalizes a pre-flattening {@link Material} enum-constant NAME to its modern
 * (1.13+) spelling, once, at the platform seam.
 *
 * <p>The kernel's string vocabularies (e.g. {@code DamageTables.weaponDamage},
 * which keys on the {@code WOODEN_}/{@code GOLDEN_} prefixes and the
 * {@code _SWORD}/{@code _AXE}/{@code _PICKAXE}/{@code _SHOVEL} suffixes) stay
 * modern and version-blind by design — legacy names never enter the kernel. On a
 * pre-flattening server, though, {@code Material.name()} returns the OLD constant
 * ({@code WOOD_SWORD}, {@code GOLD_SWORD}, {@code WOOD_SPADE}, {@code
 * GOLD_HELMET}, …), so every core-side feed that hands a material name to a
 * kernel table routes it through {@link #modernize} first. This is the single
 * translation point the whole backport relies on (2026-07-02 decision log).</p>
 *
 * <p>Scope is deliberately narrow: ONLY the combat vocabulary a kernel string
 * table consumes — the four tool stems, hoes (for completeness of the tool set),
 * the shovel's {@code SPADE→SHOVEL} rename, and the {@code GOLD_} armour set that
 * shares the {@code GOLD_→GOLDEN_} rename. This is NOT a full 1.12 materials
 * database; a name outside the table (blocks, potions, anything already modern)
 * passes through unchanged. The two compound cases ({@code WOOD_SPADE →
 * WOODEN_SHOVEL}, {@code GOLD_SPADE → GOLDEN_SHOVEL}) carry BOTH renames at once,
 * which is exactly why an explicit table is safer than a naive prefix rule: a
 * blanket {@code WOOD_→WOODEN_} would mis-map {@code WOOD_STAIRS} / {@code
 * WOOD_BUTTON} and friends that were never renamed.</p>
 *
 * <p>The renames below were read off the real CraftBukkit {@code Material} enum
 * of the terminal 1.9.4 and 1.12.2 patches (both identical for these items),
 * never assumed.</p>
 */
public final class LegacyMaterialNames {

    /**
     * True on a flattened (1.13+) server, resolved once at class load by constant
     * presence — the same class/constant-presence idiom {@link PersistentData}
     * uses, never a version parse. {@code WOODEN_SWORD} is a post-flattening
     * constant; the single-argument {@link Material#getMaterial(String)} never
     * searches legacy names, so it is {@code null} on every pre-1.13 server and
     * non-null from 1.13 up. On a flattened server {@link #modernize} is a pure
     * identity (modern servers never emit a legacy name), so the whole normalizer
     * is a zero-cost no-op on the entire modern range — the byte-identical
     * behaviour the 1.17+ suites pin.
     */
    private static final boolean FLATTENED = Material.getMaterial("WOODEN_SWORD") != null;

    /**
     * The pre-flattening combat vocabulary → its modern spelling. Kept minimal and
     * explicit (see the class note on why a table beats a prefix rule).
     */
    private static final Map<String, String> LEGACY_TO_MODERN = Map.ofEntries(
            Map.entry("WOOD_SWORD", "WOODEN_SWORD"),
            Map.entry("WOOD_AXE", "WOODEN_AXE"),
            Map.entry("WOOD_PICKAXE", "WOODEN_PICKAXE"),
            Map.entry("WOOD_SPADE", "WOODEN_SHOVEL"),
            Map.entry("WOOD_HOE", "WOODEN_HOE"),
            Map.entry("GOLD_SWORD", "GOLDEN_SWORD"),
            Map.entry("GOLD_AXE", "GOLDEN_AXE"),
            Map.entry("GOLD_PICKAXE", "GOLDEN_PICKAXE"),
            Map.entry("GOLD_SPADE", "GOLDEN_SHOVEL"),
            Map.entry("GOLD_HOE", "GOLDEN_HOE"),
            Map.entry("STONE_SPADE", "STONE_SHOVEL"),
            Map.entry("IRON_SPADE", "IRON_SHOVEL"),
            Map.entry("DIAMOND_SPADE", "DIAMOND_SHOVEL"),
            Map.entry("GOLD_HELMET", "GOLDEN_HELMET"),
            Map.entry("GOLD_CHESTPLATE", "GOLDEN_CHESTPLATE"),
            Map.entry("GOLD_LEGGINGS", "GOLDEN_LEGGINGS"),
            Map.entry("GOLD_BOOTS", "GOLDEN_BOOTS"));

    private LegacyMaterialNames() {}

    /**
     * The modern spelling of a Bukkit {@link Material} enum-constant name. Identity
     * on a flattened server (1.13+ never emits a legacy name) and for any name
     * outside the combat vocabulary; on a pre-flattening server it maps the combat
     * vocabulary to the modern spelling the kernel tables expect. Never null for a
     * non-null input.
     */
    public static @NotNull String modernize(@NotNull String name) {
        return FLATTENED ? name : translate(name);
    }

    /**
     * The pure table lookup, independent of the running server — the unit-testable
     * half. An unknown or already-modern name passes through unchanged. Package-
     * private so tests can exercise the legacy mapping directly (in the modern-API
     * test JVM {@link #modernize} is identity, so the table itself is what the
     * legacy-name pins must reach).
     */
    static @NotNull String translate(@NotNull String name) {
        return LEGACY_TO_MODERN.getOrDefault(name, name);
    }
}
