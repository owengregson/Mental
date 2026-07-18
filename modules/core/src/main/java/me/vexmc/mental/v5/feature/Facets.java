package me.vexmc.mental.v5.feature;

/**
 * A feature's per-surface declaration (B5): for each of the four combat
 * surfaces, either Phase 4 HANDLES it, or it is explicitly NONE with a stated
 * why. There is no default — a record forces all four to be provided at
 * construction, so a new feature constant cannot silently omit a surface. A
 * feature-registry test enumerates the descriptors and fails on any null.
 *
 * <p>The four surfaces: {@code serverRule} (a Bukkit-event rule that changes
 * behavior), {@code clientPresentation} (packet spoof/suppress the client
 * sees), {@code fastPathDamage} (a pure component composed into the fast-path
 * hit), and {@code vanillaPathDamage} (a component composed at the EDBEE entry
 * for hits Mental did not originate).</p>
 */
public record Facets(
        Facet serverRule,
        Facet clientPresentation,
        Facet fastPathDamage,
        Facet vanillaPathDamage) {

    /** A surface is either handled in Phase 4 or explicitly declared absent. */
    public sealed interface Facet {
        record Handled() implements Facet {}
        record None(String why) implements Facet {}
    }

    /** Shorthand: a surface Phase 4 handles. */
    public static Facet handled() {
        return new Facet.Handled();
    }

    /** Shorthand: a surface a feature does not touch, with the reason. */
    public static Facet none(String why) {
        return new Facet.None(why);
    }
}
