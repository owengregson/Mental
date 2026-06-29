package me.vexmc.mental.gui.menu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The hand-authored presentation layer for every module: a themed icon and a
 * one-line "why" for each module id, plus the category grouping the dashboard
 * navigates by. Icons are material <em>names</em> (resolved safely through
 * {@code Materials}); copy is deliberately short so it reads as lore at a
 * glance. Module identity, defaults and behaviour live in the modules
 * themselves — this is purely how they look in the menu.
 */
public final class Catalog {

    /** A module's menu glyph: themed icon material name + a one-line blurb. */
    public record Glyph(@NotNull String material, @NotNull String blurb) {}

    /** A dashboard section: a title, its nav icon, and the modules it holds. */
    public record Category(
            @NotNull String key,
            @NotNull String title,
            @NotNull String navMaterial,
            @NotNull String navBlurb,
            @NotNull List<String> moduleIds) {}

    private static final Map<String, Glyph> GLYPHS = new LinkedHashMap<>();

    static {
        // Engine essentials.
        glyph("hit-registration", "IRON_SWORD",
                "Async hit registration — attacks validated on the netty thread, damage on the victim's region.");
        glyph("wtap-registration", "FEATHER",
                "Reads the attacker's sprint in packet-arrival order, so a w-tap registers however fast the tap.");
        glyph("knockback", "PISTON",
                "Melee knockback through the velocity pipeline, shaped by the active profile.");
        glyph("latency-compensation", "CLOCK",
                "Ping-aware vertical-knockback correction.");
        glyph("fishing-knockback", "FISHING_ROD",
                "1.7 rod combat — hooks are real zero-damage hits that knock.");
        glyph("rod-velocity", "TRIPWIRE_HOOK",
                "1.7 rod cast feel — launch speed, spread, and flight gravity.");
        glyph("projectile-knockback", "SNOWBALL",
                "1.7 projectile knockback — snowballs, eggs, pearls, and arrows.");

        // Combat rules.
        glyph("attack-cooldown", "NETHERITE_SWORD",
                "Remove the 1.9 attack cooldown — full-charge damage on every swing.");
        glyph("disable-sword-sweep", "IRON_SWORD",
                "Disable the 1.9 sweep attack — swords hit a single target, no sweep particle.");
        glyph("disable-attack-sounds", "NOTE_BLOCK",
                "Suppress the 1.9 swing-result sounds — 1.7/1.8 combat was silent.");
        glyph("disable-offhand", "SHIELD",
                "Block items from the off-hand slot — the slot did not exist pre-1.9.");
        glyph("disable-crafting", "CRAFTING_TABLE",
                "Make the configured items uncraftable (shield by default).");
        glyph("sword-blocking", "STONE_SWORD",
                "Restore 1.7/1.8 right-click sword blocking — a blocked hit takes (1 + dmg) x 0.5.");

        // Damage.
        glyph("old-armour-strength", "IRON_CHESTPLATE",
                "1.8 flat armour reduction — 4% per point, no toughness.");
        glyph("old-armour-durability", "DIAMOND_CHESTPLATE",
                "1.8 armour-durability Unbreaking skip.");
        glyph("old-tool-durability", "IRON_PICKAXE",
                "Restore weapon durability loss on fast-path melee hits.");
        glyph("old-critical-hits", "NETHER_STAR",
                "1.8 crit rule — x1.5 with no sprint/cooldown gate (fast-path-off hits).");

        // Potions & food.
        glyph("old-potion-durations", "POTION",
                "Restore the pre-1.9 potion durations.");
        glyph("old-potion-values", "SPLASH_POTION",
                "Restore the pre-1.9 Strength/Weakness damage values.");
        glyph("old-golden-apples", "ENCHANTED_GOLDEN_APPLE",
                "1.8.9 golden-apple effects and the notch-apple recipe.");
        glyph("disable-enderpearl-cooldown", "ENDER_PEARL",
                "Remove the 1.9 ender-pearl throw cooldown.");

        // Player.
        glyph("old-player-regen", "GOLDEN_APPLE",
                "1.8 natural regen — heal 1 HP every 4s at food level 18+.");
        glyph("old-hitboxes", "TARGET",
                "1.7/1.8 melee reach (3.0) and hitbox margin, where the server allows.");
    }

    /** The categories the dashboard exposes, in display order. */
    public static final List<Category> CATEGORIES = List.of(
            new Category("hitreg", "Hit Registration", "COMPARATOR",
                    "The async hit pipeline and its sprint/ping helpers.",
                    List.of("hit-registration", "wtap-registration", "latency-compensation")),
            new Category("rules", "Combat Rules", "DIAMOND_SWORD",
                    "Cooldown, sweep, sounds, off-hand, crafting, blocking.",
                    List.of("attack-cooldown", "disable-sword-sweep", "disable-attack-sounds",
                            "disable-offhand", "disable-crafting", "sword-blocking")),
            new Category("damage", "Damage", "IRON_CHESTPLATE",
                    "Era armour, durability, and critical-hit rules.",
                    List.of("old-armour-strength", "old-armour-durability",
                            "old-tool-durability", "old-critical-hits")),
            new Category("potions", "Potions & Food", "BREWING_STAND",
                    "Era potion durations/values, golden apples, pearls.",
                    List.of("old-potion-durations", "old-potion-values",
                            "old-golden-apples", "disable-enderpearl-cooldown")),
            new Category("player", "Player", "PLAYER_HEAD",
                    "Era health regen and melee reach/hitbox.",
                    List.of("old-player-regen", "old-hitboxes")));

    private Catalog() {}

    private static void glyph(@NotNull String id, @NotNull String material, @NotNull String blurb) {
        GLYPHS.put(id, new Glyph(material, blurb));
    }

    /** The glyph for a module id, or a neutral default if one is not catalogued. */
    public static @NotNull Glyph glyph(@NotNull String moduleId) {
        Glyph glyph = GLYPHS.get(moduleId);
        return glyph != null ? glyph : new Glyph("PAPER", "");
    }

    public static @Nullable Category category(@NotNull String key) {
        for (Category category : CATEGORIES) {
            if (category.key().equals(key)) {
                return category;
            }
        }
        return null;
    }
}
