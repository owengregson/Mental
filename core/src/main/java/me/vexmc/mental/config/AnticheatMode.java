package me.vexmc.mental.config;

/**
 * How Mental coexists with movement-prediction anticheats.
 *
 * <p>Prediction engines (GrimAC, Vulcan) verify client movement against the
 * velocity the server itself produced. Everything Mental applies through the
 * server pipeline is therefore compatible by construction; the one risky
 * behavior is pre-sending velocity packets from the netty thread ahead of the
 * tick. {@link #AUTO} disables exactly that, only while such an anticheat is
 * installed.</p>
 */
public enum AnticheatMode {
    AUTO,
    FORCE_SAFE,
    OFF
}
