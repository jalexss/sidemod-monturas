package com.soleysus.cobblemounts.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * World-level backup of mount soft-refs, keyed by player UUID.
 * <p>
 * Stored under the overworld data storage so assignments always live in the world
 * save, independent of Cobblemon custom-store dirty flags and player session state.
 */
public class MountAssignmentWorldData extends SavedData {
	public static final String DATA_NAME = "cobble_mounts_assignments";
	private static final String KEY_PLAYERS = "Players";
	private static final String KEY_ID = "Id";
	private static final String KEY_DATA = "Data";

	private final Map<UUID, CompoundTag> byPlayer = new HashMap<>();

	public static SavedData.Factory<MountAssignmentWorldData> factory() {
		return new SavedData.Factory<>(
				MountAssignmentWorldData::new,
				MountAssignmentWorldData::load,
				DataFixTypes.LEVEL
		);
	}

	public static MountAssignmentWorldData get(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		return overworld.getDataStorage().computeIfAbsent(factory(), DATA_NAME);
	}

	public static MountAssignmentWorldData get(ServerLevel anyLevel) {
		return get(anyLevel.getServer());
	}

	public void put(UUID playerId, CompoundTag softRefsNbt) {
		if (playerId == null || softRefsNbt == null) {
			return;
		}
		byPlayer.put(playerId, softRefsNbt.copy());
		setDirty();
	}

	@Nullable
	public CompoundTag get(UUID playerId) {
		CompoundTag tag = byPlayer.get(playerId);
		return tag == null ? null : tag.copy();
	}

	public boolean has(UUID playerId) {
		CompoundTag tag = byPlayer.get(playerId);
		return tag != null && !tag.isEmpty();
	}

	public void remove(UUID playerId) {
		if (byPlayer.remove(playerId) != null) {
			setDirty();
		}
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		for (Map.Entry<UUID, CompoundTag> entry : byPlayer.entrySet()) {
			CompoundTag row = new CompoundTag();
			row.putUUID(KEY_ID, entry.getKey());
			row.put(KEY_DATA, entry.getValue().copy());
			list.add(row);
		}
		tag.put(KEY_PLAYERS, list);
		return tag;
	}

	public static MountAssignmentWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
		MountAssignmentWorldData data = new MountAssignmentWorldData();
		if (!tag.contains(KEY_PLAYERS, Tag.TAG_LIST)) {
			return data;
		}
		ListTag list = tag.getList(KEY_PLAYERS, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag row = list.getCompound(i);
			if (!row.hasUUID(KEY_ID) || !row.contains(KEY_DATA, Tag.TAG_COMPOUND)) {
				continue;
			}
			data.byPlayer.put(row.getUUID(KEY_ID), row.getCompound(KEY_DATA).copy());
		}
		return data;
	}
}
