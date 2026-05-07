package me.vexmc.strikesync.command.nodes.knockback;

import me.vexmc.strikesync.command.CommandContext;
import me.vexmc.strikesync.command.CommandNode;
import me.vexmc.strikesync.config.HitRegSettings;
import me.vexmc.strikesync.config.KnockbackSettings;
import me.vexmc.strikesync.message.Messages;

public final class KnockbackStatusNode extends CommandNode {

	public KnockbackStatusNode() {
		super("status", "strikesync.command.knockback");
	}

	@Override
	public String usage() {
		return "/ss knockback status";
	}

	@Override
	public boolean execute(CommandContext context) {
		KnockbackSettings k = context.service().config().knockback();
		HitRegSettings h = context.service().config().hitReg();
		context.send(Messages.knockbackStatus(k.enabled(), false, h.maxCps()));
		return true;
	}
}
