package com.soleysus.cobblemounts.network.payload;

import com.soleysus.cobblemounts.CobbleMounts;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Client → server action for the mount menu.
 */
public record MountActionPayload(
		Action action,
		String styleName,
		int slot,
		@Nullable UUID pokemonId,
		boolean flag
) implements CustomPacketPayload {
	public static final Type<MountActionPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CobbleMounts.MOD_ID, "mount_action"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MountActionPayload> CODEC = StreamCodec.of(
			MountActionPayload::write,
			MountActionPayload::read
	);

	public enum Action {
		OPEN,
		ASSIGN,
		UNASSIGN,
		SUMMON,
		DISMOUNT,
		SET_MEGA
	}

	public MountActionPayload(Action action, String styleName, int slot, @Nullable UUID pokemonId) {
		this(action, styleName, slot, pokemonId, false);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private static void write(RegistryFriendlyByteBuf buf, MountActionPayload payload) {
		buf.writeEnum(payload.action);
		buf.writeUtf(payload.styleName);
		buf.writeVarInt(payload.slot);
		buf.writeBoolean(payload.pokemonId != null);
		if (payload.pokemonId != null) {
			buf.writeUUID(payload.pokemonId);
		}
		buf.writeBoolean(payload.flag);
	}

	private static MountActionPayload read(RegistryFriendlyByteBuf buf) {
		Action action = buf.readEnum(Action.class);
		String style = buf.readUtf();
		int slot = buf.readVarInt();
		UUID id = buf.readBoolean() ? buf.readUUID() : null;
		boolean flag = buf.readBoolean();
		return new MountActionPayload(action, style, slot, id, flag);
	}
}
