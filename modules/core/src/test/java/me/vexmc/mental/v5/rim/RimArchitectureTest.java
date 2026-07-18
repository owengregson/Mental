package me.vexmc.mental.v5.rim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The netty-realm allow-list pin (spec §3, §6; the {@code netty-fast-path} safe
 * list). The packet rim is the only core code that runs on netty threads, so it
 * must never reach a live entity through a Folia-forbidden accessor. This test
 * scans every rim SOURCE file and fails the build on any occurrence of the
 * forbidden accessors, so the pin holds by construction rather than by review.
 *
 * <p>Comments are stripped before scanning (a forbidden token may legitimately
 * appear in a doc comment explaining why it is forbidden), so the scan sees only
 * code.</p>
 */
class RimArchitectureTest {

    /**
     * The forbidden live-entity accessors — every one either routes through
     * {@code getHandle()} (throws off the owning region on Folia) or queries live
     * world/entity state that is undefined off a region thread. Identity in the
     * rim is a PacketEvents {@code User.getUUID()}; the tick is the {@code
     * TickClock} abstraction, never {@code Bukkit.getCurrentTick} directly; a
     * player's name is never read on the netty thread ({@code .getName()}).
     */
    private static final List<String> FORBIDDEN = List.of(
            "getEntityById",
            "getGameMode(",
            "getNearbyEntities",
            "getEntities()",
            "Bukkit.getCurrentTick",
            ".getHandle(",
            ".getName()");

    @Test
    void rimSourceUsesNoForbiddenLiveEntityAccessor() throws IOException {
        Path rimDir = locateRimSource();
        List<Path> sources = listJavaSources(rimDir);
        assertFalse(sources.isEmpty(), "no rim source files found under " + rimDir.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String code = stripComments(Files.readString(source));
            for (String forbidden : FORBIDDEN) {
                if (code.contains(forbidden)) {
                    violations.add(source.getFileName() + " uses forbidden accessor '" + forbidden + "'");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "the netty rim must touch no live-entity accessor:\n  " + String.join("\n  ", violations));
    }

    /** Removes block and line comments so a forbidden token in prose is not flagged. */
    private static String stripComments(String source) {
        String withoutBlock = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlock.replaceAll("//[^\\n]*", " ");
    }

    private static List<Path> listJavaSources(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> result = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".java")).forEach(result::add);
            return result;
        }
    }

    /** The Gradle test working directory is the module dir; the module dir also works if run from root. */
    private static Path locateRimSource() {
        String relative = "src/main/java/me/vexmc/mental/v5/rim";
        for (Path candidate : List.of(Path.of(relative), Path.of("core").resolve(relative))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not locate the rim source directory (" + relative + ")");
    }
}
