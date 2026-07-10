package com.soleysus.cobblemounts;

import com.cobblemon.mod.common.api.riding.RidingStyle;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * Mount categories used by this sidemod.
 * <p>
 * LAND / LIQUID / AIR map 1:1 to Cobblemon {@link RidingStyle}.
 * TELEPORT opens the Waystones selection UI for Abra/Ralts lines.
 */
public enum MountStyle {
	LAND,
	LIQUID,
	AIR,
	/** Teleport — Waystones UI, not a rideable mount. */
	TELEPORT;

	public boolean isRideable() {
		return this != TELEPORT;
	}

	@Nullable
	public RidingStyle toRidingStyle() {
		return switch (this) {
			case LAND -> RidingStyle.LAND;
			case LIQUID -> RidingStyle.LIQUID;
			case AIR -> RidingStyle.AIR;
			case TELEPORT -> null;
		};
	}

	public String displayKey() {
		return "screen.cobble_mounts.style." + name().toLowerCase(Locale.ROOT);
	}

	public static MountStyle fromString(String raw) {
		return valueOf(raw.trim().toUpperCase(Locale.ROOT));
	}

	/** Accepts both MountStyle and legacy RidingStyle names. */
	public static MountStyle parse(String raw) {
		String key = raw.trim().toUpperCase(Locale.ROOT);
		if ("NO_MOUNT".equals(key) || "NONE".equals(key) || "TP".equals(key) || "TELEPORT".equals(key)) {
			return TELEPORT;
		}
		return valueOf(key);
	}
}
