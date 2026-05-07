package me.vexmc.strikesync.command.nodes;

import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.message.Brand;
import me.vexmc.strikesync.message.Messages;
import me.vexmc.strikesync.module.compensation.LatencyCompensationModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

import static me.vexmc.strikesync.message.Brand.ACCENT;
import static me.vexmc.strikesync.message.Brand.MUTED;
import static me.vexmc.strikesync.message.Brand.TEXT;
import static me.vexmc.strikesync.message.Brand.SUCCESS;
import static me.vexmc.strikesync.message.Brand.FAILURE;

public final class PingNode extends CommandNode {

	public PingNode() {
		super("ping", "strikesync.command.ping");
	}

	@Override
	public String usage() {
		return "/ss ping [player]";
	}

	@Override
	public boolean execute(CommandContext context) {
		Player target;
		if (context.hasMore()) {
			String name = context.next();
			target = Bukkit.getPlayerExact(name);
			if (target == null) {
				context.send(Brand.failure("No online player named '" + name + "'."));
				return true;
			}
		} else if (context.sender() instanceof Player p) {
			target = p;
		} else {
			context.send(Messages.usage("/ss ping <player>"));
			return true;
		}

		LatencyCompensationModule comp = context.service().lookup(LatencyCompensationModule.class);
		if (comp == null) {
			context.send(Brand.failure("Latency compensation module is not loaded."));
			return true;
		}

		LatencyCompensationModule.Snapshot snapshot = comp.pingSnapshot(target.getUniqueId());
		Component header = Brand.line(Component.text("ping for ", MUTED)
				.append(Component.text(target.getName(), TEXT)));

		if (snapshot.pingMs() == null) {
			context.send(header);
			context.send(row("real RTT", "no probe yet (player may be out of combat)", MUTED));
			context.send(row("vanilla", target.getPing() + " ms", ACCENT));
			return true;
		}

		context.send(header);
		context.send(row("real RTT", round(snapshot.pingMs()) + " ms", ACCENT));
		if (snapshot.previousPingMs() != null) {
			context.send(row("previous", round(snapshot.previousPingMs()) + " ms", MUTED));
		}
		context.send(row("jitter", round(snapshot.jitterMs()) + " ms", ACCENT));
		context.send(row("spike", snapshot.spike() ? "yes" : "no",
				snapshot.spike() ? FAILURE : SUCCESS));
		context.send(row("vanilla", target.getPing() + " ms", MUTED));
		return true;
	}

	@Override
	public List<String> complete(CommandContext context) {
		if (context.remaining() <= 1) {
			return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
		}
		return List.of();
	}

	private static Component row(String label, String value, net.kyori.adventure.text.format.TextColor valueColor) {
		return Component.text()
				.append(Component.text("  "))
				.append(Component.text(label + ": ", MUTED))
				.append(Component.text(value, valueColor))
				.build();
	}

	private static double round(double v) {
		return Math.round(v * 100.0D) / 100.0D;
	}
}
