package me.vexmc.mental.common.command;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A captured value in the command tree ({@code <player>}, {@code <module>}, …). */
public record ArgumentNode(
        @NotNull String name,
        @Nullable String permission,
        @NotNull String description,
        @NotNull Suggester suggester,
        @NotNull List<CommandNode> children,
        @Nullable CommandAction action) implements CommandNode {

    public ArgumentNode {
        children = List.copyOf(children);
    }

    public static @NotNull Builder argument(@NotNull String name, @NotNull Suggester suggester) {
        return new Builder(name, suggester);
    }

    public static final class Builder {

        private final String name;
        private final Suggester suggester;
        private String permission;
        private String description = "";
        private final List<CommandNode> children = new ArrayList<>();
        private CommandAction action;

        private Builder(String name, Suggester suggester) {
            this.name = name;
            this.suggester = suggester;
        }

        public @NotNull Builder permission(@NotNull String permission) {
            this.permission = permission;
            return this;
        }

        public @NotNull Builder describe(@NotNull String description) {
            this.description = description;
            return this;
        }

        public @NotNull Builder then(@NotNull CommandNode child) {
            children.add(child);
            return this;
        }

        public @NotNull Builder runs(@NotNull CommandAction action) {
            this.action = action;
            return this;
        }

        public @NotNull ArgumentNode build() {
            return new ArgumentNode(name, permission, description, suggester, children, action);
        }
    }
}
