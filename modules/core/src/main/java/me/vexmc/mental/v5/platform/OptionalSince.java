package me.vexmc.mental.v5.platform;

import java.util.Optional;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A manifest entry whose handle is EXPECTED to be absent below a declared
 * version: its absence is a typed, quiet outcome with a fallback declared right
 * at the entry — never a mapping break, never a bare {@code @Nullable} at a call
 * site. (A server at or above {@link #since()} that still fails to resolve is a
 * mapping break the resolver's own probe loud-logs; that is the adapters' job,
 * not this record's.)
 *
 * <p>The declared fallback is what a caller uses when the handle is absent: a
 * same-typed value for flag entries (e.g. {@code false} for a capability flag),
 * or {@code null} with a {@link #fallbackNote() note} for handle entries whose
 * caller substitutes a numeric of its own (e.g. an attribute absent below 1.20.5
 * where the caller uses the vanilla constant). Either way the fallback is
 * DECLARED, not a magic literal buried at the read site.</p>
 *
 * @param <T> the resolved handle / flag type
 */
public final class OptionalSince<T> implements ManifestEntry {

    private final String name;
    private final String since;
    private final @Nullable T fallback;
    private final String fallbackNote;
    private final @Nullable T value;

    private OptionalSince(
            @NotNull String name, @NotNull String since,
            @Nullable T fallback, @NotNull String fallbackNote, @Nullable T value) {
        this.name = name;
        this.since = since;
        this.fallback = fallback;
        this.fallbackNote = fallbackNote;
        this.value = value;
    }

    /** Resolve now via {@code resolver}; absence yields the declared {@code fallback}. */
    public static <T> @NotNull OptionalSince<T> resolve(
            @NotNull String name, @NotNull String since, @Nullable T fallback,
            @NotNull String fallbackNote, @NotNull Supplier<@Nullable T> resolver) {
        return new OptionalSince<>(name, since, fallback, fallbackNote, resolver.get());
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public boolean present() {
        return value != null;
    }

    /** The version the handle first appears, e.g. {@code 1.20.5}. Never blank. */
    public @NotNull String since() {
        return since;
    }

    /** The resolved handle when present. */
    public @NotNull Optional<T> value() {
        return Optional.ofNullable(value);
    }

    /** The resolved handle if present, else the declared fallback (which may itself be {@code null}). */
    public @Nullable T orFallback() {
        return value != null ? value : fallback;
    }

    /** The human description of what a caller does when the handle is absent. Never blank. */
    public @NotNull String fallbackNote() {
        return fallbackNote;
    }

    @Override
    public @NotNull String describe() {
        return present() ? name + "=present" : name + "=absent(since " + since + ", " + fallbackNote + ")";
    }
}
