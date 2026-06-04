package me.vexmc.mental.common.command;

import org.jetbrains.annotations.NotNull;

/** A leaf behavior in the command tree. */
@FunctionalInterface
public interface CommandAction {

    void run(@NotNull CommandContext context);
}
