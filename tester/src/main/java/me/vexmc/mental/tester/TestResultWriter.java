package me.vexmc.mental.tester;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Writes PASS/FAIL for the Gradle check task, then the failure details.
 *
 * <p>The verdict line carries the run's freshness nonce — {@code
 * "PASS nonce=<n>"} / {@code "FAIL nonce=<n>"} — so a stale result from an
 * earlier server boot can never be mistaken for this run's answer: the Gradle
 * check task and the concurrent-matrix script both reject any file whose nonce
 * is not the one this exact invocation generated. See {@code
 * -Dmental.tester.nonce} in core/build.gradle.kts and integration-matrix.sh.</p>
 */
final class TestResultWriter {

    private TestResultWriter() {}

    static void write(@NotNull JavaPlugin plugin, boolean success, @NotNull List<String> failures,
            @NotNull String nonce) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().severe("Could not create data folder for test results");
        }
        try {
            Files.writeString(new File(dataFolder, "test-results.txt").toPath(),
                    (success ? "PASS" : "FAIL") + " nonce=" + nonce, StandardCharsets.UTF_8);
            if (!failures.isEmpty()) {
                Files.write(new File(dataFolder, "test-failures.txt").toPath(),
                        failures, StandardCharsets.UTF_8);
            }
        } catch (IOException failure) {
            plugin.getLogger().severe("Could not write test results: " + failure);
        }
    }
}
