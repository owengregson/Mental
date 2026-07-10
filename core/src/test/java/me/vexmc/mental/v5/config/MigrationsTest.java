package me.vexmc.mental.v5.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The explicit config-version chain: 1 → 2 splits the single-file layout and
 * carries tuned knockback into profiles/custom.yml, 2 → 3 creates the overlay
 * and stamps the version. Each step backs up first and is idempotent; the
 * version is now READ.
 */
class MigrationsTest {

    @TempDir
    Path dataFolder;

    private final Function<String, InputStream> resources = name ->
            getClass().getClassLoader().getResourceAsStream(name);

    private Migrations migrations() {
        return new Migrations(dataFolder, resources, message -> {});
    }

    private void installFixture(String tree) throws Exception {
        try (InputStream in = resources.apply("v5/migration/" + tree + "/config.yml")) {
            Files.writeString(dataFolder.resolve("config.yml"),
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
    }

    private YamlConfiguration load(String file) {
        return YamlConfiguration.loadConfiguration(dataFolder.resolve(file).toFile());
    }

    @Test
    void v1TunedMigratesThroughTheChainToTheSplitV3Layout() throws Exception {
        installFixture("v1");

        Migrations.Result result = migrations().migrate();

        assertEquals(1, result.fromVersion());
        assertEquals(3, result.toVersion());
        assertEquals(List.of(2, 3), result.stepsApplied());

        // Both step backups exist; the version is now stamped 3.
        assertTrue(Files.isRegularFile(dataFolder.resolve("config-backup-v1/config.yml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("config-backup-v2/config.yml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("state/overrides.yml")));

        YamlConfiguration main = load("config.yml");
        assertFalse(Migrations.isLegacyLayout(main), "migrated config.yml still legacy-shaped");
        assertEquals(3, main.getInt("config-version"));
        assertFalse(main.getBoolean("modules.latency-compensation", true), "disabled toggle carried");
        assertTrue(main.getBoolean("modules.hit-registration", false), "enabled toggle kept from template");
        assertEquals("force-safe", main.getString("anticheat.mode"));
        assertEquals(List.of("GrimAC"), main.getStringList("anticheat.known"));
        assertTrue(main.getBoolean("debug.enabled"));

        assertEquals(16, load("hit-registration.yml").getInt("hit-registration.max-cps"));
        assertFalse(load("hit-registration.yml").getBoolean("hit-registration.fast-path.simulate-crits", true));
        assertEquals(40, load("latency-compensation.yml").getInt("latency-compensation.ping-offset-ms"));

        YamlConfiguration knockback = load("knockback.yml");
        assertEquals("custom", knockback.getString("knockback.profile"));
        assertEquals("cancel", knockback.getString("fishing-knockback.reel-in"));

        YamlConfiguration custom = load("profiles/legacy/custom.yml");
        assertEquals(0.42, custom.getDouble("knockback.base.horizontal"));
        assertEquals(0.36, custom.getDouble("knockback.base.vertical"));
        assertEquals(1.5, custom.getDouble("knockback.modifiers.sprint"));
        assertFalse(custom.getBoolean("knockback.modifiers.combos", true));

        // End to end: the migrated tree parses into the tuned profile.
        ConfigStore.Sources sources = new ConfigStore(dataFolder, resources, message -> {}).loadSources();
        SnapshotParser.Result parsed = SnapshotParser.parse(sources.main(), sources.knockback(),
                sources.hitReg(), sources.latency(), sources.profiles());
        assertFalse(parsed.snapshot().enabled(
                me.vexmc.mental.v5.feature.Feature.LATENCY_COMPENSATION));
        assertEquals("custom", parsed.snapshot().profileFor("world").name());
        assertEquals(0.42, parsed.snapshot().profileFor("world").base().horizontal());
    }

    @Test
    void v1UntunedKeepsTheLegacyProfileSelected() throws Exception {
        Files.writeString(dataFolder.resolve("config.yml"), """
                config-version: 1
                modules:
                  knockback:
                    enabled: true
                    base:
                      horizontal: 0.4
                      vertical: 0.4
                """, StandardCharsets.UTF_8);

        migrations().migrate();
        // custom.yml is NOT migrated (untouched knockback) — the bundle regenerates it.
        new ConfigStore(dataFolder, resources, message -> {}).ensureDefaultFiles();

        assertEquals("legacy-1.7", load("knockback.yml").getString("knockback.profile"));
        assertEquals("Custom", load("profiles/legacy/custom.yml").getString("display-name"),
                "an untuned migration keeps the bundled custom preset, not a migrated one");
    }

    @Test
    void v2StampsToV3AndCreatesTheOverlayIdempotently() throws Exception {
        installFixture("v2");

        Migrations.Result first = migrations().migrate();
        assertEquals(2, first.fromVersion());
        assertEquals(3, first.toVersion());
        assertEquals(List.of(3), first.stepsApplied());
        assertTrue(Files.isRegularFile(dataFolder.resolve("config-backup-v2/config.yml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("state/overrides.yml")));
        assertEquals(3, load("config.yml").getInt("config-version"));

        // Idempotent: a second run reads version 3 and does nothing.
        byte[] afterFirst = Files.readAllBytes(dataFolder.resolve("config.yml"));
        Migrations.Result second = migrations().migrate();
        assertEquals(3, second.fromVersion());
        assertTrue(second.stepsApplied().isEmpty());
        assertEquals(-1, java.util.Arrays.mismatch(afterFirst,
                Files.readAllBytes(dataFolder.resolve("config.yml"))), "config.yml unchanged on re-run");
    }

    @Test
    void freshTreeAndAlreadyMigratedTreeAreNoOps() throws Exception {
        // No config.yml on disk → nothing to migrate.
        Migrations.Result fresh = migrations().migrate();
        assertEquals(3, fresh.fromVersion());
        assertTrue(fresh.stepsApplied().isEmpty());
        assertFalse(Files.exists(dataFolder.resolve("config-backup-v1")));
        assertFalse(Files.exists(dataFolder.resolve("config-backup-v2")));

        // An already-v3 tree is untouched.
        installFixture("v3");
        byte[] before = Files.readAllBytes(dataFolder.resolve("config.yml"));
        Migrations.Result already = migrations().migrate();
        assertEquals(3, already.fromVersion());
        assertTrue(already.stepsApplied().isEmpty());
        assertEquals(-1, java.util.Arrays.mismatch(before,
                Files.readAllBytes(dataFolder.resolve("config.yml"))));
    }

    @Test
    void theWholeChainIsIdempotentOnAnAlreadyMigratedTree() throws Exception {
        installFixture("v1");
        migrations().migrate();
        byte[] afterFirst = Files.readAllBytes(dataFolder.resolve("config.yml"));

        Migrations.Result second = migrations().migrate();
        assertEquals(3, second.fromVersion());
        assertTrue(second.stepsApplied().isEmpty());
        assertEquals(-1, java.util.Arrays.mismatch(afterFirst,
                Files.readAllBytes(dataFolder.resolve("config.yml"))));
    }
}
