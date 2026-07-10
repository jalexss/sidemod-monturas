package com.soleysus.cobblemounts.storage;

import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.soleysus.cobblemounts.MountStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Soft-reference mount slots. Pokémon remain in the player's PC; this store only keeps
 * UUID pointers (and optional mega-use flags) per {@link MountStyle}.
 * <p>
 * Extends {@link PlayerPartyStore} for ownership compatibility with older saves that still
 * hold Pokémon in the bank; new assigns no longer move mons out of the PC.
 */
public class MountBankStore extends PlayerPartyStore {
	public static final int SLOTS_PER_STYLE = 3;
	private static final String KEY_SLOTS = "MountStyleSlots";
	private static final String KEY_MEGA = "MountMegaFlags";
	private static final String KEY_STYLE = "Style";
	private static final String KEY_UUIDS = "Uuids";
	private static final String KEY_FLAGS = "Flags";

	private final Map<MountStyle, UUID[]> styleSlots = new EnumMap<>(MountStyle.class);
	/** When true, summon uses the matching mega form if the mon holds the correct stone. */
	private final Map<MountStyle, boolean[]> megaFlags = new EnumMap<>(MountStyle.class);

	public MountBankStore(UUID playerId) {
		super(playerId, playerId);
		for (MountStyle style : MountStyle.values()) {
			styleSlots.put(style, new UUID[SLOTS_PER_STYLE]);
			megaFlags.put(style, new boolean[SLOTS_PER_STYLE]);
		}
	}

	@Override
	public void initialize() {
		super.initialize();
		getObserverUUIDs().clear();
	}

	@Override
	public void sendTo(ServerPlayer player) {
		// Client uses MountSyncPayload instead of party packets.
	}

	public Map<MountStyle, UUID[]> getStyleSlots() {
		return styleSlots;
	}

	/** Unique Pokémon still physically stored in this bank (legacy). */
	public List<Pokemon> getBank() {
		List<Pokemon> list = new ArrayList<>();
		for (Pokemon pokemon : this) {
			list.add(pokemon);
		}
		return list;
	}

	@Nullable
	public Pokemon getByUuid(UUID pokemonId) {
		return get(pokemonId);
	}

	@Nullable
	public UUID getSlotUuid(MountStyle style, int slot) {
		if (slot < 0 || slot >= SLOTS_PER_STYLE) {
			return null;
		}
		return styleSlots.get(style)[slot];
	}

	public void setSlot(MountStyle style, int slot, @Nullable UUID pokemonId) {
		if (slot < 0 || slot >= SLOTS_PER_STYLE) {
			return;
		}
		styleSlots.get(style)[slot] = pokemonId;
		if (pokemonId == null) {
			megaFlags.get(style)[slot] = false;
		}
		touch();
	}

	/**
	 * First empty slot index for {@code style}, or {@code -1} if full.
	 */
	public int firstEmptySlot(MountStyle style) {
		UUID[] slots = styleSlots.get(style);
		for (int i = 0; i < SLOTS_PER_STYLE; i++) {
			if (slots[i] == null) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Slot index where {@code pokemonId} sits under {@code style}, or {@code -1}.
	 */
	public int indexOf(MountStyle style, UUID pokemonId) {
		if (pokemonId == null) {
			return -1;
		}
		UUID[] slots = styleSlots.get(style);
		for (int i = 0; i < SLOTS_PER_STYLE; i++) {
			if (pokemonId.equals(slots[i])) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Removes every occurrence of {@code pokemonId} across all mount styles.
	 * Used so multi-type mounts stay in sync when unassigned from any slot.
	 */
	public void clearAllReferences(UUID pokemonId) {
		if (pokemonId == null) {
			return;
		}
		boolean changed = false;
		for (MountStyle style : MountStyle.values()) {
			UUID[] slots = styleSlots.get(style);
			boolean[] flags = megaFlags.get(style);
			for (int i = 0; i < SLOTS_PER_STYLE; i++) {
				if (pokemonId.equals(slots[i])) {
					slots[i] = null;
					flags[i] = false;
					changed = true;
				}
			}
		}
		if (changed) {
			touch();
		}
	}

	/**
	 * Clears this UUID from every slot of a single style (so it can be moved
	 * to another slot of the same style without leaving a duplicate).
	 */
	public void clearStyleReferences(MountStyle style, UUID pokemonId) {
		if (pokemonId == null) {
			return;
		}
		UUID[] slots = styleSlots.get(style);
		boolean[] flags = megaFlags.get(style);
		boolean changed = false;
		for (int i = 0; i < SLOTS_PER_STYLE; i++) {
			if (pokemonId.equals(slots[i])) {
				slots[i] = null;
				flags[i] = false;
				changed = true;
			}
		}
		if (changed) {
			touch();
		}
	}

	public boolean isMegaEnabled(MountStyle style, int slot) {
		if (slot < 0 || slot >= SLOTS_PER_STYLE) {
			return false;
		}
		return megaFlags.get(style)[slot];
	}

	public void setMegaEnabled(MountStyle style, int slot, boolean enabled) {
		if (slot < 0 || slot >= SLOTS_PER_STYLE) {
			return;
		}
		megaFlags.get(style)[slot] = enabled;
		touch();
	}

	public boolean isReferenced(UUID pokemonId) {
		for (UUID[] styleSlot : styleSlots.values()) {
			for (UUID id : styleSlot) {
				if (pokemonId.equals(id)) {
					return true;
				}
			}
		}
		return false;
	}

	/** All unique UUIDs currently assigned as mounts. */
	public Set<UUID> allAssignedUuids() {
		Set<UUID> out = new HashSet<>();
		for (UUID[] styleSlot : styleSlots.values()) {
			for (UUID id : styleSlot) {
				if (id != null) {
					out.add(id);
				}
			}
		}
		return out;
	}

	private void touch() {
		for (Pokemon p : this) {
			onPokemonChanged(p);
			return;
		}
	}

	@Override
	public CompoundTag saveToNBT(CompoundTag nbt, RegistryAccess registryAccess) {
		super.saveToNBT(nbt, registryAccess);
		nbt.put(KEY_SLOTS, writeStyleSlotsNbt());
		nbt.put(KEY_MEGA, writeMegaFlagsNbt());
		return nbt;
	}

	@Override
	public PartyStore loadFromNBT(CompoundTag nbt, RegistryAccess registryAccess) {
		super.loadFromNBT(nbt, registryAccess);
		readStyleSlotsNbt(nbt);
		readMegaFlagsNbt(nbt);
		return this;
	}

	@Override
	public JsonObject saveToJSON(JsonObject json, RegistryAccess registryAccess) {
		super.saveToJSON(json, registryAccess);
		JsonObject slotsJson = new JsonObject();
		JsonObject megaJson = new JsonObject();
		for (MountStyle style : MountStyle.values()) {
			JsonArray arr = new JsonArray();
			for (UUID id : styleSlots.get(style)) {
				arr.add(id == null ? "" : id.toString());
			}
			slotsJson.add(style.name(), arr);
			JsonArray flags = new JsonArray();
			for (boolean f : megaFlags.get(style)) {
				flags.add(f);
			}
			megaJson.add(style.name(), flags);
		}
		json.add(KEY_SLOTS, slotsJson);
		json.add(KEY_MEGA, megaJson);
		return json;
	}

	@Override
	public PartyStore loadFromJSON(JsonObject json, RegistryAccess registryAccess) {
		super.loadFromJSON(json, registryAccess);
		for (MountStyle style : MountStyle.values()) {
			styleSlots.put(style, new UUID[SLOTS_PER_STYLE]);
			megaFlags.put(style, new boolean[SLOTS_PER_STYLE]);
		}
		if (json.has(KEY_SLOTS)) {
			JsonObject slotsJson = json.getAsJsonObject(KEY_SLOTS);
			for (MountStyle style : MountStyle.values()) {
				if (!slotsJson.has(style.name())) {
					// Legacy RidingStyle names already match LAND/LIQUID/AIR
					continue;
				}
				JsonArray arr = slotsJson.getAsJsonArray(style.name());
				UUID[] dest = styleSlots.get(style);
				int j = 0;
				for (JsonElement el : arr) {
					if (j >= SLOTS_PER_STYLE) {
						break;
					}
					String raw = el.getAsString();
					dest[j++] = raw.isEmpty() ? null : UUID.fromString(raw);
				}
			}
		}
		if (json.has(KEY_MEGA)) {
			JsonObject megaJson = json.getAsJsonObject(KEY_MEGA);
			for (MountStyle style : MountStyle.values()) {
				if (!megaJson.has(style.name())) {
					continue;
				}
				JsonArray arr = megaJson.getAsJsonArray(style.name());
				boolean[] dest = megaFlags.get(style);
				int j = 0;
				for (JsonElement el : arr) {
					if (j >= SLOTS_PER_STYLE) {
						break;
					}
					dest[j++] = el.getAsBoolean();
				}
			}
		}
		return this;
	}

	private ListTag writeStyleSlotsNbt() {
		ListTag stylesTag = new ListTag();
		for (MountStyle style : MountStyle.values()) {
			CompoundTag styleTag = new CompoundTag();
			styleTag.putString(KEY_STYLE, style.name());
			ListTag uuids = new ListTag();
			for (UUID id : styleSlots.get(style)) {
				uuids.add(StringTag.valueOf(id == null ? "" : id.toString()));
			}
			styleTag.put(KEY_UUIDS, uuids);
			stylesTag.add(styleTag);
		}
		return stylesTag;
	}

	private ListTag writeMegaFlagsNbt() {
		ListTag stylesTag = new ListTag();
		for (MountStyle style : MountStyle.values()) {
			CompoundTag styleTag = new CompoundTag();
			styleTag.putString(KEY_STYLE, style.name());
			ListTag flags = new ListTag();
			for (boolean f : megaFlags.get(style)) {
				CompoundTag flagTag = new CompoundTag();
				flagTag.putBoolean("v", f);
				flags.add(flagTag);
			}
			styleTag.put(KEY_FLAGS, flags);
			stylesTag.add(styleTag);
		}
		return stylesTag;
	}

	private void readStyleSlotsNbt(CompoundTag nbt) {
		for (MountStyle style : MountStyle.values()) {
			styleSlots.put(style, new UUID[SLOTS_PER_STYLE]);
		}
		if (!nbt.contains(KEY_SLOTS, Tag.TAG_LIST)) {
			return;
		}
		ListTag stylesTag = nbt.getList(KEY_SLOTS, Tag.TAG_COMPOUND);
		for (int s = 0; s < stylesTag.size(); s++) {
			CompoundTag styleTag = stylesTag.getCompound(s);
			MountStyle style;
			try {
				style = MountStyle.parse(styleTag.getString(KEY_STYLE));
			} catch (IllegalArgumentException ex) {
				continue;
			}
			ListTag uuids = styleTag.getList(KEY_UUIDS, Tag.TAG_STRING);
			UUID[] arr = styleSlots.get(style);
			for (int j = 0; j < Math.min(SLOTS_PER_STYLE, uuids.size()); j++) {
				String raw = uuids.getString(j);
				arr[j] = raw.isEmpty() ? null : UUID.fromString(raw);
			}
		}
	}

	private void readMegaFlagsNbt(CompoundTag nbt) {
		for (MountStyle style : MountStyle.values()) {
			megaFlags.put(style, new boolean[SLOTS_PER_STYLE]);
		}
		if (!nbt.contains(KEY_MEGA, Tag.TAG_LIST)) {
			return;
		}
		ListTag stylesTag = nbt.getList(KEY_MEGA, Tag.TAG_COMPOUND);
		for (int s = 0; s < stylesTag.size(); s++) {
			CompoundTag styleTag = stylesTag.getCompound(s);
			MountStyle style;
			try {
				style = MountStyle.parse(styleTag.getString(KEY_STYLE));
			} catch (IllegalArgumentException ex) {
				continue;
			}
			ListTag flags = styleTag.getList(KEY_FLAGS, Tag.TAG_COMPOUND);
			boolean[] arr = megaFlags.get(style);
			for (int j = 0; j < Math.min(SLOTS_PER_STYLE, flags.size()); j++) {
				arr[j] = flags.getCompound(j).getBoolean("v");
			}
		}
	}
}
