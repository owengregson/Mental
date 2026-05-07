package me.vexmc.strikesync.module.compensation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Rolling jitter calculator. Records the last {@value #SAMPLE_SIZE} ping
 * samples (in nanoseconds) and reports the IQR-filtered standard deviation
 * (in milliseconds), which is robust to one-off spikes.
 *
 * <p>
 * Adapted from KnockbackSync's {@code JitterCalculator}; switched to
 * {@link ArrayDeque} for cheaper poll/offer than {@link java.util.LinkedList}.
 */
final class JitterCalculator {

	private static final int SAMPLE_SIZE = 15;

	private final Deque<Long> pings = new ArrayDeque<>(SAMPLE_SIZE + 1);

	synchronized void addPing(long pingNanos) {
		pings.addLast(pingNanos);
		if (pings.size() > SAMPLE_SIZE)
			pings.removeFirst();
	}

	/** Standard deviation of the IQR-filtered window, in milliseconds. */
	synchronized double calculateMillis() {
		if (pings.size() < 2)
			return 0.0D;

		List<Long> sorted = new ArrayList<>(pings);
		Collections.sort(sorted);

		int q1Index = sorted.size() / 4;
		int q3Index = Math.min(sorted.size() - 1, q1Index * 3);
		long q1 = sorted.get(q1Index);
		long q3 = sorted.get(q3Index);
		long iqr = q3 - q1;

		double lowerBound = q1 - 1.5D * iqr;
		double upperBound = q3 + 1.5D * iqr;

		double sum = 0.0D;
		int count = 0;
		for (long p : sorted) {
			if (p < lowerBound || p > upperBound)
				continue;
			sum += p;
			count++;
		}
		if (count == 0)
			return 0.0D;
		double mean = sum / count;

		double variance = 0.0D;
		for (long p : sorted) {
			if (p < lowerBound || p > upperBound)
				continue;
			double d = p - mean;
			variance += d * d;
		}
		variance /= count;

		// Convert nanos² → millis (after sqrt).
		return Math.sqrt(variance) / 1_000_000.0D;
	}

	synchronized void clear() {
		pings.clear();
	}
}
