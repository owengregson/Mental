package me.vexmc.mental.module.compensation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Rolling jitter: the IQR-filtered standard deviation of the last fifteen
 * ping samples, in milliseconds — robust to one-off spikes by construction.
 */
final class JitterCalculator {

    private static final int SAMPLE_SIZE = 15;

    private final Deque<Long> pings = new ArrayDeque<>(SAMPLE_SIZE + 1);

    synchronized void addPing(long pingNanos) {
        pings.addLast(pingNanos);
        if (pings.size() > SAMPLE_SIZE) {
            pings.removeFirst();
        }
    }

    synchronized double calculateMillis() {
        if (pings.size() < 2) {
            return 0.0;
        }

        List<Long> sorted = new ArrayList<>(pings);
        Collections.sort(sorted);

        int q1Index = sorted.size() / 4;
        int q3Index = Math.min(sorted.size() - 1, q1Index * 3);
        long q1 = sorted.get(q1Index);
        long q3 = sorted.get(q3Index);
        long iqr = q3 - q1;

        double lowerBound = q1 - 1.5 * iqr;
        double upperBound = q3 + 1.5 * iqr;

        double sum = 0.0;
        int count = 0;
        for (long ping : sorted) {
            if (ping < lowerBound || ping > upperBound) {
                continue;
            }
            sum += ping;
            count++;
        }
        if (count == 0) {
            return 0.0;
        }
        double mean = sum / count;

        double variance = 0.0;
        for (long ping : sorted) {
            if (ping < lowerBound || ping > upperBound) {
                continue;
            }
            double delta = ping - mean;
            variance += delta * delta;
        }
        variance /= count;

        return Math.sqrt(variance) / 1_000_000.0;
    }

    synchronized void clear() {
        pings.clear();
    }
}
