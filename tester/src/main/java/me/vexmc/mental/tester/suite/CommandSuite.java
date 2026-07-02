package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.fake.FakePlayer;
import me.vexmc.mental.v5.MentalPluginV5;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * The minimal v5 {@code /mental} executor (spec §13): {@code reload} re-reads the
 * config with the {@code mental.command.reload} permission; every other form is a
 * one-line placeholder — the in-game management GUI arrives in Phase 6, so its
 * assertion is a note-SKIP here. Both the console and a permitted player must see
 * the command handled (the executor always returns true).
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
                new TestCase("command: a permitted player's bare /mental is handled (GUI: Phase 6)", context -> {
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
                        // The v5 command surface is a reload + placeholder only; the in-game
                        // management menu (and its holder-open assertion) lands in Phase 6.
                        context.note("management GUI deferred to Phase 6 — bare /mental returns "
                                + "the placeholder message (verified handled above)");
                    } finally {
                        context.syncRun(player::remove);
                    }
                }));
    }
}
