package com.soleysus.cobblemounts.network;

import com.soleysus.cobblemounts.CobbleMounts;
import com.soleysus.cobblemounts.MountStyle;
import com.soleysus.cobblemounts.network.payload.MountActionPayload;
import com.soleysus.cobblemounts.network.payload.MountSyncPayload;
import com.soleysus.cobblemounts.network.payload.OpenMountMenuPayload;
import com.soleysus.cobblemounts.service.MountService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class MountNetworking {
	private MountNetworking() {
	}

	public static void registerCommon() {
		PayloadTypeRegistry.playC2S().register(MountActionPayload.TYPE, MountActionPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(MountSyncPayload.TYPE, MountSyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(OpenMountMenuPayload.TYPE, OpenMountMenuPayload.CODEC);
	}

	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(MountActionPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> handleAction(player, payload));
		});
	}

	private static void handleAction(ServerPlayer player, MountActionPayload payload) {
		try {
			switch (payload.action()) {
				case OPEN -> {
					MountService.syncTo(player);
					sendToPlayer(player, OpenMountMenuPayload.INSTANCE);
				}
				case ASSIGN -> {
					MountStyle style = MountStyle.parse(payload.styleName());
					if (payload.pokemonId() == null) {
						MountService.chat(player, Component.translatable("message.cobble_mounts.missing_pokemon_id"));
						return;
					}
					MountService.Result result = MountService.assign(player, style, payload.slot(), payload.pokemonId());
					MountService.chat(player, result);
					MountService.syncTo(player);
				}
				case UNASSIGN -> {
					MountStyle style = MountStyle.parse(payload.styleName());
					MountService.Result result = MountService.unassign(player, style, payload.slot());
					MountService.chat(player, result);
					MountService.syncTo(player);
				}
				case SUMMON -> {
					MountStyle style = MountStyle.parse(payload.styleName());
					MountService.Result result = MountService.summonAndRide(player, style, payload.slot());
					MountService.chat(player, result);
					// Close not forced for teleport (menu already replaced by waystones)
				}
				case DISMOUNT -> {
					MountService.Result result = MountService.dismount(player);
					MountService.chat(player, result);
					MountService.syncTo(player);
				}
				case SET_MEGA -> {
					MountStyle style = MountStyle.parse(payload.styleName());
					MountService.Result result = MountService.setMegaMode(player, style, payload.slot(), payload.flag());
					MountService.chat(player, result);
					MountService.syncTo(player);
				}
			}
		} catch (IllegalArgumentException ex) {
			CobbleMounts.LOGGER.warn("Invalid mount action from {}: {}", player.getGameProfile().getName(), ex.getMessage());
		} catch (Exception ex) {
			CobbleMounts.LOGGER.error("Error handling mount action", ex);
			MountService.chat(player, Component.translatable("message.cobble_mounts.internal_error",
					ex.getMessage() == null ? "?" : ex.getMessage()));
		}
	}

	public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
		ServerPlayNetworking.send(player, payload);
	}
}
