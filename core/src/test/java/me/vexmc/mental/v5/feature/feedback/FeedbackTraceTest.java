package me.vexmc.mental.v5.feature.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeedbackTraceTest {

    @Test
    void recordsInOrderAndEvictsPastCapacity() {
        FeedbackTrace trace = new FeedbackTrace();
        UUID a = UUID.randomUUID();
        UUID v = UUID.randomUUID();
        for (int i = 0; i < 130; i++) {
            trace.record(new FeedbackTrace.Entry("hit-feedback", a, v, "SOUNDS", "n=" + i));
        }
        assertEquals(128, trace.entries().size());
        assertEquals("n=2", trace.entries().get(0).detail());
        assertEquals("n=129", trace.entries().get(127).detail());
        trace.clear();
        assertEquals(0, trace.entries().size());
    }
}
