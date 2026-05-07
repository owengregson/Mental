package me.vexmc.strikesync.core;

import me.vexmc.strikesync.util.Logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the module list and orchestrates lifecycle calls with strict exception
 * isolation: a misbehaving module never prevents its siblings from starting,
 * stopping, or reloading.
 */
public final class ModuleManager {

	private final Logging log;
	private final List<Module> modules = new ArrayList<>();
	private final List<Module> enabled = new ArrayList<>();

	public ModuleManager(Logging log) {
		this.log = log;
	}

	public void register(Module module) {
		modules.add(module);
	}

	/** Bring every registered module up. */
	public void enableAll() {
		for (Module module : modules) {
			try {
				module.enable();
				enabled.add(module);
				log.debug(() -> "module enabled: " + module.id());
			} catch (Throwable t) {
				log.error("Failed to enable module '" + module.id() + "'", t);
			}
		}
	}

	/** Tear every successfully-enabled module down, in reverse order. */
	public void disableAll() {
		List<Module> reverse = new ArrayList<>(enabled);
		Collections.reverse(reverse);
		enabled.clear();
		for (Module module : reverse) {
			try {
				module.disable();
				log.debug(() -> "module disabled: " + module.id());
			} catch (Throwable t) {
				log.error("Failed to disable module '" + module.id() + "'", t);
			}
		}
	}

	/** Apply a fresh config snapshot to every enabled module. */
	public void reloadAll() {
		for (Module module : enabled) {
			try {
				module.reload();
				log.debug(() -> "module reloaded: " + module.id());
			} catch (Throwable t) {
				log.error("Failed to reload module '" + module.id() + "'", t);
			}
		}
	}
}
