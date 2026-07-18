package me.vexmc.mental.v5.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;

/**
 * Every bundled rules-bundle revision that a later round corrected, exactly as it
 * shipped — the third member of the RAW-BYTE supersession family, alongside the
 * kernel's {@code SupersededPresets} (knockback profiles) and
 * {@link SupersededEffectsPresets} (Combat Effects presets), carrying the identical
 * contract: a bundle file on disk that is byte-identical (after newline
 * normalization) to one of these was never edited by the owner — it is the old
 * bundle verbatim, and {@link ConfigStore} upgrades it in place to the corrected
 * bundle. A file that differs in ANYTHING — a value, a comment, whitespace — is an
 * owner edit and is never touched.
 *
 * <p>The archive is <b>empty at 2.8.x</b>: the {@code ct8c}/{@code signature}/
 * {@code vanilla} bundles ship for the first time here, so no revision has yet
 * been superseded. The mechanism is wired end-to-end anyway so the FIRST time a
 * shipped bundle's module map is corrected, a pristine install upgrades in place
 * on its next boot — the operator gains the fix, an edited bundle keeps its edits.
 * A new archived hash is added here (with a provenance comment) the same round the
 * bundle changes, exactly as the effects twin gained its first entry in 2.5.5.</p>
 */
public final class SupersededBundles {

    /**
     * SHA-256 hex of every superseded bundled revision's newline-normalized text,
     * keyed by bundle stem. Empty until a round corrects a shipped bundle — add
     * the old revision's hash here the same round the {@code bundles/*.yml}
     * resource changes.
     */
    private static final Map<String, Set<String>> ARCHIVED_HASHES = Map.of();

    private SupersededBundles() {}

    /**
     * Whether {@code fileText} is byte-identical (newline-normalized) to a
     * superseded bundled revision of {@code bundle}.
     */
    public static boolean isSupersededBundleText(String bundle, String fileText) {
        Set<String> hashes = ARCHIVED_HASHES.get(bundle);
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
