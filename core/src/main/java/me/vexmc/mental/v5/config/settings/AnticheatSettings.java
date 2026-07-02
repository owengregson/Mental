package me.vexmc.mental.v5.config.settings;

import java.util.List;
import me.vexmc.mental.v5.config.AnticheatMode;

/**
 * The plugin-wide anticheat-coexistence section (config.yml {@code anticheat}),
 * ported field-for-field from the retired {@code config.AnticheatSettings}.
 * Exposed by {@code Snapshot.anticheat()}, frozen into the fast path.
 */
public record AnticheatSettings(AnticheatMode mode, List<String> knownPlugins) {

    public static final AnticheatSettings DEFAULTS =
            new AnticheatSettings(AnticheatMode.AUTO, List.of("GrimAC", "Vulcan"));
}
