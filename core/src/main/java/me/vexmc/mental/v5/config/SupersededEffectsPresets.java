package me.vexmc.mental.v5.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;

/**
 * Every bundled Combat Effects preset revision that a later round corrected,
 * exactly as it shipped — the effects twin of the kernel's
 * {@code SupersededPresets}, carrying the same RAW-BYTE contract from day one:
 * a preset file on disk that is byte-identical (after newline normalization)
 * to one of these was never tuned by the owner — it is the old bundle
 * verbatim, and {@link ConfigStore} upgrades it in place to the corrected
 * bundle. A file that differs in ANYTHING — a value, a comment, whitespace —
 * is an owner edit and is never touched. {@code custom.yml} is exempt
 * entirely: it is the owner's preset by definition, whatever its bytes.
 *
 * <p>The first archived revision landed in 2.5.5: the 2.5.3/2.5.4
 * {@code signature.yml} (before the {@code low-health-threshold-percent} +
 * {@code heal-text} rewrite), so a pristine install upgrades in place to the
 * corrected bundle on its next boot, exactly like the knockback presets.
 * {@code vanilla} is NOT here — it left the bundle in 2.5.5, and its retirement
 * (delete-if-pristine, keep-if-edited) is the 4 → 5 migration's job, not the
 * upgrade driver's.</p>
 */
public final class SupersededEffectsPresets {

    /**
     * SHA-256 hex of every superseded bundled revision's newline-normalized
     * text, keyed by preset stem ({@code signature} — never {@code custom},
     * never the retired {@code vanilla}).
     */
    private static final Map<String, Set<String>> ARCHIVED_HASHES = Map.of(
            // The 2.5.3/2.5.4 signature.yml, superseded by the 2.5.5 rewrite
            // (the low-health-threshold-hearts → -percent key and the new
            // damage-indicators heal-text key); the 2.5.5→2.6.1 revision,
            // superseded by the 2026-07-12 window/HP-units rewrite ({HEALTH} in
            // damage points, the roll-hold-ticks knob and its window prose); the
            // 2.6.2 revision, superseded by 2.7.0 adding the death-effects
            // kill-title block; and the 2.7.0 revision, superseded by 2.7.1
            // renaming crit-threshold-hearts → crit-threshold-percent.
            "signature", Set.of(
                    "6781856643e401f3e1ff9f7901a8138f998367ccb369641d11399df6889f3af1",
                    "40a57e598f9d0d397d6314e76ba351bd9ff163ae90cf23b30c7e9ebaf1cdd7ad",
                    // The 2.6.2 signature.yml, superseded by 2.7.0 adding the
                    // death-effects kill-title block; a pristine install upgrades
                    // in place to gain the "KILLED: {NAME}" title.
                    "f0cf052e062349b8dc8f03cade450480cde0f9ae065c106a7082218df5a05858",
                    // The 2.7.0 signature.yml, superseded by 2.7.1 replacing the
                    // absolute crit-threshold-hearts with crit-threshold-percent
                    // (of the victim's max health); a pristine install upgrades in
                    // place to the percent margin.
                    "808396ddabc6d91047ceda223cbe04c8a9a31a092fa63442f3327957cce973db"));

    private SupersededEffectsPresets() {}

    /**
     * Whether {@code fileText} is byte-identical (newline-normalized) to a
     * superseded bundled revision of {@code preset}.
     */
    public static boolean isSupersededBundleText(String preset, String fileText) {
        Set<String> hashes = ARCHIVED_HASHES.get(preset);
        if (hashes == null || fileText == null) {
            return false;
        }
        String normalized = fileText.replace("\r\n", "\n").replace('\r', '\n');
        return hashes.contains(sha256Hex(normalized));
    }

    /**
     * The archived hash set for {@code preset} (empty when none) — package-private
     * test seam for the completeness guard: the hash-archive test asserts these
     * EXACTLY equal the recomputed hashes of the texts under
     * {@code superseded-effects-bundles/}, so a constant can never exist without
     * its verbatim historical text (nor the reverse).
     */
    static Set<String> archivedHashes(String preset) {
        return ARCHIVED_HASHES.getOrDefault(preset, Set.of());
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
