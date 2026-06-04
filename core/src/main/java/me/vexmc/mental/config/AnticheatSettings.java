package me.vexmc.mental.config;

import java.util.List;
import org.jetbrains.annotations.NotNull;

public record AnticheatSettings(@NotNull AnticheatMode mode, @NotNull List<String> knownPlugins) {

    static final AnticheatSettings DEFAULTS =
            new AnticheatSettings(AnticheatMode.AUTO, List.of("GrimAC", "Vulcan"));

    static @NotNull AnticheatSettings parse(@NotNull ConfigReader reader) {
        AnticheatMode mode = reader.oneOf("mode", DEFAULTS.mode, AnticheatMode.class);
        List<String> known = reader.section() == null || !reader.section().isList("known")
                ? DEFAULTS.knownPlugins
                : List.copyOf(reader.section().getStringList("known"));
        return new AnticheatSettings(mode, known);
    }
}
