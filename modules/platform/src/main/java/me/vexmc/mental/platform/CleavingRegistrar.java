package me.vexmc.mental.platform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
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
 * <p><b>Provenance note (1.21.4 + 26.1.2 javap-pinned, 2026-07-18).</b> The v2.9.0
 * seam never landed the write <em>anywhere</em>: every reflective probe derived its
 * parameter type from {@code enchantmentKey.getClass().getSuperclass()} — and
 * {@code net.minecraft.resources.ResourceKey} extends {@code Object}, so the probes
 * searched for {@code lookupOrThrow(Object)} / {@code create(Object, …)}, which do
 * not exist on any version. The chain below is now pinned member-by-member against
 * the live 1.21.4 AND 26.1.2 jars:
 * <ul>
 *   <li>{@code RegistryAccess#lookupOrThrow(ResourceKey)} / the older
 *       {@code registryOrThrow} / the {@code Optional}-returning {@code lookup} —
 *       all take the declared {@code ResourceKey} class, resolved via
 *       {@code Class.forName} and invoked off the public interface (the concrete
 *       {@code Frozen} impl class need not be accessible).</li>
 *   <li>{@code MappedRegistry#register(ResourceKey, T, RegistrationInfo)} THROWS on
 *       a duplicate <em>value</em> ({@code byValue} is identity-keyed), so the
 *       template enchant is never re-registered as-is: the write registers a fresh
 *       canonical-constructor copy ({@code Enchantment} is a record on 1.21+; its
 *       {@code description/definition/exclusiveSet/effects} accessors and public
 *       canonical constructor are byte-identical on 1.21.4 and 26.1.2). Below 1.21
 *       {@code Enchantment} is a plain class, the copy probe misses, and the seam
 *       degrades before touching the registry — that IS the floor gate.</li>
 *   <li>{@code register} does NOT bind the new holder's tags, and
 *       {@code Holder.Reference#is(TagKey)} throws {@code "Tags not bound"} until
 *       {@code bindTags} runs — so the write binds the fresh holder to the empty
 *       tag set, keeping later anvil/grindstone tag checks safe.</li>
 *   <li>On the year scheme {@code ResourceLocation} is renamed
 *       {@code net.minecraft.resources.Identifier}; both spellings carry the same
 *       {@code fromNamespaceAndPath(String, String)} factory and
 *       {@code ResourceKey#create(ResourceKey, <location>)}, so the key builder
 *       tries both class names and the write lands on 26.x too.</li>
 * </ul></p>
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

    /** A non-fatal note from the post-register tag bind — folded into {@link #state} by {@link #install}. */
    private static volatile @NotNull String tagBindNote = "";

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
                state = "injected " + injected.getKey() + tagBindNote;
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
     * The registry write: register a fresh copy of a vanilla enchant's value under
     * the injected key (the intrinsic effect it carries is discarded downstream —
     * {@code ct8c-damage} overwrites the BASE with its own composition, which
     * reads only the Cleaving LEVEL and applies {@code 1+level}). A COPY, never
     * the template itself: {@code MappedRegistry.register} throws on a duplicate
     * value and its {@code byValue}/{@code toId} maps are identity-keyed, so
     * re-registering the live instance would either throw or corrupt the
     * template's own reverse lookups. Returns the new Bukkit {@link Enchantment},
     * or {@code null} where the modern registry shape does not resolve — every
     * probe (copy included) runs BEFORE the unfreeze, so a degrade never touches
     * the registry. The registry is refrozen in a {@code finally} so a mid-write
     * throw never leaves it open, and the fresh holder is bound to the empty tag
     * set so later {@code Holder.Reference.is(TagKey)} checks cannot throw.
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
        Object copy = copyTemplate(template);
        if (copy == null) {
            return null; // pre-record Enchantment (below 1.21) or a drifted shape — degrade
        }
        Object resourceKey = injectedResourceKey();
        if (resourceKey == null) {
            return null; // neither ResourceLocation nor Identifier resolved — degrade
        }
        boolean wasFrozen = frozen.getBoolean(registry);
        Object holder;
        try {
            frozen.setBoolean(registry, false);
            holder = registerValue(registry, resourceKey, copy);
        } finally {
            frozen.setBoolean(registry, wasFrozen); // refreeze to the prior state, always
        }
        bindEmptyTags(holder);
        // The Bukkit view of the freshly registered key (CraftRegistry resolves
        // lazily off the live NMS registry, so the post-boot write is visible).
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
        Class<?> resourceKeyClass = classOrNull("net.minecraft.resources.ResourceKey");
        if (enchantmentRegistryKey == null || resourceKeyClass == null
                || !resourceKeyClass.isInstance(enchantmentRegistryKey)) {
            return null;
        }
        // The lookups take the DECLARED ResourceKey class (the key's runtime class
        // extends Object, so getSuperclass() would probe lookupOrThrow(Object) — a
        // method that exists nowhere; the v2.9.0 miss). Resolve off the public
        // RegistryAccess interface so the concrete Frozen impl's visibility never
        // matters: lookupOrThrow (1.21.2+), registryOrThrow (older), then the
        // Optional-returning lookup.
        Class<?> accessInterface = classOrNull("net.minecraft.core.RegistryAccess");
        if (accessInterface == null || !accessInterface.isInstance(registryAccess)) {
            return null;
        }
        for (String method : new String[] {"lookupOrThrow", "registryOrThrow"}) {
            try {
                Method lookup = accessInterface.getMethod(method, resourceKeyClass);
                Object registry = lookup.invoke(registryAccess, enchantmentRegistryKey);
                if (registry != null) {
                    return registry;
                }
            } catch (ReflectiveOperationException | RuntimeException tryNext) {
                // fall through to the next spelling / the Optional-returning form
            }
        }
        try {
            Method lookup = accessInterface.getMethod("lookup", resourceKeyClass);
            Object optional = lookup.invoke(registryAccess, enchantmentRegistryKey);
            if (optional instanceof Optional<?> present && present.isPresent()) {
                return present.get();
            }
        } catch (ReflectiveOperationException | RuntimeException absent) {
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
     * Registers {@code value} under {@code resourceKey} via the registry's
     * {@code register(ResourceKey, T, RegistrationInfo)} — the modern three-arg
     * form — falling back to shorter spellings. Returns the {@code Holder.Reference}
     * the registry hands back (the caller binds its tags).
     */
    private static @NotNull Object registerValue(@NotNull Object registry, @NotNull Object resourceKey,
            @NotNull Object value) throws ReflectiveOperationException {
        for (Method method : registry.getClass().getMethods()) {
            if (!method.getName().equals("register")) {
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
                Object holder = method.invoke(registry, args);
                if (holder == null) {
                    throw new ReflectiveOperationException("register(...) returned no holder on " + registry.getClass());
                }
                return holder;
            }
        }
        throw new ReflectiveOperationException("no matching register(...) on " + registry.getClass());
    }

    /**
     * The Mojang-mapped {@code ResourceKey<Enchantment>} for {@code mental:cleaving},
     * or {@code null} on any miss. The location class is {@code ResourceLocation}
     * through 1.21.x and {@code Identifier} on the year scheme (26.x) — both carry
     * the same {@code fromNamespaceAndPath} factory and {@code ResourceKey.create}
     * overload (javap-pinned on 1.21.4 and 26.1.2), so both spellings are tried.
     */
    private static @Nullable Object injectedResourceKey() {
        Object registryKey = enchantmentResourceKey();
        Class<?> resourceKeyClass = classOrNull("net.minecraft.resources.ResourceKey");
        if (registryKey == null || resourceKeyClass == null) {
            return null;
        }
        for (String locationName : new String[] {
                "net.minecraft.resources.ResourceLocation", "net.minecraft.resources.Identifier"}) {
            try {
                Class<?> locationClass = Class.forName(locationName);
                Object location = locationClass
                        .getMethod("fromNamespaceAndPath", String.class, String.class)
                        .invoke(null, INJECTED_NAMESPACE, CLEAVING_KEY);
                return resourceKeyClass
                        .getMethod("create", resourceKeyClass, locationClass)
                        .invoke(null, registryKey, location);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError tryNext) {
                // next location spelling
            }
        }
        return null;
    }

    /**
     * A fresh {@code Enchantment} carrying the template's exact components, built
     * through the record's public canonical constructor off its
     * {@code description/definition/exclusiveSet/effects} accessors (byte-identical
     * on 1.21.4 and 26.1.2 — the record shape IS the modern floor gate: below 1.21
     * {@code Enchantment} is a plain class, the accessor probe misses, and the seam
     * degrades before the unfreeze). Plain Java-8 reflection on purpose — the
     * {@code RecordComponent} API is unavailable to the class-v52 base tree.
     */
    static @Nullable Object copyTemplate(@NotNull Object template) { // package-private: unit-pinned
        try {
            Class<?> type = template.getClass();
            String[] accessors = {"description", "definition", "exclusiveSet", "effects"};
            Class<?>[] paramTypes = new Class<?>[accessors.length];
            Object[] args = new Object[accessors.length];
            for (int i = 0; i < accessors.length; i++) {
                Method accessor = type.getMethod(accessors[i]);
                paramTypes[i] = accessor.getReturnType();
                args[i] = accessor.invoke(template);
            }
            return type.getConstructor(paramTypes).newInstance(args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    /**
     * Binds the freshly registered holder to the empty tag set. {@code register}
     * leaves the new {@code Holder.Reference}'s tags UNBOUND, and
     * {@code Holder.Reference.is(TagKey)} throws {@code "Tags not bound"} until a
     * bind runs (javap-pinned) — a later anvil/grindstone tag check on a Cleaving
     * item would blow up. Best-effort: the package-private {@code bindTags} is
     * opened reflectively; a miss is logged into the state, never thrown.
     */
    private static void bindEmptyTags(@NotNull Object holder) {
        try {
            for (Class<?> cursor = holder.getClass(); cursor != null && cursor != Object.class;
                    cursor = cursor.getSuperclass()) {
                for (Method method : cursor.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (method.getName().equals("bindTags") && params.length == 1
                            && params[0].isAssignableFrom(List.class)) {
                        method.setAccessible(true);
                        method.invoke(holder, List.of());
                        tagBindNote = "";
                        return;
                    }
                }
            }
            tagBindNote = " (holder tags left unbound — no bindTags on " + holder.getClass().getName() + ")";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError miss) {
            tagBindNote = " (holder tags left unbound: " + miss + ")";
        }
    }

    /** {@link Class#forName(String)} that resolves absence to {@code null} instead of a throw. */
    private static @Nullable Class<?> classOrNull(@NotNull String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError absent) {
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
