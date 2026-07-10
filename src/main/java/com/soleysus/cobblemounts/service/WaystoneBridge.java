package com.soleysus.cobblemounts.service;

import com.soleysus.cobblemounts.CobbleMounts;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Soft integration with Blay's Waystones.
 * Opens the same selection GUI used by warp stone / inventory button, without a hard dependency.
 */
public final class WaystoneBridge {
	public static final String MOD_ID = "waystones";

	private WaystoneBridge() {
	}

	public static boolean isAvailable() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	/**
	 * Opens the Waystones selection menu for the player (like using a Warp Stone / inventory button).
	 *
	 * @return true if the UI was opened
	 */
	public static boolean openSelectionMenu(ServerPlayer player) {
		if (!isAvailable()) {
			return false;
		}
		try {
			// new WaystoneSelectionListBuilder(player).withTargetsForPlayer()
			//   .buildMenuProvider(ModMenus.inventorySelection.get(), title)
			// Balm.getNetworking().openGui(player, provider)
			Class<?> builderClass = Class.forName("net.blay09.mods.waystones.menu.WaystoneSelectionListBuilder");
			Constructor<?> ctor = builderClass.getConstructor(ServerPlayer.class);
			Object builder = ctor.newInstance(player);

			Method withTargets = builderClass.getMethod("withTargetsForPlayer");
			builder = withTargets.invoke(builder);

			// Optional: inventory-button style flags when available
			try {
				Class<?> flagsClass = Class.forName("net.blay09.mods.waystones.api.TeleportFlags");
				Object inventoryFlag = flagsClass.getField("INVENTORY_BUTTON").get(null);
				Method withFlags = builderClass.getMethod("withFlags", Set.class);
				builder = withFlags.invoke(builder, Set.of(inventoryFlag));
			} catch (ReflectiveOperationException ignored) {
				// Flags optional
			}

			Class<?> modMenus = Class.forName("net.blay09.mods.waystones.menu.ModMenus");
			Object deferred = modMenus.getField("inventorySelection").get(null);
			// Prefer inventorySelection; fallback to warpStoneSelection
			if (deferred == null) {
				deferred = modMenus.getField("warpStoneSelection").get(null);
			}
			Object menuType = deferred.getClass().getMethod("get").invoke(deferred);

			Component title = Component.translatable("container.waystones.waystone_selection");
			Method buildProvider = null;
			for (Method m : builderClass.getMethods()) {
				if (m.getName().equals("buildMenuProvider") && m.getParameterCount() == 2) {
					buildProvider = m;
					break;
				}
			}
			if (buildProvider == null) {
				CobbleMounts.LOGGER.error("Waystones buildMenuProvider not found");
				return false;
			}
			Object provider = buildProvider.invoke(builder, menuType, title);

			Class<?> balm = Class.forName("net.blay09.mods.balm.api.Balm");
			Object networking = balm.getMethod("getNetworking").invoke(null);
			// openGui(Player, MenuProvider)
			Method openGui = null;
			for (Method m : networking.getClass().getMethods()) {
				if (m.getName().equals("openGui") && m.getParameterCount() == 2) {
					openGui = m;
					break;
				}
			}
			if (openGui == null) {
				CobbleMounts.LOGGER.error("Balm openGui not found");
				return false;
			}
			openGui.invoke(networking, player, provider);
			return true;
		} catch (Throwable t) {
			CobbleMounts.LOGGER.error("Failed to open Waystones selection UI", t);
			return false;
		}
	}
}
