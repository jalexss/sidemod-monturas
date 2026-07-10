package com.soleysus.cobblemounts.network.payload;

import com.soleysus.cobblemounts.CobbleMounts;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server → client: open the mount menu (sync data is sent separately / together). */
public record OpenMountMenuPayload() implements CustomPacketPayload {
	public static final Type<OpenMountMenuPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CobbleMounts.MOD_ID, "open_mount_menu"));

	public static final OpenMountMenuPayload INSTANCE = new OpenMountMenuPayload();

	public static final StreamCodec<RegistryFriendlyByteBuf, OpenMountMenuPayload> CODEC =
			StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
