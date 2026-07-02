package me.vexmc.mental.tester;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.tester.suite.BlockingSuite;
import me.vexmc.mental.tester.suite.BootSuite;
import me.vexmc.mental.tester.suite.DamageRulesSuite;
import me.vexmc.mental.tester.suite.EraParitySuite;
import me.vexmc.mental.tester.suite.FishingSuite;
import me.vexmc.mental.tester.suite.KnockbackSuite;
import me.vexmc.mental.tester.suite.ProfileSuite;
import me.vexmc.mental.tester.suite.ProjectileSuite;
import me.vexmc.mental.tester.suite.ReloadSuite;
import me.vexmc.mental.tester.suite.ZeroTouchSuite;
import me.vexmc.mental.v5.MentalPluginV5;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Boots inside a real server next to Mental, waits for the world to settle,
 * runs the suite, writes PASS/FAIL for the Gradle build, and shuts the
 * server down. On Folia only the boot suite runs — gameplay suites drive
 * cross-region state from a single context by design.
 *
 * <p>At the 4A2 swap the suite list was trimmed to the families the v5 plugin
 * supported; 4B restores the damage family: the active list is now {@code Boot,
 * Knockback, Profile, Fishing, Projectile, EraParity, DamageRules, Blocking,
 * Reload, ZeroTouch}. The cadence/sustain/loadout suites (and the OCM
 * coexistence suite) remain delisted here and are restored across 4C–4E.</p>
 */
public final class MentalTesterPlugin extends JavaPlugin {

    private static final long SETTLE_TICKS = 40L;

    @Override
    public void onEnable() {
        MentalPluginV5 mental = (MentalPluginV5) getServer().getPluginManager().getPlugin("Mental");
        if (mental == null) {
            getLogger().severe("Mental is not installed — cannot test");
            TestResultWriter.write(this, false, List.of("Mental plugin missing"));
            getServer().shutdown();
            return;
        }

        Scheduling scheduling = mental.scheduling();
        TaskHandle[] starter = new TaskHandle[1];
        starter[0] = scheduling.repeatGlobal(SETTLE_TICKS, 72_000L, () -> {
            starter[0].cancel();
            boolean ocmInstalled = getServer().getPluginManager().getPlugin("OldCombatMechanics") != null;
            List<TestCase> suite = new ArrayList<>(BootSuite.tests(mental));
            if (mental.capabilities().folia()) {
                getLogger().info("Folia detected — running the boot suite only.");
            } else if (ocmInstalled) {
                // The coexistence suite is delisted for this sub-phase (it
                // returns in 4E); with OCM present the era suites assert
                // Mental's ownership, which OCM contests, so run boot only.
                getLogger().info("OldCombatMechanics detected — running the boot suite only "
                        + "(the coexistence suite returns in 4E).");
            } else {
                suite.addAll(KnockbackSuite.tests(mental, this));
                suite.addAll(ProfileSuite.tests(mental, this));
                suite.addAll(FishingSuite.tests(mental, this));
                suite.addAll(ProjectileSuite.tests(mental, this));
                suite.addAll(EraParitySuite.tests(mental, this));
                suite.addAll(DamageRulesSuite.tests(mental, this));
                suite.addAll(BlockingSuite.tests(mental, this));
                suite.addAll(ReloadSuite.tests(mental));
                suite.addAll(ZeroTouchSuite.tests(mental, this));
            }
            new TestHarness(this, scheduling).run(suite);
        });
    }
}
