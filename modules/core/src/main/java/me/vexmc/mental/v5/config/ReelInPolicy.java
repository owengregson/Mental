package me.vexmc.mental.v5.config;

/**
 * What reeling in a hooked entity does (frozen from the retired
 * {@code config.ReelInPolicy}).
 *
 * <p>{@link #LEGACY} applies the 1.7.10 pull formula on every version;
 * {@link #CANCEL} suppresses the pull entirely; {@link #VANILLA} leaves the
 * server's native reel untouched.</p>
 */
public enum ReelInPolicy {
    LEGACY,
    CANCEL,
    VANILLA
}
