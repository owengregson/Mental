package me.vexmc.mental.v5.feature.combo;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.vexmc.mental.api.ComboView;
import me.vexmc.mental.api.MentalCombat;
import me.vexmc.mental.kernel.combo.ComboViewState;
import me.vexmc.mental.kernel.model.TickStamp;
import org.jetbrains.annotations.NotNull;

/**
 * The gen-3 published-view map: one immutable {@link ComboView} per victim,
 * written on the victim's session thread at every transition, readable from
 * any thread (§6). NONE-shaped states are removals — the map only ever holds
 * DEVELOPING/ACTIVE views, so a quit/retire leaves nothing behind.
 */
public final class ComboViewBook {

    /**
     * The shared NONE view — a victim with no chain, an offline victim, or a
     * UUID Mental has never seen. Exposed so {@code MentalCombatService} answers
     * the §4 defunct-handle shape from ONE constant (never a fresh allocation).
     */
    public static final ComboView NONE_VIEW = PublishedComboView.NONE;

    private final ConcurrentHashMap<UUID, ComboView> views = new ConcurrentHashMap<>();

    /**
     * Publishes {@code state} for {@code victim} — an ACTIVE/DEVELOPING view, or
     * a removal when the state is NONE (or null). Called on the victim's session
     * (owning region) thread right after a tracker mutation, so the write needs
     * no locking; readers on any thread see one whole {@link ComboView} or none.
     */
    public void publish(@NotNull UUID victim, ComboViewState state) {
        if (state == null || state.none()) {
            views.remove(victim);
            return;
        }
        views.put(victim, PublishedComboView.of(state));
    }

    /** Drops the victim's published view (retire/quit) — leaves the NONE shape behind. */
    public void forget(@NotNull UUID victim) {
        views.remove(victim);
    }

    /** Clears every published view (module/plugin teardown). */
    public void clear() {
        views.clear();
    }

    /** The victim's published view, or {@link #NONE_VIEW} when none is held. Any thread. */
    public @NotNull ComboView viewOf(@NotNull UUID victim) {
        ComboView view = views.get(victim);
        return view == null ? NONE_VIEW : view;
    }

    /** The api sentinel translation — kernel NO_TICK (MIN_VALUE) must never leak. */
    static long tickValue(TickStamp stamp) {
        return stamp != null && stamp.known() ? stamp.value() : MentalCombat.NO_TICK;
    }

    /** The immutable published shape — one per victim, never a live tracker reference. */
    record PublishedComboView(ComboView.State state, UUID attackerId, int hits,
                              long lastKnockTick, long gapDeadlineTick) implements ComboView {

        static final PublishedComboView NONE = new PublishedComboView(
                ComboView.State.NONE, null, 0, MentalCombat.NO_TICK, MentalCombat.NO_TICK);

        static PublishedComboView of(ComboViewState state) {
            return new PublishedComboView(
                    state.active() ? ComboView.State.ACTIVE : ComboView.State.DEVELOPING,
                    state.attackerId(), state.hits(),
                    tickValue(state.lastKnockTick()), tickValue(state.gapDeadline()));
        }
    }
}
