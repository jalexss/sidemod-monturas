package com.soleysus.cobblemounts.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lightweight smoke-test mixin retained from scaffolding.
 */
@Mixin(Minecraft.class)
public class CobbleMountsClientMixin {

	@Inject(at = @At("HEAD"), method = "run")
	private void cobbleMounts$onRun(CallbackInfo info) {
		// Intentionally quiet — use keybind H / /cobblemounts instead of console spam.
	}
}
