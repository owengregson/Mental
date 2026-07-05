package me.vexmc.mental.tester;

import java.util.ArrayList;
import java.util.List;
import me.vexmc.mental.platform.Scheduling;
import me.vexmc.mental.platform.TaskHandle;
import me.vexmc.mental.tester.suite.BlockingSuite;
import me.vexmc.mental.tester.suite.BootSuite;
import me.vexmc.mental.tester.suite.CommandSuite;
import me.vexmc.mental.tester.suite.ConsumableRulesSuite;
import me.vexmc.mental.tester.suite.ComboSuite;
import me.vexmc.mental.tester.suite.CosmeticSmokeSuite;
import me.vexmc.mental.tester.suite.DamageRulesSuite;
import me.vexmc.mental.tester.suite.EraParitySuite;
import me.vexmc.mental.tester.suite.FishingSuite;
import me.vexmc.mental.tester.suite.FoliaCombatSmoke;
import me.vexmc.mental.tester.suite.HitboxSuite;
import me.vexmc.mental.tester.suite.InventoryRulesSuite;
import me.vexmc.mental.tester.suite.KnockbackSuite;
import me.vexmc.mental.tester.suite.OcmCoexistenceSuite;
import me.vexmc.mental.tester.suite.PotsSuite;
import me.vexmc.mental.tester.suite.ProfileSuite;
import me.vexmc.mental.tester.suite.ProjectileSuite;
import me.vexmc.mental.tester.suite.ReloadSuite;
import me.vexmc.mental.tester.suite.ZeroTouchSuite;
import me.vexmc.mental.v5.MentalPluginV5;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Boots inside a real server next to Mental, waits for the world to settle,
 * runs the suite, writes PASS/FAIL for the Gradle build, and shuts the
 * server down. On Folia the boot suite runs plus the Folia combat smoke — a
 * same-region pair driven entirely on their owning region threads (the
 * Paper-shaped era suites drive cross-region state from one global context,
 * which Folia forbids; see {@code FoliaCombatSmoke} and Task 5.6).
 *
 * <p>4E restores the FULL suite list. On a plain server the active list is
 * {@code Boot, Knockback, Profile, Fishing, Projectile, EraParity, DamageRules,
 * Blocking, ConsumableRules, CosmeticSmoke, Hitbox, InventoryRules, Pots,
 * Command, Reload, ZeroTouch}; with OldCombatMechanics installed it is {@code Boot +
 * OcmCoexistence} (the coexistence suite asserts the ownership split the era
 * suites cannot, since OCM contests ownership).</p>
 */
public final class MentalTesterPlugin extends JavaPlugin {

    private static final long SETTLE_TICKS = 40L;

    @Override
    public void onEnable() {
        // The run task stamps every boot with a fresh freshness nonce; the
        // tester echoes it into the verdict line so a leftover test-results.txt
        // from an earlier boot can never masquerade as this run's answer.
        // Absent (a manual boot) it defaults to "0" — the check tasks then only
        // pass when they too expect "0", which they never do under the gate.
        String nonce = System.getProperty("mental.tester.nonce", "0");

        MentalPluginV5 mental = (MentalPluginV5) getServer().getPluginManager().getPlugin("Mental");
        if (mental == null) {
            getLogger().severe("Mental is not installed — cannot test");
            TestResultWriter.write(this, false, List.of("Mental plugin missing"), nonce);
            getServer().shutdown();
            return;
        }

        // The suite tier for this boot, from the integration harness (-Dmental.tester.suites). "boot" is
        // the legacy-backport classload/boot-safety tier: run BootSuite ONLY, whatever else is detected —
        // feature correctness on those versions is promoted per-phase. Any other value (or absent, a manual
        // boot) keeps the historical behavior, so every modern/Folia/OCM entry is untouched.
        String suites = System.getProperty("mental.tester.suites", "");
        boolean bootOnly = "boot".equals(suites);

        Scheduling scheduling = mental.scheduling();
        TaskHandle[] starter = new TaskHandle[1];
        starter[0] = scheduling.repeatGlobal(SETTLE_TICKS, 72_000L, () -> {
            starter[0].cancel();
            boolean ocmInstalled = getServer().getPluginManager().getPlugin("OldCombatMechanics") != null;
            List<TestCase> suite = new ArrayList<>(BootSuite.tests(mental));
            if (bootOnly) {
                getLogger().info("mental.tester.suites=boot — running the boot suite only "
                        + "(legacy classload/boot-safety tier).");
            } else if (mental.capabilities().folia()) {
                // The Paper-shaped era suites drive cross-region state from one
                // global context, which Folia forbids; the Folia smoke instead
                // drives a same-region pair with every action on its owning region
                // thread and asserts the journal-recorded desk delivery (Task 5.6).
                getLogger().info("Folia detected — running boot + the Folia combat smoke.");
                suite.addAll(FoliaCombatSmoke.tests(mental, this));
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
                suite.addAll(PotsSuite.tests(mental, this));
                suite.addAll(CommandSuite.tests(mental, this));
                suite.addAll(ComboSuite.tests(mental, this));
                suite.addAll(ReloadSuite.tests(mental));
                suite.addAll(ZeroTouchSuite.tests(mental, this));
            }
            new TestHarness(this, scheduling, nonce).run(suite);
        });
    }
}
