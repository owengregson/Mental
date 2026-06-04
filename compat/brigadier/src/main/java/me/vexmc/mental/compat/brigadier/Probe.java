package me.vexmc.mental.compat.brigadier;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

final class Probe {
    private Probe() {}

    static Class<?>[] required() {
        return new Class<?>[] {Commands.class, LifecycleEvents.class};
    }
}
