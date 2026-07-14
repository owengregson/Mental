package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The kill-title token substitution — pure string work with NO PacketEvents,
 * so it stands alone. {@code {NAME}}/{@code {VICTIM}} are the slain player,
 * {@code {KILLER}} the killer, {@code {PROTECT_SECONDS}} the Drop Protection
 * window (blank when off). Substitution is on the RAW ampersand string, so the
 * colour codes ride through untouched for the downstream deserialization.
 */
class KillTitleRenderTest {

    @Test
    void substitutesEveryTokenAndKeepsColourCodes() {
        String out = DeathEffectsListener.substituteTokens(
                "&c&lKILLED:&r &f{NAME}&r", "Steve", "Alex", "15");
        assertEquals("&c&lKILLED:&r &fSteve&r", out, "the & codes are preserved verbatim");
    }

    @Test
    void victimAliasAndKillerAndProtectSeconds() {
        String out = DeathEffectsListener.substituteTokens(
                "{KILLER} killed {VICTIM} — protected {PROTECT_SECONDS}s", "Steve", "Alex", "15");
        assertEquals("Alex killed Steve — protected 15s", out);
    }

    @Test
    void blankProtectSecondsWhenFeatureOff() {
        String out = DeathEffectsListener.substituteTokens(
                "protected for &f&n{PROTECT_SECONDS}s", "Steve", "Alex", "");
        assertEquals("protected for &f&ns", out, "an off feature leaves the token blank");
    }

    @Test
    void emptyTemplateRendersEmpty() {
        assertEquals("", DeathEffectsListener.substituteTokens("", "Steve", "Alex", "15"));
        assertEquals("", DeathEffectsListener.substituteTokens(null, "Steve", "Alex", "15"));
    }
}
