package me.vexmc.mental.tester;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.MentalPlugin;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.tester.suite.BootSuite;
import me.vexmc.mental.tester.suite.CommandSuite;
import me.vexmc.mental.tester.suite.FishingSuite;
import me.vexmc.mental.tester.suite.KnockbackSuite;
import me.vexmc.mental.tester.suite.ProjectileSuite;
import me.vexmc.mental.tester.suite.ReloadSuite;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Boots inside a real server next to Mental, waits for the world to settle,
 * runs the suite, writes PASS/FAIL for the Gradle build, and shuts the
 * server down. On Folia only the boot suite runs — gameplay suites drive
 * cross-region state from a single context by design.
 */
public final class MentalTesterPlugin extends JavaPlugin {

    private static final long SETTLE_TICKS = 100L;

    @Override
    public void onEnable() {
        MentalPlugin mental = (MentalPlugin) getServer().getPluginManager().getPlugin("Mental");
        if (mental == null) {
            getLogger().severe("Mental is not installed — cannot test");
            TestResultWriter.write(this, false, List.of("Mental plugin missing"));
            getServer().shutdown();
            return;
        }

        Scheduling scheduling = mental.services().scheduling();
        TaskHandle[] starter = new TaskHandle[1];
        starter[0] = scheduling.repeatGlobal(SETTLE_TICKS, 72_000L, () -> {
            starter[0].cancel();
            List<TestCase> suite = new ArrayList<>(BootSuite.tests(mental));
            if (!mental.services().capabilities().folia()) {
                suite.addAll(KnockbackSuite.tests(mental, this));
                suite.addAll(ProjectileSuite.tests(mental, this));
                suite.addAll(FishingSuite.tests(mental, this));
                suite.addAll(CommandSuite.tests(mental, this));
                suite.addAll(ReloadSuite.tests(mental));
            } else {
                getLogger().info("Folia detected — running the boot suite only.");
            }
            new TestHarness(this, scheduling).run(suite);
        });
    }
}
