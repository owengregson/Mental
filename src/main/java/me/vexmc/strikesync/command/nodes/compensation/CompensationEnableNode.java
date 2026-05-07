package me.vexmc.strikesync.command.nodes.compensation;

import me.vexmc.strikesync.StrikeSyncPlugin;
import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.message.Brand;

public final class CompensationEnableNode extends CommandNode {

	public CompensationEnableNode() {
		super("enable", "strikesync.command.compensation");
	}

	@Override
	public String usage() {
		return "/ss compensation enable";
	}

	@Override
	public boolean execute(CommandContext context) {
		context.service().config().set("compensation.enabled", true);
		((StrikeSyncPlugin) context.service().plugin()).reloadAll();
		context.send(Brand.success("Latency compensation enabled."));
		return true;
	}
}
