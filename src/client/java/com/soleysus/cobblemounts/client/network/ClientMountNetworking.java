package com.soleysus.cobblemounts.client.network;

import com.soleysus.cobblemounts.client.gui.MountMenuScreen;
import com.soleysus.cobblemounts.client.gui.MountMenuState;
import com.soleysus.cobblemounts.network.payload.MountSyncPayload;
import com.soleysus.cobblemounts.network.payload.OpenMountMenuPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class ClientMountNetworking {
	private ClientMountNetworking() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(MountSyncPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> {
				MountMenuState.update(payload);
				if (context.client().screen instanceof MountMenuScreen screen) {
					screen.refreshFromState();
				}
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(OpenMountMenuPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> {
				Minecraft mc = context.client();
				mc.setScreen(new MountMenuScreen());
			});
		});
	}
}
