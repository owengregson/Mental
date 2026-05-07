package me.vexmc.strikesync.util;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin level-gated wrapper around {@link java.util.logging.Logger}.
 *
 * <p>
 * Lazy message suppliers avoid string concatenation when a level is disabled,
 * keeping the hot hit-registration path allocation-free when debug is off.
 */
public final class Logging {

	private final Logger logger;
	private volatile boolean debug;

	public Logging(Logger logger) {
		this.logger = logger;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	public boolean isDebugEnabled() {
		return debug;
	}

	public void info(String message) {
		logger.info(message);
	}

	public void warn(String message) {
		logger.warning(message);
	}

	public void warn(String message, Throwable t) {
		logger.log(Level.WARNING, message, t);
	}

	public void error(String message, Throwable t) {
		logger.log(Level.SEVERE, message, t);
	}

	public void debug(Supplier<String> message) {
		if (!debug)
			return;
		logger.info("[debug] " + message.get());
	}

	public void debug(String message) {
		if (!debug)
			return;
		logger.info("[debug] " + message);
	}
}
