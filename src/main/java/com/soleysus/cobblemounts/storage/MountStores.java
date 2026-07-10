package com.soleysus.cobblemounts.storage;

import com.cobblemon.mod.common.Cobblemon;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;

public final class MountStores {
	private MountStores() {
	}

	public static MountBankStore get(ServerPlayer player) {
		return get(player.getUUID(), player.registryAccess());
	}

	public static MountBankStore get(UUID playerId, RegistryAccess registryAccess) {
		MountBankStore store = Cobblemon.INSTANCE.getStorage().getCustomStore(MountBankStore.class, playerId, registryAccess);
		if (store == null) {
			throw new IllegalStateException("Unable to load mount bank for " + playerId);
		}
		return store;
	}
}
