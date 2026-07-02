package me.vexmc.mental.kernel.model;

/**
 * A hit's identity — a monotonically increasing long minted from a global
 * counter at the hit's origin (spec §3.1). Withdrawal, arbitration and
 * journaling key on this value; there is deliberately no "withdraw all for a
 * victim" operation (B4).
 */
public record HitId(long value) {
}
