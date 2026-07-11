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
            // damage-indicators heal-text key).
            "signature", Set.of(
                    "6781856643e401f3e1ff9f7901a8138f998367ccb369641d11399df6889f3af1"));

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
