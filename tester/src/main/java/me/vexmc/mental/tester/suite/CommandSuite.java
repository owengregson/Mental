package me.vexmc.mental.tester.suite;

import java.util.List;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/** The command tree executes on whichever backend this version selected. */
public final class CommandSuite {

    private CommandSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("command: console runs version and module status", context -> {
                    boolean version = context.sync(() ->
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mental version"));
                    context.expect(version, "'mental version' was not handled");

                    boolean status = context.sync(() ->
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mental module knockback status"));
                    context.expect(status, "'mental module knockback status' was not handled");
                }),
                new TestCase("command: permitted player opens the dashboard", context -> {
                    FakePlayer player = new FakePlayer(tester, mental.services().scheduling());
                    try {
                        context.syncRun(() ->
                                player.spawn(Arena.prepare(Bukkit.getWorlds().get(0))));
                        context.awaitTicks(3);
                        boolean handled = context.sync(() -> {
                            player.player().addAttachment(tester, "mental.command.use", true);
                            return Bukkit.dispatchCommand(player.player(), "mental");
                        });
                        context.expect(handled, "bare /mental was not handled for a permitted player");
                    } finally {
                        context.syncRun(player::remove);
                    }
                }));
    }
}
