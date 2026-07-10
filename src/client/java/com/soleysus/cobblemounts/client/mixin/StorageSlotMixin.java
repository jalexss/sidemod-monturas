package com.soleysus.cobblemounts.client.mixin;

import com.cobblemon.mod.common.client.gui.pc.StorageSlot;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.soleysus.cobblemounts.client.gui.MountMenuState;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks PC Pokémon that are assigned as mounts as non-interactable and visually dimmed.
 */
@Mixin(value = StorageSlot.class, remap = false)
public abstract class StorageSlotMixin {
	@Shadow
	public abstract Pokemon getPokemon();

	@Inject(method = "clickable", at = @At("RETURN"), cancellable = true)
	private void cobbleMounts$blockMountPokemon(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue()) {
			return;
		}
		Pokemon pokemon = getPokemon();
		if (pokemon != null && MountMenuState.isAssignedAsMount(pokemon.getUuid())) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "renderSlot", at = @At("TAIL"))
	private void cobbleMounts$dimMountPokemon(GuiGraphics context, int posX, int posY, float partialTicks, CallbackInfo ci) {
		Pokemon pokemon = getPokemon();
		if (pokemon == null || !MountMenuState.isAssignedAsMount(pokemon.getUuid())) {
			return;
		}
		// Semi-transparent dark overlay + small lock cue
		context.fill(posX, posY, posX + StorageSlot.SIZE, posY + StorageSlot.SIZE, 0x99000000);
		// "M" badge (mount)
		context.drawString(
				net.minecraft.client.Minecraft.getInstance().font,
				"M",
				posX + 2,
				posY + StorageSlot.SIZE - 9,
				0xFFFF55FF,
				true
		);
	}
}
