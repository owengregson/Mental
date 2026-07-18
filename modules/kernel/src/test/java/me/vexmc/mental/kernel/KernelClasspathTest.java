package me.vexmc.mental.kernel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The kernel realm must not be able to see Bukkit even at test runtime. */
class KernelClasspathTest {
    @Test
    void bukkitIsNotOnTheKernelClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.bukkit.Bukkit"));
    }
}
