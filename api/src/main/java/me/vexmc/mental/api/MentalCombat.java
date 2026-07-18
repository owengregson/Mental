package me.vexmc.mental.api;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The authoritative combat-state query service (generation 3). Obtained from
 * {@link Mental.MentalApi#combat()} — non-null only while a combo-family module
 * is running. Every method here answers from published, immutable state: the
 * combo machine writes an immutable view on the victim's session thread at each
 * transition and readers observe it without touching a live tracker.
 *
 * <p><b>The tick frame (§5.7 of the integration surface).</b> Every tick value
 * this surface exposes — {@link #currentTick()}, {@link ComboView#lastKnockTick()},
 * {@link ComboView#gapDeadlineTick()}, and every event {@code getTick()} /
 * {@code getStartedTick()} / {@code getEndedTick()} / {@code getGapDeadlineTick()}
 * — is in Mental's own session clock frame. On Paper that frame is
 * {@link org.bukkit.Bukkit#getCurrentTick()}; on Folia and the legacy tier it is
 * Mental's own global-region counter. The frame is monotonic and shared across
 * all victims on the server, but it is NOT comparable to the server tick on
 * Folia/legacy, nor to any foreign plugin's counter. The only sanctioned
 * comparisons are deltas against other values drawn from this surface — most
 * usefully {@code gapDeadlineTick() - currentTick()} for "ticks remaining until
 * the chain lapses", which a consumer then schedules by in its own scheduler.</p>
 */
public interface MentalCombat {

    /** Sentinel for "no tick": no chain clock, or the frame is not running. */
    long NO_TICK = -1;

    /**
     * Snapshot of the combo machine for {@code victim}. Never null and never
     * throws — state NONE (attackerId null, ticks {@link #NO_TICK}) for a
     * victim with no chain, an offline victim, or a UUID Mental has never
     * seen. Callable from ANY thread: the implementation publishes an
     * immutable view at each transition (write on the victim's session
     * thread, read anywhere). An on-thread call (the victim's owning region
     * thread) observes the exact current state; an off-thread call may lag
     * by at most the in-flight transition, and is never torn.
     */
    @NotNull ComboView comboOn(@NotNull UUID victim);

    /**
     * The pinned hurt-window admit test for {@code victim}:
     * {@code victim.getNoDamageTicks() <= victim.getMaximumNoDamageTicks() / 2}
     * (integer division — the vanilla admit gate). PINNED AS THE CONTRACT:
     * this deliberately does NOT track Mental's internal "+1 staleness"
     * variant or any other fast-path read — integrators get ONE stable,
     * tested expression for "would a fresh hurt ship cleanly rather than be
     * window-swallowed". If Mental ever retunes its own boundary family,
     * this expression only changes with a Capability bump. Victim's owning
     * region thread only (it reads the live entity). Never re-derive this
     * for the Mental-integration decision; your own Mental-agnostic
     * vanilla-window reads are unaffected.
     */
    boolean hurtWindowClear(@NotNull Player victim);

    /** The current tick in this surface's clock frame. Any thread. */
    long currentTick();
}
