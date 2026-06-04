package me.vexmc.mental.debug;

import java.util.logging.Logger;
import me.vexmc.mental.common.debug.DebugCategory;
import me.vexmc.mental.common.debug.DebugLog;
import org.jetbrains.annotations.NotNull;

/** Routes debug lines to the server console under the plugin's logger. */
public record ConsoleSink(@NotNull Logger logger) implements DebugLog.Sink {

    @Override
    public void accept(@NotNull DebugCategory category, @NotNull String message) {
        logger.info("[debug:" + category.key() + "] " + message);
    }
}
