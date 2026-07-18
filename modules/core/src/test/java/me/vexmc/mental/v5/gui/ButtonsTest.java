package me.vexmc.mental.v5.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The greedy word-wrap contract, lifted verbatim from the retired
 * {@code gui/menu/ButtonsTest} reuse-ledger asset (only the package moved with
 * its subject to the v5 tree), plus the v2 grammar pins (kv / round / back).
 *
 * <p>The {@code back} pin builds a real {@link ItemStack}, which needs an
 * {@code ItemFactory}; the project ships no MockBukkit, so a dynamic-proxy
 * server (returning a stateful proxy meta) is installed for the duration of this
 * class and torn down after — the same interface-stub idiom the codebase already
 * uses for headless Bukkit shells.</p>
 */
class ButtonsTest {

    private static boolean installedServer;

    @BeforeAll
    static void installServer() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        if (field.get(null) == null) {
            field.set(null, server());
            installedServer = true;
        }
    }

    @AfterAll
    static void removeServer() throws Exception {
        if (installedServer) {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, null);
        }
    }

    @Test
    void shortTextStaysOnOneLine() {
        assertEquals(List.of("Short blurb"), Buttons.wrap("Short blurb"));
    }

    @Test
    void longTextWrapsAtWordBoundariesAndLosesNothing() {
        String text = "Remove the 1.9 attack cooldown so spam-clicking deals full "
                + "charge damage on every single swing across the version range";
        List<String> lines = Buttons.wrap(text);

        assertTrue(lines.size() > 1, "long text should wrap onto multiple lines");
        for (String line : lines) {
            // Each line stays within the width unless it is a single over-long word.
            assertTrue(line.length() <= 42 || !line.contains(" "),
                    "line over width with a break opportunity: '" + line + "'");
        }
        // Greedy wrap preserves every word in order, joined by single spaces.
        assertEquals(text, String.join(" ", lines));
    }

    @Test
    void aSingleOverlongWordIsNeverSplit() {
        String word = "Supercalifragilisticexpialidocious_pneumonoultramicroscopicsilicovolcanoconiosis";
        assertEquals(List.of(word), Buttons.wrap(word));
    }

    @Test
    void backNamesItsDestination() {
        ItemStack back = Buttons.back("Knockback");
        List<String> lore = back.getItemMeta().getLore();
        assertNotNull(lore, "the back tile carries lore");
        assertEquals("Return to Knockback.", stripColors(lore.get(0)));
    }

    @Test
    void kvJoinsMutedLabelAndAccentValue() {
        Component kv = Buttons.kv("Active", "signature", NamedTextColor.RED);
        List<Component> children = kv.children();
        assertEquals(2, children.size(), "kv is a muted label plus an accent value");
        assertEquals("Active: ", ((TextComponent) children.get(0)).content());
        assertEquals(NamedTextColor.GRAY, children.get(0).color());
        assertEquals("signature", ((TextComponent) children.get(1)).content());
        assertEquals(NamedTextColor.RED, children.get(1).color());
    }

    @Test
    void roundKeepsThreeDecimals() {
        assertEquals("0.123", Buttons.round(0.12345));
        assertEquals("2.0", Buttons.round(2.0));
    }

    private static String stripColors(String line) {
        return line.replaceAll("§.", "");
    }

    /* ------------------------------------------------------------------ */
    /*  Interface stubs (dynamic proxies) — no Bukkit server required.     */
    /* ------------------------------------------------------------------ */

    private static Server server() {
        ItemFactory factory = (ItemFactory) Proxy.newProxyInstance(
                loader(), new Class[]{ItemFactory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItemMeta" -> meta();
                    case "isApplicable" -> true;
                    case "asMetaFor" -> args[0];      // return the passed meta unchanged
                    case "updateMaterial" -> args[1]; // keep the item's material
                    default -> defaultValue(method, proxy, args);
                });
        return (Server) Proxy.newProxyInstance(
                loader(), new Class[]{Server.class},
                (proxy, method, args) -> "getItemFactory".equals(method.getName())
                        ? factory
                        : defaultValue(method, proxy, args));
    }

    /** A stateful meta stub: it remembers the name/lore it is given and clones to itself. */
    private static ItemMeta meta() {
        Map<String, Object> state = new HashMap<>();
        ItemMeta[] self = new ItemMeta[1];
        self[0] = (ItemMeta) Proxy.newProxyInstance(
                loader(), new Class[]{ItemMeta.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setDisplayName" -> {
                        state.put("name", args[0]);
                        yield null;
                    }
                    case "getDisplayName" -> state.get("name");
                    case "hasDisplayName" -> state.containsKey("name");
                    case "setLore" -> {
                        state.put("lore", args[0]);
                        yield null;
                    }
                    case "getLore" -> state.get("lore");
                    case "hasLore" -> state.containsKey("lore");
                    case "clone" -> self[0];
                    default -> defaultValue(method, proxy, args);
                });
        return self[0];
    }

    private static ClassLoader loader() {
        return ButtonsTest.class.getClassLoader();
    }

    /** Sensible defaults for un-stubbed proxy calls (Object identity + typed zeroes). */
    private static Object defaultValue(Method method, Object proxy, Object[] args) {
        switch (method.getName()) {
            case "toString": return method.getDeclaringClass().getSimpleName() + "-stub";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals":   return proxy == args[0];
            default:         return primitiveZero(method.getReturnType());
        }
    }

    private static Object primitiveZero(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
