package com.soleysus.cobblemounts;

import com.soleysus.cobblemounts.command.MountCommands;
import com.soleysus.cobblemounts.network.MountNetworking;
import com.soleysus.cobblemounts.service.MountService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CobbleMounts implements ModInitializer {
	public static final String MOD_ID = "cobble_mounts";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("¡Inicializando el sistema de monturas personalizadas!");
		MountNetworking.registerCommon();
		MountNetworking.registerServer();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				MountCommands.register(dispatcher));
		// Delayed mount retries + recall Pokémon when the player dismounts
		ServerTickEvents.END_SERVER_TICK.register(MountService::tickActiveMounts);
		// Sync assigned mount UUIDs so PC slots show as locked without opening the menu
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				server.execute(() -> {
					try {
						MountService.syncTo(handler.player);
					} catch (Exception ex) {
						LOGGER.warn("Failed to sync mounts on join for {}", handler.player.getGameProfile().getName(), ex);
					}
				}));
	}
}
