package me.vexmc.strikesync.command.nodes;

import me.vexmc.strikesync.StrikeSyncPlugin;
import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.message.Messages;

public final class ReloadNode extends CommandNode {

	public ReloadNode() {
		super("reload", "strikesync.command.reload");
	}

	@Override
	public String usage() {
		return "/ss reload";
	}

	@Override
	public boolean execute(CommandContext context) {
		long start = System.currentTimeMillis();
		try {
			((StrikeSyncPlugin) context.service().plugin()).reloadAll();
			context.send(Messages.reloadSucceeded(System.currentTimeMillis() - start));
		} catch (Throwable t) {
			context.service().log().error("Reload failed", t);
			context.send(Messages.reloadFailed(t.getClass().getSimpleName()));
		}
		return true;
	}
}
