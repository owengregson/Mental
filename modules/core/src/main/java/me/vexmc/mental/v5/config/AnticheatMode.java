package me.vexmc.mental.v5.config;

/**
 * How Mental coexists with movement-prediction anticheats (frozen from the
 * retired {@code config.AnticheatMode}). {@link #AUTO} disables netty-thread
 * pre-send only while such an anticheat is installed; {@link #FORCE_SAFE}
 * always disables it; {@link #OFF} never does.
 */
public enum AnticheatMode {
    AUTO,
    FORCE_SAFE,
    OFF
}
