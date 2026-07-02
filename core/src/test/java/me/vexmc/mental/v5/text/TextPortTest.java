package me.vexmc.mental.v5.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

/**
 * Pins the Component → legacy-string conversion the {@link TextPort} sinks are built on. The section-code
 * output is what the universal Bukkit String APIs render on every version 1.9 → 26.x; a regression here
 * would silently corrupt every menu title, icon name, and denial line on legacy servers.
 */
class TextPortTest {

    @Test
    void coloredTextSerializesToSectionCodes() {
        assertEquals("§cHello", TextPort.legacy(Component.text("Hello", NamedTextColor.RED)));
        assertEquals("§aok", TextPort.legacy(Component.text("ok", NamedTextColor.GREEN)));
    }

    @Test
    void plainTextRoundTripsWithoutCodes() {
        assertEquals("plain", TextPort.legacy(Component.text("plain")));
        assertEquals("", TextPort.legacy(Component.empty()));
    }

    @Test
    void aListSerializesLineByLineInOrder() {
        List<String> lines = TextPort.legacy(List.of(
                Component.text("first", NamedTextColor.GOLD),
                Component.text("second", NamedTextColor.GRAY)));
        assertEquals(List.of("§6first", "§7second"), lines);
    }
}
