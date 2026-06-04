package me.vexmc.mental.common.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A fixed keyword in the command tree ({@code module}, {@code reload}, …). */
public record LiteralNode(
        @NotNull String name,
        @Nullable String permission,
        @NotNull String description,
        @NotNull List<CommandNode> children,
        @Nullable CommandAction action) implements CommandNode {

    public LiteralNode {
        children = List.copyOf(children);
    }

    public static @NotNull Builder literal(@NotNull String name) {
        return new Builder(name.toLowerCase(Locale.ROOT));
    }

    public static final class Builder {

        private final String name;
        private String permission;
        private String description = "";
        private final List<CommandNode> children = new ArrayList<>();
        private CommandAction action;

        private Builder(String name) {
            this.name = name;
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

        public @NotNull LiteralNode build() {
            return new LiteralNode(name, permission, description, children, action);
        }
    }
}
