package me.vexmc.mental.v5.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The optional PlaceholderAPI bridge, exercised with PAPI absent from the test
 * classpath (the zero-touch case): {@code apply} must return the raw string
 * untouched and never throw, and {@code available()} must be false — so a
 * server without PlaceholderAPI shows the raw text, exactly as designed.
 */
class PlaceholdersTest {

    @Test
    void absentPapiReportsUnavailable() {
        Placeholders.reset();
        assertFalse(Placeholders.available(), "PAPI is not on the test classpath");
    }

    @Test
    void absentPapiReturnsRawUnchanged() {
        Placeholders.reset();
        assertEquals("hi %server_online% &c{NAME}",
                Placeholders.apply(null, "hi %server_online% &c{NAME}"),
                "with no PAPI the raw string rides through untouched, codes and tokens intact");
    }

    @Test
    void nullAndEmptyPassStraightThrough() {
        assertNull(Placeholders.apply(null, null));
        assertEquals("", Placeholders.apply(null, ""));
    }
}
