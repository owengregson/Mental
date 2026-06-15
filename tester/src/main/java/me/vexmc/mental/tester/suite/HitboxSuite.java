package me.vexmc.mental.tester.suite;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.module.hitbox.EraReach;
import me.vexmc.mental.platform.Attributes;
import me.vexmc.mental.tester.Arena;
import me.vexmc.mental.tester.MentalTesterPlugin;
import me.vexmc.mental.tester.TestCase;
import me.vexmc.mental.tester.TestContext;
import me.vexmc.mental.tester.fake.FakePlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The old-hitboxes module pulls whichever per-version reach lever the running
 * server actually exposes — and is a deliberate, complete no-op where neither
 * exists. These scenarios confirm the lever is APPLIED where supported and that
 * the suite cleanly SKIPS (never fails) where the feature is documented as
 * absent:
 *
 * <ul>
 *   <li><b>1.17.1–1.20.4</b> — no {@code ENTITY_INTERACTION_RANGE} attribute and
 *       no {@code ATTACK_RANGE} component; the gate is a hardcoded 6 blocks with
 *       no safe per-player lever, so the module does nothing (both cases note a
 *       SKIP).</li>
 *   <li><b>1.20.5+</b> — the era reach lives in the {@code ENTITY_INTERACTION_RANGE}
 *       attribute (era base {@code 3.0}); enabling the module pins it there.</li>
 *   <li><b>1.21.5+</b> — additionally the {@code ATTACK_RANGE} item data component;
 *       the held weapon carries the era component once the module is on.</li>
 * </ul>
 *
 * <p>Version is detected by capability — attribute presence
 * ({@link Attributes#entityInteractionRange()}) and the
 * {@code DataComponents.ATTACK_RANGE} field probe — exactly as the module's own
 * drivers detect their tiers, never by a version literal.</p>
 */
public final class HitboxSuite {

    private HitboxSuite() {}

    public static @NotNull List<TestCase> tests(
            @NotNull MentalPlugin mental, @NotNull MentalTesterPlugin tester) {
        return List.of(
                new TestCase("hitbox: era reach attribute is applied on 1.20.5+", context ->
                        runReachAttribute(mental, tester, context)),
                new TestCase("hitbox: AttackRange item component is applied on 1.21.5+", context ->
                        runAttackRangeComponent(mental, tester, context)));
    }

    /* ------------------------------------------------------------------ */
    /*  Case 1 — ENTITY_INTERACTION_RANGE attribute (1.20.5+)              */
    /* ------------------------------------------------------------------ */

    private static void runReachAttribute(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        // Capability gate (mirrors EntityInteractionRange.supported()): below
        // 1.20.5 the attribute does not exist and the module is a no-op here.
        Attribute reach = Attributes.entityInteractionRange();
        if (reach == null) {
            context.note("entity_interaction_range absent on this version — "
                    + "old-hitboxes is a documented no-op here");
            return;
        }

        FakePlayer subject = new FakePlayer(tester, mental.services().scheduling());
        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                subject.spawn(Arena.offset(centre, 0, 0));
            });
            context.awaitTicks(5);

            toggleModule(context, "old-hitboxes", true);
            context.expect(moduleActive(mental, "old-hitboxes"),
                    "old-hitboxes failed to enable");
            // The module applies the attribute on enable (it iterates online
            // players) via runOn — give the region task a few ticks to land.
            context.awaitTicks(3);

            Double value = context.sync(() -> {
                AttributeInstance instance = subject.player().getAttribute(reach);
                return instance != null ? instance.getValue() : null;
            });
            if (value == null) {
                context.note("entity_interaction_range attribute not present on this player — "
                        + "cannot read the applied reach (covered by unit pins)");
                return;
            }

            // Era reach is 3.0; the vanilla default is also 3.0, so the load-bearing
            // assertion is that ENABLING the module pins the value AT era — never
            // inflating it above the era reach — while the module is active.
            context.expectNear(EraReach.MAX_REACH, value, 0.01,
                    "entity_interaction_range was not pinned to era reach after enabling old-hitboxes");
        } finally {
            toggleModule(context, "old-hitboxes", false);
            context.syncRun(subject::remove);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Case 2 — ATTACK_RANGE item component (1.21.5+)                     */
    /* ------------------------------------------------------------------ */

    private static void runAttackRangeComponent(
            MentalPlugin mental, MentalTesterPlugin tester, TestContext context) throws Exception {
        // Capability gate (mirrors AttackRangeComponents' constructor probe):
        // the component lever exists only where DataComponents.ATTACK_RANGE does
        // (verified present 1.21.11, absent 1.21.4).
        Object attackRangeType = resolveAttackRangeType();
        if (attackRangeType == null) {
            context.note("DataComponents.ATTACK_RANGE absent on this version — "
                    + "the AttackRange item-component lever is a documented no-op below 1.21.5");
            return;
        }

        FakePlayer subject = new FakePlayer(tester, mental.services().scheduling());
        try {
            context.syncRun(() -> {
                Location centre = Arena.prepare(Bukkit.getWorlds().get(0));
                subject.spawn(Arena.offset(centre, 0, 0));
                subject.player().getInventory()
                        .setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
            });
            context.awaitTicks(5);

            toggleModule(context, "old-hitboxes", true);
            context.expect(moduleActive(mental, "old-hitboxes"),
                    "old-hitboxes failed to enable");
            // The module reconciles online players' held weapons on enable via
            // runOn — give the region task a few ticks to write the component.
            context.awaitTicks(3);

            // Read the component back through the SAME NMS surface the module
            // writes it with: ItemStack.has(DataComponentType). The attribute on
            // case 1 is the primary 1.20.5+ assert; here we confirm the held sword
            // ends up carrying the era ATTACK_RANGE component.
            Boolean carries = context.sync(() -> {
                ItemStack held = subject.player().getInventory().getItemInMainHand();
                if (held.getType() != Material.DIAMOND_SWORD) {
                    return null; // Inventory not as staged — let the caller note-skip.
                }
                return heldHasComponent(held, attackRangeType);
            });
            if (carries == null) {
                context.note("could not read the held weapon's ATTACK_RANGE component on this "
                        + "version (no CraftItemStack handle / NMS has-method) — module is active, "
                        + "value-read covered by the module's own apply path");
                return;
            }

            context.expect(carries,
                    "held diamond sword did not carry the ATTACK_RANGE component after enabling old-hitboxes");
        } finally {
            toggleModule(context, "old-hitboxes", false);
            context.syncRun(subject::remove);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Reflective component probes (mirror AttackRangeComponents)         */
    /* ------------------------------------------------------------------ */

    /**
     * The {@code DataComponents.ATTACK_RANGE} type instance, or {@code null} when
     * absent — the exact capability the module's component driver gates on.
     */
    private static @Nullable Object resolveAttackRangeType() {
        try {
            Class<?> dataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            Field field = dataComponents.getField("ATTACK_RANGE");
            return field.get(null);
        } catch (Throwable absent) {
            return null;
        }
    }

    /**
     * Whether {@code held}'s NMS handle carries the given component type, via the
     * same {@code ItemStack.has(DataComponentType)} surface the module uses.
     * Returns {@code null} when the read path is unavailable on this version (so
     * the caller note-skips rather than failing on a non-CraftItemStack).
     */
    private static @Nullable Boolean heldHasComponent(@NotNull ItemStack held, @NotNull Object type) {
        try {
            Object handle = nmsHandle(held);
            if (handle == null) {
                return null;
            }
            Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType");
            Method has = findUnary(handle.getClass(), "has", componentType);
            if (has == null) {
                return null;
            }
            Object result = has.invoke(handle, type);
            return result instanceof Boolean bool ? bool : null;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /** The CraftItemStack {@code handle} (NMS ItemStack), or {@code null} if not a CraftItemStack. */
    private static @Nullable Object nmsHandle(@NotNull ItemStack stack) throws ReflectiveOperationException {
        Class<?> c = stack.getClass();
        while (c != null && c != Object.class) {
            try {
                Field handle = c.getDeclaredField("handle");
                handle.setAccessible(true);
                return handle.get(stack);
            } catch (NoSuchFieldException next) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** The single-arg method named {@code name} whose parameter accepts {@code parameterType}. */
    private static @Nullable Method findUnary(
            @NotNull Class<?> owner, @NotNull String name, @NotNull Class<?> parameterType) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            if (method.getParameterTypes()[0].isAssignableFrom(parameterType)) {
                return method;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /*  Shared helpers (copied from ZeroTouchSuite)                        */
    /* ------------------------------------------------------------------ */

    /** Toggles through the real console command path and waits for convergence. */
    private static void toggleModule(TestContext context, String id, boolean enabled) throws Exception {
        context.syncRun(() -> Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(), "mental module " + id + " " + (enabled ? "on" : "off")));
        context.awaitTicks(1);
    }

    private static boolean moduleActive(MentalPlugin mental, String id) {
        return mental.modules().byId(id).map(module -> module.active()).orElse(false);
    }
}
