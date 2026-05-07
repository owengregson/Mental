package me.vexmc.strikesync.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;

import static me.vexmc.strikesync.message.Brand.*;

/**
 * All user-facing messages live here so copy can be tweaked in one place
 * and so command nodes stay focused on logic, not formatting.
 */
public final class Messages {

	private Messages() {
	}

	public static Component noPermission() {
		return failure("You do not have permission for that.");
	}

	public static Component usage(String usage) {
		return Component.text()
				.append(prefix())
				.append(Component.text(" Usage: ", MUTED))
				.append(Component.text(usage, ACCENT))
				.build();
	}

	public static Component unknownSubcommand() {
		return Component.text()
				.append(prefix())
				.append(Component.text(" Unknown subcommand. Try ", MUTED))
				.append(Component.text("/ss help", ACCENT))
				.build();
	}

	public static Component reloadStarted() {
		return line(Component.text("Reloading…", MUTED));
	}

	public static Component reloadSucceeded(long millis) {
		return Component.text()
				.append(prefix())
				.append(Component.text(" Reloaded in ", TEXT))
				.append(Component.text(millis + "ms", ACCENT))
				.append(Component.text(".", TEXT))
				.build();
	}

	public static Component reloadFailed(String reason) {
		return failure("Reload failed: " + reason);
	}

	public static Component hitRegEnabled() {
		return success("Async hit registration enabled.");
	}

	public static Component hitRegDisabled() {
		return failure("Async hit registration disabled.");
	}

	public static Component knockbackEnabled() {
		return success("Knockback module enabled.");
	}

	public static Component knockbackDisabled() {
		return failure("Knockback module disabled.");
	}

	public static Component knockbackStatus(boolean enabled, boolean async, double cps) {
		return Component.text()
				.append(prefix())
				.append(Component.text(" Knockback: ", TEXT))
				.append(Component.text(enabled ? "ENABLED" : "DISABLED", enabled ? SUCCESS : FAILURE,
						TextDecoration.BOLD))
				.append(Component.text("  async=", MUTED))
				.append(Component.text(String.valueOf(async), ACCENT))
				.append(Component.text("  hitreg-cps-cap=", MUTED))
				.append(Component.text(cps == 0 ? "off" : String.valueOf((int) cps), ACCENT))
				.build();
	}

	public static Component authors() {
		Component author = Component.text("@owengregson", SECONDARY)
				.decorate(TextDecoration.UNDERLINED)
				.clickEvent(ClickEvent.openUrl("https://github.com/owengregson"));
		return Component.text()
				.append(prefix())
				.append(Component.text(" by ", TEXT))
				.append(author)
				.append(Component.text(" — ", MUTED))
				.append(Component.text("github.com/owengregson/StrikeSync", MUTED)
						.clickEvent(ClickEvent.openUrl("https://github.com/owengregson/StrikeSync")))
				.build();
	}

	public static Component help() {
		Component header = Component.text()
				.append(prefix())
				.append(Component.text(" — Asynchronous PvP toolkit", MUTED))
				.build();
		return Component.text()
				.append(header).append(Component.newline())
				.append(helpRow("/ss help", "Show this help"))
				.append(Component.newline())
				.append(helpRow("/ss authors", "Plugin authors"))
				.append(Component.newline())
				.append(helpRow("/ss reload", "Reload config & modules"))
				.append(Component.newline())
				.append(helpRow("/ss toggle", "Toggle async hit registration"))
				.append(Component.newline())
				.append(helpRow("/ss knockback <enable|disable|status>",
						"Manage 1.8-style knockback module"))
				.append(Component.newline())
				.append(helpRow("/ss compensation <enable|disable|status>",
						"Manage latency compensation module"))
				.append(Component.newline())
				.append(helpRow("/ss ping [player]",
						"Show measured RTT, jitter, and spike state"))
				.build();
	}

	private static Component helpRow(String command, String description) {
		return Component.text()
				.append(Component.text("  "))
				.append(Component.text(command, SECONDARY)
						.clickEvent(ClickEvent.suggestCommand(command.split(" ", 2)[0])))
				.append(Component.text(" — ", MUTED))
				.append(Component.text(description, TEXT))
				.build();
	}
}
