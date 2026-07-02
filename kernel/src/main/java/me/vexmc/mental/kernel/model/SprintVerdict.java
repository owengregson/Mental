package me.vexmc.mental.kernel.model;

/**
 * The attack-time sprint answer, stamped at registration (from the connection
 * domain's {@code SprintWire}) and consumed by the authoritative pass.
 * {@code fresh} is the wire-ordered w-tap freshness, or {@code null} when no
 * wire view existed at registration (the pass then falls back to its own
 * ledger); {@code at} records the tick the verdict was read.
 */
public record SprintVerdict(boolean sprinting, Boolean fresh, TickStamp at) {
}
