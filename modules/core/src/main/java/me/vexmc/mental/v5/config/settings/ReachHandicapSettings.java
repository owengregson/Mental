package me.vexmc.mental.v5.config.settings;

/**
 * The combo reach-handicap module's one tunable (combo-hold §1) — the scale
 * applied to a combo victim's entity-interaction-range attribute while their
 * combo is active, so a launched victim's own raycast shortens and cannot answer.
 *
 * <p>Promoted to its own {@code modules.combo-reach-handicap} feature in 2.4.4: an
 * {@code enabled} boolean would be a registry smell (only {@code Feature}s carry
 * toggles, and the GUI derives its catalog from the enum), so the enable dissolves
 * INTO the module toggle — exactly like the POTS features — and this record holds
 * only the scale. {@code scale} lives in {@code [0.5, 1.0]}: a handicap only ever
 * SHORTENS reach, and {@code 0.87} takes the era 3.0 to 2.61 (the 2.4.5 retune —
 * the owner judged the previous {@code 0.8}/2.4-block floor too severe). The whole
 * feature is a byte-identical no-op because the MODULE defaults OFF.</p>
 */
public record ReachHandicapSettings(double scale) {

    /** Era-3.0-to-2.61 when the module is switched on; a no-op while the module is off. */
    public static final ReachHandicapSettings DEFAULTS = new ReachHandicapSettings(0.87);
}
