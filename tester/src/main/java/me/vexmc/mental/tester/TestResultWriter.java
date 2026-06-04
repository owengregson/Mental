package me.vexmc.mental.tester;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/** Writes PASS/FAIL for the Gradle check task, then the failure details. */
final class TestResultWriter {

    private TestResultWriter() {}

    static void write(@NotNull JavaPlugin plugin, boolean success, @NotNull List<String> failures) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().severe("Could not create data folder for test results");
        }
        try {
            Files.writeString(new File(dataFolder, "test-results.txt").toPath(),
                    success ? "PASS" : "FAIL", StandardCharsets.UTF_8);
            if (!failures.isEmpty()) {
                Files.write(new File(dataFolder, "test-failures.txt").toPath(),
                        failures, StandardCharsets.UTF_8);
            }
        } catch (IOException failure) {
            plugin.getLogger().severe("Could not write test results: " + failure);
        }
    }
}
