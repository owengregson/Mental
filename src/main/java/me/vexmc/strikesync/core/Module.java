package me.vexmc.strikesync.core;

/**
 * A self-contained subsystem with a clean lifecycle.
 *
 * <p>
 * Modules are owned by {@link ModuleManager}. They must tolerate
 * {@link #disable()}
 * being called even if {@link #enable()} threw, and {@link #reload()} being
 * called
 * any number of times after enable.
 */
public interface Module {

	/** Stable, lowercase identifier used in logs and command output. */
	String id();

	/**
	 * Bring the module up. Called once per plugin enable cycle, after the
	 * configuration snapshot has been built.
	 */
	void enable();

	/**
	 * Tear the module down. Called on plugin disable, on reload before
	 * {@link #enable()} runs again, and after a failed enable.
	 */
	void disable();

	/**
	 * Apply a fresh configuration snapshot without re-creating the module.
	 *
	 * <p>
	 * Default reload is bounce: disable then enable. Modules may override for a
	 * cheaper hot-reload (e.g. swapping immutable settings).
	 */
	default void reload() {
		disable();
		enable();
	}
}
