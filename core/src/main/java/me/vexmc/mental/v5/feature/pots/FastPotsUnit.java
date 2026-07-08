package me.vexmc.mental.v5.feature.pots;

import java.util.List;
import java.util.function.Supplier;
import me.vexmc.mental.kernel.wire.PositionRing;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.settings.FastPotsSettings;
import me.vexmc.mental.v5.feature.Feature;
import me.vexmc.mental.v5.feature.FeatureUnit;
import me.vexmc.mental.v5.feature.Scope;
import me.vexmc.mental.v5.feature.SettingsKey;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Redirects a steeply-thrown splash potion at the thrower's own predicted feet at
 * a multiplied speed — the classic "fast pot" self-heal, made reliable (POTS
 * family, owner directive 2026-07-04).
 *
 * <p>When a {@link ThrownPotion} whose item is a splash potion is launched by a
 * player looking down more steeply than {@code angle-degrees} (Bukkit pitch >
 * threshold), the launch velocity is re-aimed by {@link PotsAim}, which solves the
 * exact discrete potion flight so the burst lands just in front of the thrower's
 * predicted feet — extrapolated over the short flight from the thrower's current
 * per-tick velocity and led forward by {@code lead-ticks} so a running player moves
 * INTO the cloud — spending the least speed <em>within</em> the {@code
 * [min-speed-multiplier, max-speed-multiplier] × vanilla} band (default {@code
 * [0.5, 1.5]×}). The band is the only lever on which impact tick is reachable; every
 * velocity component stays free and the potion's spawn is never moved (velocity-only
 * redirect). Shallower throws are left byte-for-byte untouched (zero-touch outside
 * the band),
 * and LINGERING potions are excluded (their item is {@code LINGERING_POTION}, not
 * {@code SPLASH_POTION}) — this aids the instant splash self-heal, not the ground
 * cloud. What counts as a splash potion is {@link #splashItem(String)} — including
 * the 1.16.x–1.20.4 empty-item read of a tagless splash pot.</p>
 *
 * <p>Threading: {@link ProjectileLaunchEvent} fires on the owning region thread,
 * so the velocity write is inline and Folia-safe with no cross-region read. The
 * thrower's velocity is the {@link PositionRing} per-tick position delta ({@link
 * #throwerVelocity}), NOT {@link Player#getVelocity()} — the latter reads ~0 for a
 * player running on the GROUND (input-driven movement is client-authoritative and
 * not in the server's velocity field) and only carries momentum mid-jump, so a
 * getVelocity-based aim never leads a flat-running thrower and the burst lands
 * behind. The ring is the same client-authoritative source the combat view uses.</p>
 */
public final class FastPotsUnit implements FeatureUnit, Listener {

    /** The splash-potion item name — resolved by string so no Material constant is hard-linked. */
    private static final String SPLASH_POTION = "SPLASH_POTION";

    /** Below this launch speed there is no vanilla velocity to scale — leave it alone. */
    private static final double DEGENERATE_SPEED = 1.0e-6;

    private final Supplier<Snapshot> snapshot;

    /**
     * The per-player position ring — the thrower's velocity source. {@link
     * Player#getVelocity()} is ~0 for a player running on the GROUND (movement is
     * client-authoritative; the server's velocity field is not populated from ground
     * input), and only carries momentum mid-jump — so an un-led aim built from it
     * never leads a flat-running thrower and the burst lands behind. The position
     * ring is the client-authoritative per-tick position delta the whole combat stack
     * already trusts ({@code SessionService.buildView}'s {@code measuredVx/Vz}); it
     * reflects real ground movement.
     */
    private final PositionRing positions;

    public FastPotsUnit(@NotNull Supplier<Snapshot> snapshot, @NotNull PositionRing positions) {
        this.snapshot = snapshot;
        this.positions = positions;
    }

    @Override
    public Feature descriptor() {
        return Feature.FAST_POTS;
    }

    @Override
    public void assemble(Scope scope, Snapshot ignored) {
        scope.listen(this);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLaunch(@NotNull ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion potion)) {
            return;
        }
        ItemStack item = potion.getItem();
        if (item == null || !splashItem(item.getType().name())) {
            return; // not a splash potion (a lingering potion or a water bottle) — untouched
        }
        if (!(potion.getShooter() instanceof Player thrower)) {
            return; // dispenser / non-player source — untouched
        }

        FastPotsSettings settings = settings();
        // Bukkit pitch: positive is downward. Only a throw steeper than the threshold is redirected.
        if (thrower.getLocation().getPitch() <= settings.angleDegrees()) {
            return; // shallow — zero-touch outside the angle band
        }

        double vanillaSpeed = potion.getVelocity().length();
        if (vanillaSpeed < DEGENERATE_SPEED) {
            return; // no launch speed to scale
        }
        potion.setVelocity(redirect(potion.getLocation(), thrower, vanillaSpeed, settings, positions));
    }

    /**
     * Whether the material name read off a {@link ThrownPotion}'s item identifies a
     * splash potion. {@code AIR} is accepted as splash: on 1.16.x–1.20.4 the server
     * stores the projectile's item only when it differs from the entity's default
     * item or carries an NBT tag (javap, {@code EntityProjectileThrowable#setItem}),
     * while {@code CraftThrownPotion#getItem} reads the raw datawatcher without the
     * empty→default fallback (that lives in {@code getSuppliedItem}) — so a tagless
     * splash potion (e.g. a bare {@code /give}) reads back as AIR on exactly that
     * band. An empty item on a ThrownPotion can only mean the unstored default, and
     * {@code EntityPotion}'s default item IS the splash potion — the server's own
     * splash logic treats it so, and CraftBukkit ≤1.15.2 even coerces a missing item
     * to a splash stack ("ThrownPotion entity has no item?!"). Below 1.16 the store
     * is unconditional and from 1.20.5 the item-component rework made it so again,
     * so the AIR branch is dead everywhere the read is honest. Exactly {@code AIR}:
     * near-misses like {@code CAVE_AIR} never come off a ThrownPotion's item.
     */
    static boolean splashItem(@NotNull String materialName) {
        return SPLASH_POTION.equals(materialName) || "AIR".equals(materialName);
    }

    /**
     * The redirected launch velocity for a fast pot — public and production-derived
     * so the integration suite asserts against exactly this, not a re-derivation.
     * {@link PotsAim} solves the exact discrete potion flight to land the burst just
     * in front of the thrower's predicted feet (led forward by {@code lead-ticks}),
     * spending the least launch speed <em>within</em> the {@code [min, max] ×
     * vanillaSpeed} band — so the returned magnitude is bounded into that band, never
     * a fixed speed. The spawn ({@code launch}) is passed through untouched; only the
     * velocity is solved.
     */
    public static @NotNull Vector redirect(
            @NotNull Location launch, @NotNull Player thrower, double vanillaSpeed,
            @NotNull FastPotsSettings settings, @NotNull PositionRing positions) {
        Location feet = thrower.getLocation();
        Vector throwerVelocity = throwerVelocity(thrower, positions);
        double minSpeed = vanillaSpeed * settings.minSpeedMultiplier();
        double maxSpeed = vanillaSpeed * settings.maxSpeedMultiplier();
        PotsAim.Aim aim = PotsAim.aim(
                launch.getX(), launch.getY(), launch.getZ(),
                feet.getX(), feet.getY(), feet.getZ(),
                throwerVelocity.getX(), throwerVelocity.getY(), throwerVelocity.getZ(),
                minSpeed, maxSpeed, settings.leadTicks());
        return new Vector(aim.x(), aim.y(), aim.z());
    }

    /**
     * The thrower's real per-tick velocity for the aim — the position-ring delta
     * between the two most recent samples (client-authoritative, so it reflects flat
     * GROUND running, which {@link Player#getVelocity()} does not). Falls back to
     * {@link Player#getVelocity()} only when the ring has fewer than two samples (an
     * untracked, out-of-combat thrower) — imperfect there, but a self-heal pot is a
     * combat action, so the thrower is normally tracked and the ring is populated.
     * Public + static so the integration suite derives the expected redirect through
     * exactly this seam, not a re-derivation.
     */
    public static @NotNull Vector throwerVelocity(@NotNull Player thrower, @NotNull PositionRing positions) {
        List<PositionRing.Sample> recent = positions.recent(thrower.getUniqueId(), 2);
        if (recent.size() >= 2) {
            PositionRing.Sample previous = recent.get(0);
            PositionRing.Sample latest = recent.get(1);
            return new Vector(
                    latest.x() - previous.x(), latest.y() - previous.y(), latest.z() - previous.z());
        }
        return thrower.getVelocity();
    }

    @SuppressWarnings("unchecked")
    private FastPotsSettings settings() {
        return snapshot.get().settings(
                (SettingsKey<FastPotsSettings>) Feature.FAST_POTS.settingsKey());
    }
}
