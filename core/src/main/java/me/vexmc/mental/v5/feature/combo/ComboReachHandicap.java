package me.vexmc.mental.v5.feature.combo;

import java.util.function.Supplier;
import me.vexmc.mental.platform.AttributeModifiers;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.ComboSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * The combo-hold reach handicap (design §1) — the deferred sub-feature that scales
 * DOWN a victim's interaction range WHILE their combo is active, so a launched
 * victim's own raycast shortens and cannot answer. Driven by the combo transition
 * point that fires the api events ({@link ComboEvents}): applied on combo START,
 * removed on EVERY end reason, both on the victim's owning region thread.
 *
 * <h2>Version tier (1.20.5+ only)</h2>
 * <p>The {@code ENTITY_INTERACTION_RANGE} attribute is client-synced from 1.20.5,
 * so shortening it makes the CLIENT's own raycast shorten — no phantom misses. Below
 * 1.20.5 the attribute is absent ({@link Attributes#entityInteractionRange()} null);
 * the sub-feature is a documented no-op with one loud boot line when enabled (the
 * platform-probe doctrine). <b>ViaVersion legacy clients ignore the synced attribute</b>
 * — the server cannot help them; this is documented, not something the server rejects.</p>
 *
 * <h2>The lever</h2>
 * <p>An ADDITIVE {@code mental:combo-reach} modifier (never a base rewrite, so it
 * composes with any third-party base) built and removed through the probe-once
 * {@link AttributeModifiers} seam — {@code MULTIPLY_SCALAR_1} at {@code scale − 1},
 * so the era 3.0 becomes 2.4 at the default {@code 0.8}. Removal is idempotent by
 * fixed identity.</p>
 *
 * <h2>Lifecycle safety — the three legs (§1)</h2>
 * <ol>
 *   <li><b>Apply/remove</b> ride the combo transition: {@link #onComboStart} on
 *       START, {@link #onComboEnd} on every end reason (EXPIRED / RETALIATION /
 *       GROUNDED / BLOWOUT / RETIRED / DISABLED), on the owning thread.</li>
 *   <li><b>NBT-leak sweep</b>: player attribute modifiers persist to the save, so a
 *       crash could leave the handicap in the file. {@link #enable} sweeps every
 *       online player, {@link #onJoin} sweeps each joiner, and {@link #disable}
 *       restores every online player — all by-identity, so a leaked modifier is
 *       cleared on sight even if we never applied it this session.</li>
 *   <li><b>Reload/disable mid-combo</b>: the module's DISABLED end already fires
 *       through {@link ComboEvents}, so {@link #onComboEnd} rides it; {@link #disable}
 *       is the belt-and-suspenders inline restore-all on scope close.</li>
 * </ol>
 *
 * <p>Zero-touch: with the sub-feature off {@link #onComboStart} constructs no
 * modifier and pays no probe (a single boolean read gates it); removal on end stays
 * unconditional so any lingering modifier self-heals. The transition legs run
 * through {@link Scheduling#ensureOn} — INLINE, because {@code ComboEvents.fire}
 * always runs on the victim's owning thread. Inline is load-bearing on the quit
 * path (audit quit-path conflict): the RETIRED end fires during
 * {@code PlayerQuitEvent}, and a deferred {@code runOn} dies at the next-tick
 * validity gate AFTER the disconnect already saved the modifier into the player's
 * NBT — a combat-log then persists the {@code mental:combo-reach} handicap, and
 * permanently so if the feature is off at their next join (no sweep runs). The
 * inline remove lands before the save, the same pre-save shape
 * {@code EphemeralDecoration.onQuit} established. The lifecycle sweeps
 * ({@link #enable}/{@link #onJoin}) stay deferred {@link Scheduling#runOn} (their
 * players are alive and staying); the scope close restores INLINE on the disabling
 * thread, where the scheduler may already be stopping and synchronous attribute
 * access is safe (the {@code EraReachAttribute} precedent).</p>
 */
public final class ComboReachHandicap implements Listener {

    private final Plugin plugin;
    private final Scheduling scheduling;
    private final Supplier<Snapshot> snapshot;

    public ComboReachHandicap(
            @NotNull Plugin plugin, @NotNull Scheduling scheduling, @NotNull Supplier<Snapshot> snapshot) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.snapshot = snapshot;
    }

    /** Whether this server exposes the interaction-range attribute (1.20.5+). */
    public boolean supported() {
        return Attributes.entityInteractionRange() != null && AttributeModifiers.supported();
    }

    /* ----------------------------- transition legs ----------------------------- */

    /**
     * Leg 1 apply — the combo just went active, so shorten the victim's reach for
     * its duration. No-op when the sub-feature is off (no modifier constructed, no
     * probe beyond one config read) or the attribute is absent.
     */
    public void onComboStart(@NotNull Player victim) {
        if (!supported()) {
            return;
        }
        ComboSettings.ReachHandicap config = config();
        if (!config.enabled()) {
            return; // sub-feature off ⇒ zero-touch, no modifier ever constructed
        }
        double scale = config.scale();
        // ensureOn: ComboEvents.fire is on the victim's owning thread, so this is
        // inline — and MUST match the end leg's inline shape, or a same-tick
        // start+end pair would apply (deferred) AFTER it removed (inline).
        scheduling.ensureOn(victim, () -> applyNow(victim, scale), () -> {});
    }

    /**
     * Leg 1 remove — the combo ended (any reason), so restore the victim's reach.
     * Unconditional (gated only on the attribute's presence): removal is idempotent
     * by identity, so a modifier applied under a since-disabled sub-feature is still
     * cleared here. INLINE via {@link Scheduling#ensureOn} — the quit path's
     * RETIRED end must strip the modifier before the disconnect save (see the
     * class javadoc; a deferred remove is dead on arrival for a quitting player).
     */
    public void onComboEnd(@NotNull Player victim) {
        if (!supported()) {
            return;
        }
        scheduling.ensureOn(victim, () -> removeNow(victim), () -> {});
    }

    /* ------------------------------ lifecycle legs ----------------------------- */

    /**
     * Leg 2 at feature enable — sweep every online player of any stale handicap (a
     * crash-leaked modifier from a previous boot), and print the one loud degrade
     * line when the sub-feature is enabled on a server that cannot honour it (below
     * 1.20.5). Runs on the enabling thread; the per-player sweep is scheduled.
     */
    public void enable() {
        ComboSettings.ReachHandicap config = config();
        if (config.enabled() && !supported()) {
            plugin.getLogger().warning(
                    "combo-hold reach-handicap is enabled but this server has no "
                    + "entity-interaction-range attribute (below 1.20.5) — the sub-feature is a "
                    + "no-op here. (Note: ViaVersion legacy clients ignore the synced attribute "
                    + "even on 1.20.5+; the server cannot shorten their reach.)");
            return;
        }
        if (!supported()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduling.runOn(player, () -> removeNow(player), () -> {});
        }
    }

    /**
     * Leg 2/3 at scope close — restore every online player INLINE on the disabling
     * thread (the scheduler may be stopping, so a deferred write could never run; the
     * disable path is the main / global thread where synchronous attribute access is
     * safe — the {@code EraReachAttribute#disableAll} shape).
     */
    public void disable() {
        if (!supported()) {
            return;
        }
        Attribute attribute = Attributes.entityInteractionRange();
        if (attribute == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                AttributeModifiers.removeMatching(instance);
            }
        }
    }

    /** Leg 2 on join — sweep the joiner of any stale handicap before a combo could apply one. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        if (!supported()) {
            return;
        }
        Player player = event.getPlayer();
        scheduling.runOn(player, () -> removeNow(player), () -> {});
    }

    /* --------------------------------- helpers --------------------------------- */

    private void applyNow(@NotNull Player victim, double scale) {
        Attribute attribute = Attributes.entityInteractionRange();
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = victim.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        // Clear any prior identity first so a re-apply never stacks a second modifier,
        // then add the fresh handicap for this combo.
        AttributeModifiers.removeMatching(instance);
        AttributeModifier modifier = AttributeModifiers.comboReach(scale);
        if (modifier != null) {
            instance.addModifier(modifier);
        }
    }

    private void removeNow(@NotNull Player victim) {
        Attribute attribute = Attributes.entityInteractionRange();
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = victim.getAttribute(attribute);
        if (instance != null) {
            AttributeModifiers.removeMatching(instance);
        }
    }

    @SuppressWarnings("unchecked")
    private ComboSettings.ReachHandicap config() {
        ComboSettings settings = snapshot.get().settings(
                (SettingsKey<ComboSettings>) Feature.COMBO_HOLD.settingsKey());
        return settings.reachHandicap();
    }
}
