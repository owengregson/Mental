package me.vexmc.mental.v5.config.settings;

/**
 * The {@code pot-fill} feature's tunables (the POTS family, owner directive
 * 2026-07-04). Two knobs and nothing era-historical about them — this is a
 * PvP-server convenience command, not a 1.7/1.8 restoration — so the defaults
 * are the maximally-conservative "on, free, op-gated" shape:
 *
 * <ul>
 *   <li>{@code permission} — the node a sender must hold to run {@code /potfill};
 *       {@code mental.pots.fill} by default. A blank value would open the command
 *       to everyone, so the parser keeps the default on blank.</li>
 *   <li>{@code costPerPotion} — the per-potion charge through the soft Vault
 *       bridge; {@code 0.0} (free) by default, which needs no economy plugin at
 *       all. A positive value charges per filled potion and partial-fills to what
 *       the player can afford.</li>
 * </ul>
 *
 * <p>Note this record carries NO enablement flag — like every feature, POT_FILL's
 * on/off lives on its {@code modules.*} toggle ({@code Snapshot.enabled}).</p>
 */
public record PotFillSettings(
        String permission,
        double costPerPotion) {

    /** The default permission node — op-gated by convention, refuse without it. */
    public static final String DEFAULT_PERMISSION = "mental.pots.fill";

    public static final PotFillSettings DEFAULTS = new PotFillSettings(DEFAULT_PERMISSION, 0.0);
}
