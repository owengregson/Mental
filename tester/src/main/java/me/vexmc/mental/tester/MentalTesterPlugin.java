package me.vexmc.mental.tester;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.common.scheduling.Scheduling;
import me.vexmc.mental.common.scheduling.TaskHandle;
import me.vexmc.mental.tester.suite.BlockingSuite;
import me.vexmc.mental.tester.suite.BootSuite;
import me.vexmc.mental.tester.suite.CommandSuite;
import me.vexmc.mental.tester.suite.ConsumableRulesSuite;
import me.vexmc.mental.tester.suite.CosmeticSmokeSuite;
import me.vexmc.mental.tester.suite.DamageRulesSuite;
import me.vexmc.mental.tester.suite.EraParitySuite;
import me.vexmc.mental.tester.suite.FishingSuite;
import me.vexmc.mental.tester.suite.HitboxSuite;
import me.vexmc.mental.tester.suite.InventoryRulesSuite;
import me.vexmc.mental.tester.suite.KnockbackSuite;
import me.vexmc.mental.tester.suite.OcmCoexistenceSuite;
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
 * <p>4E restores the FULL suite list. On a plain server the active list is
 * {@code Boot, Knockback, Profile, Fishing, Projectile, EraParity, DamageRules,
 * Blocking, ConsumableRules, CosmeticSmoke, Hitbox, InventoryRules, Command,
 * Reload, ZeroTouch}; with OldCombatMechanics installed it is {@code Boot +
 * OcmCoexistence} (the coexistence suite asserts the ownership split the era
 * suites cannot, since OCM contests ownership).</p>
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
                // With OCM present the era suites assert Mental's ownership,
                // which OCM contests; the coexistence suite (restored in 4E)
                // instead asserts the ownership split through the live binding.
                getLogger().info("OldCombatMechanics detected — running boot + the coexistence suite.");
                suite.addAll(OcmCoexistenceSuite.tests(mental, this));
            } else {
                suite.addAll(KnockbackSuite.tests(mental, this));
                suite.addAll(ProfileSuite.tests(mental, this));
                suite.addAll(FishingSuite.tests(mental, this));
                suite.addAll(ProjectileSuite.tests(mental, this));
                suite.addAll(EraParitySuite.tests(mental, this));
                suite.addAll(DamageRulesSuite.tests(mental, this));
                suite.addAll(BlockingSuite.tests(mental, this));
                suite.addAll(ConsumableRulesSuite.tests(mental, this));
                suite.addAll(CosmeticSmokeSuite.tests(mental, this));
                suite.addAll(HitboxSuite.tests(mental, this));
                suite.addAll(InventoryRulesSuite.tests(mental, this));
                suite.addAll(CommandSuite.tests(mental, this));
                suite.addAll(ReloadSuite.tests(mental));
                suite.addAll(ZeroTouchSuite.tests(mental, this));
            }
            new TestHarness(this, scheduling).run(suite);
        });
    }
}
