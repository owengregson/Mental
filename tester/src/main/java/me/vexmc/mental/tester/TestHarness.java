package me.vexmc.mental.tester;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.platform.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the suite on a dedicated driver thread inside the live server,
 * reports PASS/FAIL to disk, and shuts the server down — successful or not,
 * the Gradle build always gets its answer. A watchdog guarantees shutdown
 * even if a test wedges the driver.
 */
final class TestHarness {

    private static final long WATCHDOG_SECONDS = 300;

    private final JavaPlugin plugin;
    private final Scheduling scheduling;
    private final String nonce;

    TestHarness(@NotNull JavaPlugin plugin, @NotNull Scheduling scheduling, @NotNull String nonce) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.nonce = nonce;
    }

    void run(@NotNull List<TestCase> suite) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(WATCHDOG_SECONDS * 1000L);
            } catch (InterruptedException expected) {
                return;
            }
            plugin.getLogger().severe("Test watchdog fired after " + WATCHDOG_SECONDS + "s — forcing FAIL");
            TestResultWriter.write(plugin, false, List.of("watchdog: suite did not finish"), nonce);
            scheduling.runGlobal(Bukkit::shutdown);
        }, "mental-test-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        Thread driver = new Thread(() -> {
            List<String> failures = new ArrayList<>();
            TestContext context = new TestContext(scheduling, plugin.getLogger());

            for (TestCase test : suite) {
                long started = System.nanoTime();
                try {
                    plugin.getLogger().info("[test] RUN  " + test.name());
                    test.body().run(context);
                    plugin.getLogger().info("[test] PASS " + test.name()
                            + " (" + (System.nanoTime() - started) / 1_000_000 + "ms)");
                } catch (Throwable failure) {
                    String detail = test.name() + ": " + failure;
                    failures.add(detail);
                    plugin.getLogger().severe("[test] FAIL " + detail);
                    for (StackTraceElement frame : failure.getStackTrace()) {
                        if (frame.getClassName().startsWith("me.vexmc.mental")) {
                            plugin.getLogger().severe("[test]      at " + frame);
                        }
                    }
                }
            }

            boolean success = failures.isEmpty();
            plugin.getLogger().info("[test] Suite finished: " + (suite.size() - failures.size())
                    + "/" + suite.size() + " passed");
            TestResultWriter.write(plugin, success, failures, nonce);
            watchdog.interrupt();
            scheduling.runGlobal(Bukkit::shutdown);
        }, "mental-test-driver");
        driver.setDaemon(false);
        driver.start();
    }
}
