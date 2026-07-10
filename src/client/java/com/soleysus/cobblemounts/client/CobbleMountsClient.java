package com.soleysus.cobblemounts.client;

import com.soleysus.cobblemounts.client.network.ClientMountNetworking;
import com.soleysus.cobblemounts.network.payload.MountActionPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class CobbleMountsClient implements ClientModInitializer {
	public static KeyMapping openMountMenuKey;

	@Override
	public void onInitializeClient() {
		ClientMountNetworking.register();

		openMountMenuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.cobble_mounts.open_menu",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_H,
				"key.categories.cobble_mounts"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMountMenuKey.consumeClick()) {
				if (client.player == null) {
					return;
				}
				// Works in-world and while PC (or any screen) is open: request sync + menu
				ClientPlayNetworking.send(new MountActionPayload(
						MountActionPayload.Action.OPEN,
						"LAND",
						0,
						null
				));
			}
		});
	}
}
