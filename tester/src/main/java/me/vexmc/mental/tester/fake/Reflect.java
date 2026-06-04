package me.vexmc.mental.tester.fake;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Minimal reflection helpers for the fake-player NMS bootstrap. */
final class Reflect {

    private Reflect() {}

    static @Nullable Method method(@NotNull Class<?> owner, @NotNull String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException absent) {
            try {
                Method method = owner.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException alsoAbsent) {
                return null;
            }
        }
    }

    /** First method with this name whose parameters accept the given argument types. */
    static @Nullable Method methodAssignable(@NotNull Class<?> owner, @NotNull String name, Class<?>... argumentTypes) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != argumentTypes.length) {
                continue;
            }
            if (parametersAccept(method.getParameterTypes(), argumentTypes)) {
                method.setAccessible(true);
                return method;
            }
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != argumentTypes.length) {
                continue;
            }
            if (parametersAccept(method.getParameterTypes(), argumentTypes)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    static @Nullable Field field(@NotNull Class<?> owner, @NotNull String name) {
        Class<?> current = owner;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    static @NotNull Object enumConstant(@NotNull Class<?> owner, @NotNull String... candidateNames)
            throws ReflectiveOperationException {
        for (String name : candidateNames) {
            Field field = field(owner, name);
            if (field != null) {
                return field.get(null);
            }
        }
        throw new NoSuchFieldException("None of " + String.join(", ", candidateNames) + " on " + owner.getName());
    }

    private static boolean parametersAccept(Class<?>[] parameters, Class<?>[] arguments) {
        for (int i = 0; i < parameters.length; i++) {
            if (arguments[i] != null && !parameters[i].isAssignableFrom(arguments[i])) {
                return false;
            }
        }
        return true;
    }
}
