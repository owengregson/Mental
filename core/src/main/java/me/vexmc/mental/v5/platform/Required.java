package me.vexmc.mental.v5.platform;

import java.util.function.Supplier;
import me.vexmc.mental.v5.feature.Feature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A manifest entry whose handle MUST resolve on every supported server — a miss
 * is a mapping break, not an expected absence. The resolution TECHNIQUE is
 * supplied by the caller (an {@code Attributes}/{@code Enchantments} name-probe,
 * an adapter probe): {@code Required} never re-derives it, it only owns the
 * outcome.
 *
 * <p>A missed {@link #owned owned} entry disables its {@link Feature} owner (one
 * loud log, at {@link PlatformProfile#resolveDisabled}); a missed
 * {@link #engineCritical} entry declares no owner and fails the boot instead.
 * The value is captured once at construction; the public surface is
 * presence-typed, never a bare {@code @Nullable}.</p>
 *
 * @param <T> the resolved handle type (an {@code Attribute}, an {@code Enchantment}, …)
 */
public final class Required<T> implements ManifestEntry {

    private final String name;
    private final @Nullable Feature owner; // null ⇒ engine-critical (boot fail on absence)
    private final @Nullable T value;

    private Required(@NotNull String name, @Nullable Feature owner, @Nullable T value) {
        this.name = name;
        this.owner = owner;
        this.value = value;
    }

    /** Resolve now via {@code resolver}; a {@code null} result disables {@code owner}. */
    public static <T> @NotNull Required<T> owned(
            @NotNull String name, @NotNull Feature owner, @NotNull Supplier<@Nullable T> resolver) {
        return new Required<>(name, owner, resolver.get());
    }

    /** Resolve now; a {@code null} result fails the boot (engine-critical, no owner). */
    public static <T> @NotNull Required<T> engineCritical(
            @NotNull String name, @NotNull Supplier<@Nullable T> resolver) {
        return new Required<>(name, null, resolver.get());
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public boolean present() {
        return value != null;
    }

    /** Whether a miss fails the boot rather than disabling a single feature. */
    public boolean engineCritical() {
        return owner == null;
    }

    /** The feature disabled when this entry is absent, or {@code null} when engine-critical. */
    public @Nullable Feature owner() {
        return owner;
    }

    /** The resolved handle; call only when {@link #present()}. */
    public @NotNull T get() {
        if (value == null) {
            throw new IllegalStateException("required platform handle " + name + " is absent");
        }
        return value;
    }

    @Override
    public @NotNull String describe() {
        if (present()) {
            return name + "=present";
        }
        return name + (engineCritical() ? "=MISSING(engine-critical)" : "=MISSING(disables " + owner + ")");
    }
}
