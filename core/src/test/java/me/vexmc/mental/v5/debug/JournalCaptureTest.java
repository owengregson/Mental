package me.vexmc.mental.v5.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import me.vexmc.mental.kernel.model.HitContext;
import me.vexmc.mental.kernel.model.HitGeometry;
import me.vexmc.mental.kernel.model.HitId;
import me.vexmc.mental.kernel.model.HitSource;
import me.vexmc.mental.kernel.model.JournalEntry;
import me.vexmc.mental.kernel.model.KnockbackVector;
import me.vexmc.mental.kernel.model.SprintVerdict;
import me.vexmc.mental.kernel.model.TickStamp;
import me.vexmc.mental.v5.config.ConfigStore;
import me.vexmc.mental.v5.config.Snapshot;
import me.vexmc.mental.v5.config.SnapshotParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact greppable line the F9 {@link JournalCapture} emits per journaled
 * hit, through the pure package-private {@code format} (families injected, so no
 * live plugin is needed), plus the enabled-families joiner over a real snapshot.
 */
class JournalCaptureTest {

    private static final UUID ATTACKER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000");
    private static final UUID VICTIM = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000000");

    private static HitContext context(long id, HitSource source, UUID attacker, UUID victim,
                                      SprintVerdict sprint, TickStamp registeredAt) {
        return new HitContext(new HitId(id), source, attacker, victim, sprint, true, null, registeredAt);
    }

    @Test
    void formatsAFullCaptureLine() {
        HitContext context = context(77, new HitSource.Melee(), ATTACKER, VICTIM,
                new SprintVerdict(true, Boolean.TRUE, new TickStamp(5)), new TickStamp(5));
        HitGeometry geometry = new HitGeometry(1.5, -2.25, 90f, 3.0, 4.5);
        JournalEntry.Capture capture = new JournalEntry.Capture(
                true, Boolean.TRUE, "paced-out", "ship-formula", geometry, "legacy-1.7");
        // shipped (0.3, 0.36, 0.4): h = hypot(0.3, 0.4) = sqrt(0.09 + 0.16) = sqrt(0.25) = 0.5.
        JournalEntry entry = new JournalEntry(new HitId(77), new HitSource.Melee(),
                new KnockbackVector(0.3, 0.36, 0.4), false, null, new TickStamp(9), 1.0, 1.0, capture);

        String line = JournalCapture.format(context, entry, "HIT_REGISTRATION,KNOCKBACK");
        assertEquals(
                "hit=77 src=melee out=ship-formula presend=paced-out reason=- ship=h=0.500 v=0.360"
                        + " wire=f sprint=t fresh=t pace=1.00 combo=1.00"
                        + " geom=a(1.50,-2.25 yaw 90.0) v(3.00,4.50) profile=legacy-1.7"
                        + " families=HIT_REGISTRATION,KNOCKBACK attacker=aaaaaaaa victim=bbbbbbbb tick=9",
                line);
    }

    @Test
    void formatsAnAllAbsentLine() {
        HitContext context = context(5, new HitSource.Melee(), null, null,
                new SprintVerdict(false, null, TickStamp.NO_TICK), TickStamp.NO_TICK);
        JournalEntry entry = new JournalEntry(new HitId(5), new HitSource.Melee(),
                null, false, null, TickStamp.NO_TICK, 1.0, 1.0, null);

        String line = JournalCapture.format(context, entry, "-");
        assertEquals(
                "hit=5 src=melee out=- presend=- reason=- ship=- wire=f sprint=- fresh=- pace=1.00"
                        + " combo=1.00 geom=- profile=- families=- attacker=- victim=- tick=-",
                line);
    }

    @Test
    void rendersVanillaSourceAndPresendNone() {
        HitContext context = context(3, new HitSource.Vanilla("ENTITY_ATTACK"), ATTACKER, VICTIM,
                new SprintVerdict(false, null, new TickStamp(0)), new TickStamp(0));
        // A region-path Vanilla melee: capture present but presend null (→ "none") and geometry null.
        JournalEntry.Capture capture = new JournalEntry.Capture(
                false, null, null, "ship-formula", null, "legacy-1.7");
        // shipped (0.0, 0.3608, 0.4): h = hypot(0.0, 0.4) = 0.4; v = 0.3608 → 0.361.
        JournalEntry entry = new JournalEntry(new HitId(3), new HitSource.Vanilla("ENTITY_ATTACK"),
                new KnockbackVector(0.0, 0.3608, 0.4), false, null, new TickStamp(2), 1.0, 1.0, capture);

        String line = JournalCapture.format(context, entry, "-");
        assertEquals(
                "hit=3 src=vanilla(ENTITY_ATTACK) out=ship-formula presend=none reason=-"
                        + " ship=h=0.400 v=0.361 wire=f sprint=f fresh=- pace=1.00 combo=1.00"
                        + " geom=- profile=legacy-1.7 families=- attacker=aaaaaaaa victim=bbbbbbbb tick=2",
                line);
    }

    @Test
    void rendersVanillaSourceWithSprintingAttacker() {
        // S4 journal honesty: a Vanilla(ENTITY_ATTACK) hit whose attacker was live-
        // sprinting mints a sprinting verdict; fresh stays null (no wire view). The
        // journal must read sprint=t fresh=- — not the pre-S4 sprint=f that a hardcoded
        // SprintVerdict(false) printed for a hit that shipped sprint-scale.
        HitContext context = context(4, new HitSource.Vanilla("ENTITY_ATTACK"), ATTACKER, VICTIM,
                new SprintVerdict(true, null, new TickStamp(0)), new TickStamp(0));
        JournalEntry.Capture capture = new JournalEntry.Capture(
                true, null, null, "ship-formula", null, "legacy-1.7");
        // shipped (0.3, 0.36, 0.4): h = hypot(0.3, 0.4) = 0.5; v = 0.36 → 0.360.
        JournalEntry entry = new JournalEntry(new HitId(4), new HitSource.Vanilla("ENTITY_ATTACK"),
                new KnockbackVector(0.3, 0.36, 0.4), false, null, new TickStamp(2), 1.0, 1.0, capture);

        String line = JournalCapture.format(context, entry, "-");
        assertEquals(
                "hit=4 src=vanilla(ENTITY_ATTACK) out=ship-formula presend=none reason=-"
                        + " ship=h=0.500 v=0.360 wire=f sprint=t fresh=- pace=1.00 combo=1.00"
                        + " geom=- profile=legacy-1.7 families=- attacker=aaaaaaaa victim=bbbbbbbb tick=2",
                line);
    }

    @Test
    void familiesOfListsTheEnabledFeaturesAndSkipsTheDisabled() {
        YamlConfiguration empty = new YamlConfiguration();
        Snapshot snapshot = SnapshotParser.parse(
                ConfigStore.Sources.of(empty, empty, empty, empty, Map.of())).snapshot();
        String families = JournalCapture.familiesOf(snapshot);
        assertTrue(families.contains("KNOCKBACK"), "a default-ON engine feature is listed");
        assertTrue(families.contains("HIT_REGISTRATION"), "a default-ON delivery feature is listed");
        assertFalse(families.contains("HITBOX"), "a default-OFF rule feature is not listed");
    }
}
