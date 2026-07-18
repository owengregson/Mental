package me.vexmc.mental.v5.api;

import java.util.UUID;
import me.vexmc.mental.api.ComboView;
import me.vexmc.mental.api.MentalCombat;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.kernel.port.TickClock;
import me.vexmc.mental.v5.feature.combo.ComboViewBook;
import me.vexmc.mental.v5.session.SessionService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The gen-3 {@link MentalCombat} implementation. Held handles degrade to the
 * NONE/false shapes the moment combo detection stops (module toggle-off or
 * plugin disable) — never an exception, never stale ACTIVE state (§4).
 */
public final class MentalCombatService implements MentalCombat {

    private final SessionService sessions;
    private final ComboViewBook views;
    private final TickClock clock;

    public MentalCombatService(SessionService sessions, ComboViewBook views, TickClock clock) {
        this.sessions = sessions;
        this.views = views;
        this.clock = clock;
    }

    @Override
    public @NotNull ComboView comboOn(@NotNull UUID victim) {
        if (!sessions.comboDetectionLive()) {
            return ComboViewBook.NONE_VIEW;
        }
        return views.viewOf(victim);
    }

    @Override
    public boolean hurtWindowClear(@NotNull Player victim) {
        if (!sessions.comboDetectionLive()) {
            return false;   // the §4 defunct-handle shape
        }
        return WindowJudge.clear(victim.getNoDamageTicks(), victim.getMaximumNoDamageTicks());
    }

    @Override
    public long currentTick() {
        TickStamp now = clock.current();
        return now.known() ? now.value() : NO_TICK;
    }
}
