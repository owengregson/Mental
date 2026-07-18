package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.v5.MentalPluginV5;
import me.vexmc.mental.v5.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code /mental} command surface (spec §13): {@code reload} re-reads the
 * config with the {@code mental.command.reload} permission; a bare {@code /mental}
 * from a permitted player opens the descriptor-driven management menu (Phase 6),
 * and from the console prints the reload hint. Both the console and a permitted
 * player must see the command handled (the executor always returns true), and the
 * player's menu must actually open — the holder-identity contract the click router
 * routes on.
 */
public final class CommandSuite {

    private CommandSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPluginV5 mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("command: console reload and bare hint are handled", context -> {
                    boolean reload = context.sync(() ->
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mental reload"));
                    context.expect(reload, "'mental reload' was not handled");

                    boolean bare = context.sync(() ->
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mental"));
                    context.expect(bare, "bare 'mental' from the console was not handled");
                }),
                new TestCase("command: a permitted player opens the dashboard menu", context -> {
                    FakePlayer player = new FakePlayer(tester, mental.scheduling());
                    try {
                        context.syncRun(() ->
                                player.spawn(Arena.prepare(Bukkit.getWorlds().get(0))));
                        context.awaitTicks(3);
                        boolean handled = context.sync(() -> {
                            player.player().addAttachment(tester, "mental.command.use", true);
                            return Bukkit.dispatchCommand(player.player(), "mental");
                        });
                        context.expect(handled, "bare /mental was not handled for a permitted player");
                        // open() defers the inventory work onto the player's region
                        // thread (a tick on Paper); give it a few ticks, then assert
                        // the holder-identity contract the click router routes on.
                        context.awaitTicks(3);
                        boolean menuOpen = context.sync(() -> menuOpen(player));
                        context.expect(menuOpen, "/mental did not open a Mental management menu");
                    } finally {
                        context.syncRun(() -> {
                            player.player().closeInventory();
                            player.remove();
                        });
                    }
                }));
    }

    private static boolean menuOpen(@NotNull FakePlayer player) {
        InventoryHolder holder = player.player().getOpenInventory().getTopInventory().getHolder();
        return holder instanceof Menu;
    }
}
