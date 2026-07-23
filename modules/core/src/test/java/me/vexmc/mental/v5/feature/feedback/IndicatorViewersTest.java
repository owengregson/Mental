package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The indicator audience rule (2026-07-20): everyone near the victim sees the
 * number EXCEPT the victim, plus the attacker at any distance. The rule decides
 * every case — player-on-mob, mob-on-player, mob-on-mob, environmental — so it is
 * pinned here over plain ids rather than through a live server.
 */
class IndicatorViewersTest {

    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ATTACKER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID BYSTANDER = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void theVictimNeverSeesDamageDoneToThemself() {
        // The owner ruling: your own health bar already tells you, and self-numbers
        // are clutter. True even though the victim is the closest player of all.
        List<UUID> viewers = IndicatorViewers.select(VICTIM, ATTACKER, List.of(VICTIM, BYSTANDER));
        assertFalse(viewers.contains(VICTIM), "the victim must never see their own damage");
        assertTrue(viewers.contains(BYSTANDER), "a bystander in range sees it");
        assertTrue(viewers.contains(ATTACKER), "the attacker sees the damage they dealt");
        assertEquals(2, viewers.size());
    }

    @Test
    void theAttackerSeesTheirHitFromAnyDistance() {
        // A bow sniper is nowhere near the victim, so they never appear in the
        // nearby sweep — but the hit is theirs, so they are added unconditionally.
        List<UUID> viewers = IndicatorViewers.select(VICTIM, ATTACKER, List.of());
        assertEquals(List.of(ATTACKER), viewers);
    }

    @Test
    void anAttackerAlsoInTheNearbySweepIsDrawnOnce() {
        // Melee: the attacker is both the dealer and within the radius. Two spawns
        // for one hit would double-draw the stand on their client.
        List<UUID> viewers = IndicatorViewers.select(VICTIM, ATTACKER, List.of(ATTACKER, BYSTANDER));
        assertEquals(2, viewers.size(), "the attacker must not be added twice");
        assertEquals(ATTACKER, viewers.get(0));
    }

    @Test
    void environmentalDamageStillDrawsForBystanders() {
        // Fall / lava / drowning: no attacker at all. Bystanders still see it; the
        // victim still does not — which is why fall damage shows nothing to the
        // person falling, and something to everyone watching.
        List<UUID> viewers = IndicatorViewers.select(VICTIM, null, List.of(VICTIM, BYSTANDER));
        assertEquals(List.of(BYSTANDER), viewers);
    }

    @Test
    void selfInflictedDamageIsNotShownToTheSelfInflictor() {
        // A player who damages themself is BOTH victim and attacker: the
        // unconditional attacker add must not smuggle the victim back in.
        List<UUID> viewers = IndicatorViewers.select(VICTIM, VICTIM, List.of(BYSTANDER));
        assertEquals(List.of(BYSTANDER), viewers);
    }

    @Test
    void nobodyAroundResolvesEmptySoTheHitIsDroppedBeforePacketWork() {
        // A mob dying in an unwatched farm: no viewers, no stand, no packets.
        assertTrue(IndicatorViewers.select(VICTIM, null, List.of()).isEmpty());
    }
}
