package me.vexmc.mental.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Combat Test 8c Cleaving enchantment's registry seam (spec §2.9). Cleaving
 * is a real, applies-to-axes enchantment on modern Paper; making it real needs a
 * registry write, which the frozen enchantment registry only tolerates on a
 * modern-enough server. This class contains that write end to end — the
 * unfreeze → register → refreeze is here and nowhere else — and never lets it
 * touch boot: <b>every</b> reflective step is guarded, and any miss (below the
 * floor, an unexpected NMS shape, a construction failure) resolves to
 * {@link Optional#empty()} plus ONE loud degrade line (mandate B10). The damage
 * and shield clusters then fold Cleaving in as level {@code 0} — the documented
 * gap (spec §5, "Cleaving below its registry floor").
 *
 * <h2>The floor</h2>
 * <p>Enchantments became data-driven registry entries in 1.20.5 and the
 * frozen-registry unfreeze needed to inject one at runtime settles at
 * <b>~1.21.3</b> (the registry-backed {@code Enchantment} interface; our matrix's
 * first entry at or above it is 1.21.4). The decision is made by <em>probe</em>,
 * not a version parse: {@link #install} resolves the whole reflective chain
 * (server handle → registry access → the enchantment {@code MappedRegistry} →
 * its {@code frozen} field) and only writes when every link resolves. Where the
 * chain is incomplete the probe misses and the seam degrades loud.
 *
 * <p><b>Provenance note.</b> The exact NMS shape here is the modern Mojang-mapped
 * layout (1.20.5+ mappings); it could not be javap-pinned against a cached live
 * jar in this worktree, so the injection is a guarded best-effort verified by the
 * integration matrix (1.21.4 / 1.21.11). The seam's <em>contract</em> — resolve
 * an already-present {@code cleaving} enchant, else attempt the write, else
 * degrade loud, and expose {@link CleavingHandle#levelOf} either way — holds
 * regardless of whether the write lands.</p>
 */
public final class CleavingRegistrar {

    /** The keys a Cleaving enchant may be registered under — the injected one first, then a vanilla-namespaced datapack fallback. */
    private static final String INJECTED_NAMESPACE = "mental";
    private static final String CLEAVING_KEY = "cleaving";

    /** Mojang-mapped {@code MappedRegistry.frozen} spellings across the modern range (guarded scan). */
    private static final String[] FROZEN_FIELD_NAMES = {"frozen", "l"};

    private static volatile boolean installed;
    private static volatile @NotNull Optional<CleavingHandle> handle = Optional.empty();
    private static volatile @NotNull String state = "not yet installed";

    private CleavingRegistrar() {}

    /**
     * Resolves or injects the Cleaving enchant, ONCE per JVM (idempotent — a
     * reload that re-enables the feature reuses the cached result). Returns the
     * handle when Cleaving is real on this server, {@link Optional#empty()} plus
     * one {@code log} line otherwise. Never throws.
     */
    public static synchronized @NotNull Optional<CleavingHandle> install(@NotNull Consumer<String> log) {
        if (installed) {
            return handle;
        }
        installed = true;
        try {
            // 1. Already registered (an admin datapack, or a prior boot this JVM)? Resolve and reuse.
            Enchantment existing = resolveExisting();
            if (existing != null) {
                handle = Optional.of(new RegisteredHandle(existing));
                state = "resolved existing " + existing.getKey();
                return handle;
            }
            // 2. Attempt the registry write; a null return means the probe missed
            //    (below the floor / unexpected shape) — degrade, do not throw.
            Enchantment injected = inject();
            if (injected != null) {
                handle = Optional.of(new RegisteredHandle(injected));
                state = "injected " + injected.getKey();
                return handle;
            }
            state = "unavailable — registry injection below its ~1.21.3 floor or the modern shape did not resolve";
        } catch (Throwable failure) {
            // Any reflective surprise ⇒ behave like below-floor. Never poison boot.
            state = "unavailable — injection failed: " + failure;
        }
        handle = Optional.empty();
        log.accept("Cleaving enchant " + state + "; ct8c cleaving folds in as level 0 (spec §5 gap).");
        return handle;
    }

    /** The cached handle from {@link #install} — empty until installed, or when the seam degraded. */
    public static @NotNull Optional<CleavingHandle> handle() {
        return handle;
    }

    /** For the boot report / manifest: the resolved Cleaving state. */
    public static @NotNull String describe() {
        return "cleaving=" + state;
    }

    /* ------------------------------------------------------------------ */
    /*  Resolution / injection (all guarded — a miss returns null)         */
    /* ------------------------------------------------------------------ */

    /**
     * An already-registered Cleaving enchant, under the injected key first then a
     * {@code minecraft:cleaving} datapack key, or {@code null}. Uses the
     * deprecated {@code getByKey} (present 1.13+) reflectively so class-init never
     * links a symbol absent on the backport targets.
     */
    private static @Nullable Enchantment resolveExisting() {
        Enchantment mental = enchantByKey(INJECTED_NAMESPACE, CLEAVING_KEY);
        if (mental != null) {
            return mental;
        }
        return enchantByKey(NamespacedKey.MINECRAFT, CLEAVING_KEY);
    }

    @SuppressWarnings("deprecation") // getByKey is the cross-version key accessor; registry lookup below 1.20.5
    private static @Nullable Enchantment enchantByKey(@NotNull String namespace, @NotNull String key) {
        try {
            NamespacedKey namespacedKey = new NamespacedKey(namespace, key);
            Method getByKey = Enchantment.class.getMethod("getByKey", NamespacedKey.class);
            Object resolved = getByKey.invoke(null, namespacedKey);
            return resolved instanceof Enchantment enchantment ? enchantment : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    /**
     * The registry write: reuse a vanilla enchant's registered value (its
     * intrinsic effect is discarded downstream — {@code ct8c-damage} overwrites
     * the BASE with its own composition, which reads only the Cleaving LEVEL and
     * applies {@code 1+level}), re-registered under the injected key. Returns the
     * new Bukkit {@link Enchantment}, or {@code null} where the modern registry
     * shape does not resolve. Runs the unfreeze → register → refreeze inside this
     * one method; the registry is refrozen in a {@code finally} so a mid-write
     * throw never leaves it open.
     */
    private static @Nullable Enchantment inject() throws ReflectiveOperationException {
        Object registry = enchantmentRegistry();
        if (registry == null) {
            return null; // below the floor — the registry access chain did not resolve
        }
        Field frozen = frozenField(registry.getClass());
        if (frozen == null) {
            return null; // not a MappedRegistry with a known frozen flag — degrade
        }
        Object template = anyRegisteredValue(registry);
        if (template == null) {
            return null;
        }
        boolean wasFrozen = frozen.getBoolean(registry);
        try {
            frozen.setBoolean(registry, false);
            registerValue(registry, template);
        } finally {
            frozen.setBoolean(registry, wasFrozen); // refreeze to the prior state, always
        }
        // The Bukkit view of the freshly registered key.
        return enchantByKey(INJECTED_NAMESPACE, CLEAVING_KEY);
    }

    /**
     * The NMS enchantment {@code MappedRegistry} via the server handle's
     * {@code registryAccess()} and the Mojang-mapped {@code Registries.ENCHANTMENT}
     * resource key, or {@code null} on any missing link. Modern layout only
     * (1.20.5+ Mojang mappings) — the probe that pins the floor.
     */
    private static @Nullable Object enchantmentRegistry() throws ReflectiveOperationException {
        Object server = Bukkit.getServer();
        if (server == null) {
            return null; // no live server (unit-test context) — degrade cleanly
        }
        Object nmsServer = invokeNoArg(server, "getServer");
        if (nmsServer == null) {
            return null;
        }
        Object registryAccess = invokeNoArg(nmsServer, "registryAccess");
        if (registryAccess == null) {
            return null;
        }
        Object enchantmentRegistryKey = enchantmentResourceKey();
        if (enchantmentRegistryKey == null) {
            return null;
        }
        // RegistryAccess.lookup(ResourceKey) -> Optional<Registry<...>>, or the
        // older lookupOrThrow(ResourceKey) -> Registry<...>. Try both spellings.
        for (String method : new String[] {"lookupOrThrow", "registryOrThrow"}) {
            try {
                Method lookup = registryAccess.getClass().getMethod(method, enchantmentRegistryKey.getClass().getSuperclass());
                return lookup.invoke(registryAccess, enchantmentRegistryKey);
            } catch (NoSuchMethodException | RuntimeException tryNext) {
                // fall through to the Optional-returning form
            }
        }
        try {
            Method lookup = registryAccess.getClass().getMethod("lookup", enchantmentRegistryKey.getClass().getSuperclass());
            Object optional = lookup.invoke(registryAccess, enchantmentRegistryKey);
            if (optional instanceof Optional<?> present && present.isPresent()) {
                return present.get();
            }
        } catch (NoSuchMethodException | RuntimeException absent) {
            return null;
        }
        return null;
    }

    /** The Mojang-mapped {@code Registries.ENCHANTMENT} ResourceKey, or {@code null} below the modern registry layout. */
    private static @Nullable Object enchantmentResourceKey() {
        try {
            Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
            Field enchantment = registries.getField("ENCHANTMENT");
            return enchantment.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    /** The {@code MappedRegistry.frozen} boolean field on {@code registryClass} or a superclass, or {@code null}. */
    private static @Nullable Field frozenField(@NotNull Class<?> registryClass) {
        for (Class<?> cursor = registryClass; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            for (String name : FROZEN_FIELD_NAMES) {
                try {
                    Field candidate = cursor.getDeclaredField(name);
                    if (candidate.getType() == boolean.class) {
                        candidate.setAccessible(true);
                        return candidate;
                    }
                } catch (NoSuchFieldException tryNext) {
                    // next spelling
                }
            }
        }
        return null;
    }

    /** Any already-registered enchantment value (the injection template) via {@code Iterable.iterator()}, or {@code null}. */
    private static @Nullable Object anyRegisteredValue(@NotNull Object registry) {
        if (registry instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Registers {@code value} under the injected key. Builds the Mojang-mapped
     * {@code ResourceKey<Enchantment>} for {@code mental:cleaving} and calls the
     * registry's {@code register(ResourceKey, T, RegistrationInfo)} — the modern
     * three-arg form — falling back to the older two-arg spellings.
     */
    private static void registerValue(@NotNull Object registry, @NotNull Object value)
            throws ReflectiveOperationException {
        Object resourceKey = injectedResourceKey();
        if (resourceKey == null) {
            throw new ReflectiveOperationException("could not build the mental:cleaving resource key");
        }
        for (Method method : registry.getClass().getMethods()) {
            if (!method.getName().equals("register") && !method.getName().equals("registerMapping")) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length >= 2 && params[0].isInstance(resourceKey) && params[1].isInstance(value)) {
                Object[] args = new Object[params.length];
                args[0] = resourceKey;
                args[1] = value;
                // A trailing RegistrationInfo/lifecycle arg (modern) → pass the
                // registry's own default where the type exposes a BUILT_IN/EXPERIMENTAL constant.
                for (int i = 2; i < params.length; i++) {
                    args[i] = defaultFor(params[i]);
                }
                method.invoke(registry, args);
                return;
            }
        }
        throw new ReflectiveOperationException("no matching register(...) on " + registry.getClass());
    }

    /** The Mojang-mapped {@code ResourceKey<Enchantment>} for {@code mental:cleaving}, or {@code null} on any miss. */
    private static @Nullable Object injectedResourceKey() {
        try {
            Object registryKey = enchantmentResourceKey();
            if (registryKey == null) {
                return null;
            }
            Class<?> resourceLocation = Class.forName("net.minecraft.resources.ResourceLocation");
            Object location = resourceLocation
                    .getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, INJECTED_NAMESPACE, CLEAVING_KEY);
            Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
            return resourceKey
                    .getMethod("create", registryKey.getClass().getSuperclass(), resourceLocation)
                    .invoke(null, registryKey, location);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    /** A best-effort default for a trailing registration-info parameter — a public static constant of its own type, else {@code null}. */
    private static @Nullable Object defaultFor(@NotNull Class<?> type) {
        for (Field field : type.getFields()) {
            if (type.isAssignableFrom(field.getType())) {
                try {
                    return field.get(null);
                } catch (IllegalAccessException ignored) {
                    // try the next constant
                }
            }
        }
        return null;
    }

    private static @Nullable Object invokeNoArg(@NotNull Object target, @NotNull String method)
            throws ReflectiveOperationException {
        Method handle = target.getClass().getMethod(method);
        handle.setAccessible(true);
        return handle.invoke(target);
    }

    /* ------------------------------------------------------------------ */

    /** A handle backed by a resolved/injected Bukkit {@link Enchantment}. */
    private record RegisteredHandle(@NotNull Enchantment enchantment) implements CleavingHandle {
        @Override
        public int levelOf(@Nullable ItemStack stack) {
            if (stack == null) {
                return 0;
            }
            try {
                return stack.getEnchantmentLevel(enchantment);
            } catch (RuntimeException malformed) {
                return 0;
            }
        }
    }
}
