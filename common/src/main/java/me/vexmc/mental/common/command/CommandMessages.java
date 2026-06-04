package me.vexmc.mental.common.command;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/** Branding-owned copy for the tree's failure paths; implemented in core. */
public interface CommandMessages {

    @NotNull Component noPermission();

    @NotNull Component unknownSubcommand();

    @NotNull Component usage(@NotNull String usage);
}
