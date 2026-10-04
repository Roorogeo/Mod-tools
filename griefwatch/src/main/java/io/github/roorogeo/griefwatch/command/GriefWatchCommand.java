package io.github.roorogeo.griefwatch.command;

import com.mojang.brigadier.CommandDispatcher;
import io.github.roorogeo.griefwatch.GriefWatch;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** /griefwatch reload | status - requires permission level 3 (admins). */
public final class GriefWatchCommand {
	private GriefWatchCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("griefwatch")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("reload").executes(ctx -> {
					CommandSourceStack source = ctx.getSource();
					source.sendSuccess(() -> Component.literal("Reloading GriefWatch config..."), false);
					GriefWatch.reloadAsync(source.getServer(),
							message -> source.sendSuccess(() -> Component.literal(message), true),
							message -> source.sendFailure(Component.literal(message)));
					return 1;
				}))
				.then(Commands.literal("status").executes(ctx -> {
					String status = GriefWatch.status();
					ctx.getSource().sendSuccess(() -> Component.literal(status), false);
					return 1;
				})));
	}
}
