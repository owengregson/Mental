package me.vexmc.strikesync.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Centralised StrikeSync visual brand.
 *
 * <p>
 * Every user-facing message starts with the same prefix so that operators can
 * easily attribute output to this plugin in chat logs.
 */
public final class Brand {

	public static final NamedTextColor PRIMARY = NamedTextColor.GOLD;
	public static final NamedTextColor SECONDARY = NamedTextColor.YELLOW;
	public static final NamedTextColor TEXT = NamedTextColor.WHITE;
	public static final NamedTextColor MUTED = NamedTextColor.GRAY;
	public static final NamedTextColor SUCCESS = NamedTextColor.GREEN;
	public static final NamedTextColor FAILURE = NamedTextColor.RED;
	public static final NamedTextColor ACCENT = NamedTextColor.AQUA;

	private static final Component PREFIX = Component.text()
			.append(Component.text("Strike", PRIMARY, TextDecoration.BOLD))
			.append(Component.text("Sync", SECONDARY, TextDecoration.BOLD))
			.build();

	private Brand() {
	}

	/** A clickable, branded plugin prefix used in every message. */
	public static Component prefix() {
		return PREFIX;
	}

	/** Builds a message line: {@code Strike}{@code Sync}{@code  · }{@code body}. */
	public static Component line(Component body) {
		return Component.text()
				.append(PREFIX)
				.append(Component.text(" » ", MUTED))
				.append(body)
				.build();
	}

	public static Component line(String body) {
		return line(Component.text(body, TEXT));
	}

	public static Component success(String body) {
		return line(Component.text(body, SUCCESS));
	}

	public static Component failure(String body) {
		return line(Component.text(body, FAILURE));
	}

	public static Component info(String body) {
		return line(Component.text(body, TEXT));
	}
}
