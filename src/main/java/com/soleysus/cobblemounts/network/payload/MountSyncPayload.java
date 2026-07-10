package com.soleysus.cobblemounts.network.payload;

import com.soleysus.cobblemounts.CobbleMounts;
import com.soleysus.cobblemounts.MountStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public record MountSyncPayload(
		Map<MountStyle, List<@Nullable SlotInfo>> slots,
		List<CandidateInfo> candidates,
		boolean waystonesPresent,
		boolean currentlyMounted
) implements CustomPacketPayload {
	public static final Type<MountSyncPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CobbleMounts.MOD_ID, "mount_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MountSyncPayload> CODEC = StreamCodec.of(
			MountSyncPayload::write,
			MountSyncPayload::read
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private static void write(RegistryFriendlyByteBuf buf, MountSyncPayload payload) {
		buf.writeVarInt(MountStyle.values().length);
		for (MountStyle style : MountStyle.values()) {
			buf.writeUtf(style.name());
			List<SlotInfo> list = payload.slots.getOrDefault(style, List.of());
			buf.writeVarInt(list.size());
			for (SlotInfo info : list) {
				buf.writeBoolean(info != null);
				if (info != null) {
					buf.writeUUID(info.uuid());
					buf.writeUtf(info.species());
					buf.writeVarInt(info.level());
					buf.writeUtf(info.displayName());
					buf.writeBoolean(info.canMega());
					buf.writeBoolean(info.megaEnabled());
				}
			}
		}
		buf.writeVarInt(payload.candidates.size());
		for (CandidateInfo c : payload.candidates) {
			buf.writeUUID(c.uuid());
			buf.writeUtf(c.species());
			buf.writeVarInt(c.level());
			buf.writeUtf(c.displayName());
			buf.writeUtf(c.source());
			buf.writeVarInt(c.styles().size());
			for (String s : c.styles()) {
				buf.writeUtf(s);
			}
			buf.writeBoolean(c.assignedAsMount());
			buf.writeBoolean(c.hasMegaStone());
			buf.writeUtf(c.speciesName());
			buf.writeUtf(c.translatedSpeciesName());
			buf.writeUtf(c.nickname());
		}
		buf.writeBoolean(payload.waystonesPresent);
		buf.writeBoolean(payload.currentlyMounted);
	}

	private static MountSyncPayload read(RegistryFriendlyByteBuf buf) {
		Map<MountStyle, List<SlotInfo>> slots = new EnumMap<>(MountStyle.class);
		int styleCount = buf.readVarInt();
		for (int i = 0; i < styleCount; i++) {
			MountStyle style;
			try {
				style = MountStyle.parse(buf.readUtf());
			} catch (IllegalArgumentException ex) {
				// Skip unknown style block
				int n = buf.readVarInt();
				for (int j = 0; j < n; j++) {
					if (buf.readBoolean()) {
						buf.readUUID();
						buf.readUtf();
						buf.readVarInt();
						buf.readUtf();
						buf.readBoolean();
						buf.readBoolean();
					}
				}
				continue;
			}
			int n = buf.readVarInt();
			List<SlotInfo> list = new ArrayList<>(n);
			for (int j = 0; j < n; j++) {
				if (buf.readBoolean()) {
					list.add(new SlotInfo(
							buf.readUUID(),
							buf.readUtf(),
							buf.readVarInt(),
							buf.readUtf(),
							buf.readBoolean(),
							buf.readBoolean()
					));
				} else {
					list.add(null);
				}
			}
			slots.put(style, list);
		}
		int candCount = buf.readVarInt();
		List<CandidateInfo> candidates = new ArrayList<>(candCount);
		for (int i = 0; i < candCount; i++) {
			UUID uuid = buf.readUUID();
			String species = buf.readUtf();
			int level = buf.readVarInt();
			String name = buf.readUtf();
			String source = buf.readUtf();
			int sc = buf.readVarInt();
			List<String> styles = new ArrayList<>(sc);
			for (int j = 0; j < sc; j++) {
				styles.add(buf.readUtf());
			}
			boolean assigned = buf.readBoolean();
			boolean hasMega = buf.readBoolean();
			String speciesName = buf.readUtf();
			String translated = buf.readUtf();
			String nickname = buf.readUtf();
			candidates.add(new CandidateInfo(
					uuid, species, level, name, source, styles, assigned, hasMega, speciesName, translated, nickname
			));
		}
		boolean waystones = buf.readBoolean();
		boolean mounted = buf.readBoolean();
		return new MountSyncPayload(slots, candidates, waystones, mounted);
	}

	public record SlotInfo(
			UUID uuid,
			String species,
			int level,
			String displayName,
			boolean canMega,
			boolean megaEnabled
	) {
	}

	public record CandidateInfo(
			UUID uuid,
			String species,
			int level,
			String displayName,
			String source,
			List<String> styles,
			boolean assignedAsMount,
			boolean hasMegaStone,
			String speciesName,
			String translatedSpeciesName,
			String nickname
	) {
		public boolean matchesSearch(String query) {
			if (query == null || query.isBlank()) {
				return true;
			}
			String q = query.toLowerCase();
			return contains(displayName, q)
					|| contains(speciesName, q)
					|| contains(translatedSpeciesName, q)
					|| contains(nickname, q)
					|| contains(species, q);
		}

		private static boolean contains(String value, String q) {
			return value != null && value.toLowerCase().contains(q);
		}
	}
}
