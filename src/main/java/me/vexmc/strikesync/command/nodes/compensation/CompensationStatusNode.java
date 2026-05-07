package me.vexmc.strikesync.command.nodes.compensation;

import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.config.CompensationSettings;
import me.vexmc.strikesync.message.Brand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

import static me.vexmc.strikesync.message.Brand.ACCENT;
import static me.vexmc.strikesync.message.Brand.FAILURE;
import static me.vexmc.strikesync.message.Brand.MUTED;
import static me.vexmc.strikesync.message.Brand.SUCCESS;
import static me.vexmc.strikesync.message.Brand.TEXT;

public final class CompensationStatusNode extends CommandNode {

	public CompensationStatusNode() {
		super("status", "strikesync.command.compensation");
	}

	@Override
	public String usage() {
		return "/ss compensation status";
	}

	@Override
	public boolean execute(CommandContext context) {
		CompensationSettings c = context.service().config().compensation();
		Component header = Component.text()
				.append(Brand.prefix())
				.append(Component.text(" Compensation: ", TEXT))
				.append(Component.text(c.enabled() ? "ENABLED" : "DISABLED",
						c.enabled() ? SUCCESS : FAILURE, TextDecoration.BOLD))
				.build();
		context.send(header);
		context.send(row("ping offset", c.pingOffsetMillis() + " ms"));
		context.send(row("spike threshold", c.spikeThresholdMillis() + " ms"));
		context.send(row("probe interval", c.probeIntervalTicks() + " ticks"));
		context.send(row("combat timeout", c.combatTimeoutTicks() + " ticks"));
		context.send(row("off-ground sync", String.valueOf(c.offGroundSync())));
		return true;
	}

	private static Component row(String label, String value) {
		return Component.text()
				.append(Component.text("  "))
				.append(Component.text(label + ": ", MUTED))
				.append(Component.text(value, ACCENT))
				.build();
	}
}
