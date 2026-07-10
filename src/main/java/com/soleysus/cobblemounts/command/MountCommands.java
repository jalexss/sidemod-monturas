package com.soleysus.cobblemounts.command;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.soleysus.cobblemounts.MountStyle;
import com.soleysus.cobblemounts.network.MountNetworking;
import com.soleysus.cobblemounts.network.payload.OpenMountMenuPayload;
import com.soleysus.cobblemounts.service.MountService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class MountCommands {
	private MountCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("cobblemounts")
				.requires(src -> src.isPlayer())
				.then(Commands.literal("open")
						.executes(MountCommands::open))
				.then(Commands.literal("assign")
						.then(Commands.argument("style", StringArgumentType.word())
								.then(Commands.argument("slot", IntegerArgumentType.integer(1, 3))
										.then(Commands.argument("pcIndex", IntegerArgumentType.integer(0))
												.executes(MountCommands::assignFromPc)))))
				.then(Commands.literal("remove")
						.then(Commands.argument("style", StringArgumentType.word())
								.then(Commands.argument("slot", IntegerArgumentType.integer(1, 3))
										.executes(MountCommands::remove))))
				.then(Commands.literal("summon")
						.then(Commands.argument("style", StringArgumentType.word())
								.then(Commands.argument("slot", IntegerArgumentType.integer(1, 3))
										.executes(MountCommands::summon))))
				.then(Commands.literal("dismount")
						.executes(MountCommands::dismount))
				.executes(MountCommands::open)
		);
	}

	private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		MountService.syncTo(player);
		MountNetworking.sendToPlayer(player, OpenMountMenuPayload.INSTANCE);
		return 1;
	}

	private static int assignFromPc(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		MountStyle style = MountStyle.parse(StringArgumentType.getString(ctx, "style"));
		int slot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
		int pcIndex = IntegerArgumentType.getInteger(ctx, "pcIndex");
		Pokemon pokemon = null;
		int i = 0;
		for (Pokemon p : PlayerExtensionsKt.pc(player)) {
			if (p == null) {
				continue;
			}
			if (i == pcIndex) {
				pokemon = p;
				break;
			}
			i++;
		}
		if (pokemon == null) {
			ctx.getSource().sendFailure(Component.translatable("message.cobble_mounts.pc_index_empty", pcIndex));
			return 0;
		}
		MountService.Result result = MountService.assign(player, style, slot, pokemon.getUuid());
		if (result.success()) {
			ctx.getSource().sendSuccess(result::message, false);
			return 1;
		}
		ctx.getSource().sendFailure(result.message());
		return 0;
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		MountStyle style = MountStyle.parse(StringArgumentType.getString(ctx, "style"));
		int slot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
		MountService.Result result = MountService.unassign(player, style, slot);
		if (result.success()) {
			ctx.getSource().sendSuccess(result::message, false);
			return 1;
		}
		ctx.getSource().sendFailure(result.message());
		return 0;
	}

	private static int summon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		MountStyle style = MountStyle.parse(StringArgumentType.getString(ctx, "style"));
		int slot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
		MountService.Result result = MountService.summonAndRide(player, style, slot);
		if (result.success()) {
			ctx.getSource().sendSuccess(result::message, false);
			return 1;
		}
		ctx.getSource().sendFailure(result.message());
		return 0;
	}

	private static int dismount(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		MountService.Result result = MountService.dismount(player);
		if (result.success()) {
			ctx.getSource().sendSuccess(result::message, false);
			return 1;
		}
		ctx.getSource().sendFailure(result.message());
		return 0;
	}
}
