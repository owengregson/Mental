package me.vexmc.mental.tester;

import org.jetbrains.annotations.NotNull;

/** One named integration test. */
public record TestCase(@NotNull String name, @NotNull Body body) {

    @FunctionalInterface
    public interface Body {
        void run(@NotNull TestContext context) throws Exception;
    }
}
