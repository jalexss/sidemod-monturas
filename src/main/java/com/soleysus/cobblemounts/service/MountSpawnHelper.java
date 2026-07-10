package com.soleysus.cobblemounts.service;

import com.cobblemon.mod.common.api.riding.RidingStyle;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Safe spawn + environment checks for mount summoning.
 */
public final class MountSpawnHelper {
	private MountSpawnHelper() {
	}

	public static MountService.Result validateEnvironment(ServerPlayer player, RidingStyle style) {
		if (player.isSpectator()) {
			return MountService.Result.failKey("message.cobble_mounts.spectator");
		}
		if (player.isPassenger()) {
			return MountService.Result.failKey("message.cobble_mounts.already_mounted");
		}
		if (player.isInLava()) {
			return MountService.Result.failKey("message.cobble_mounts.in_lava");
		}

		boolean inWater = player.isInWater() || player.isUnderWater()
				|| player.level().getFluidState(player.blockPosition()).is(FluidTags.WATER);

		return switch (style) {
			case LAND -> {
				if (inWater && !player.onGround()) {
					yield MountService.Result.failKey("message.cobble_mounts.land_in_water");
				}
				if (!hasNearbySolidGround(player, 2)) {
					yield MountService.Result.failKey("message.cobble_mounts.land_no_ground");
				}
				yield MountService.Result.ok();
			}
			case LIQUID -> {
				// Allow shoreline; deep dry land is awkward for liquid mounts
				if (!inWater && player.onGround() && !hasNearbyWater(player, 3)) {
					yield MountService.Result.failKey("message.cobble_mounts.liquid_need_water");
				}
				yield MountService.Result.ok();
			}
			case AIR -> {
				// Air mounts can launch from ground; just block tight ceilings
				if (!hasVerticalClearance(player, 3)) {
					yield MountService.Result.failKey("message.cobble_mounts.air_no_space");
				}
				yield MountService.Result.ok();
			}
		};
	}

	/**
	 * Spawn right with the player (slightly in front / at feet) so ride feels instant, not a far send-out.
	 */
	public static Vec3 findMountSpawnPos(ServerPlayer player, Pokemon pokemon) {
		ServerLevel level = player.serverLevel();
		Vec3 look = player.getLookAngle();
		// Prefer feet position, slight forward offset so we don't clip into the player hitbox
		Vec3[] candidates = new Vec3[] {
				player.position().add(look.x * 0.6, 0.0, look.z * 0.6),
				player.position(),
				player.position().add(-look.x * 0.6, 0.0, -look.z * 0.6),
				player.position().add(look.z * 0.6, 0.0, -look.x * 0.6),
				player.position().add(-look.z * 0.6, 0.0, look.x * 0.6),
		};

		float width = Math.max(0.6F, pokemon.getForm().getHitbox().width());
		float height = Math.max(0.8F, pokemon.getForm().getHitbox().height());

		for (Vec3 candidate : candidates) {
			Vec3 snapped = snapToGround(level, candidate, 4);
			if (snapped != null && isSpaceFree(level, snapped, width, height)) {
				return snapped;
			}
		}

		// Last resort: player feet
		return player.position();
	}

	private static boolean hasNearbySolidGround(ServerPlayer player, int radius) {
		BlockPos origin = player.blockPosition();
		ServerLevel level = player.serverLevel();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				BlockPos below = origin.offset(dx, -1, dz);
				if (level.getBlockState(below).isSolidRender(level, below)) {
					return true;
				}
			}
		}
		return player.onGround();
	}

	private static boolean hasNearbyWater(ServerPlayer player, int radius) {
		BlockPos origin = player.blockPosition();
		ServerLevel level = player.serverLevel();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (level.getFluidState(origin.offset(dx, dy, dz)).is(FluidTags.WATER)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean hasVerticalClearance(ServerPlayer player, int blocks) {
		BlockPos origin = player.blockPosition();
		ServerLevel level = player.serverLevel();
		for (int y = 1; y <= blocks; y++) {
			BlockPos up = origin.above(y);
			BlockState state = level.getBlockState(up);
			if (state.isSolidRender(level, up)) {
				return false;
			}
		}
		return true;
	}

	@Nullable
	private static Vec3 snapToGround(ServerLevel level, Vec3 pos, int maxDrop) {
		BlockPos.MutableBlockPos cursor = BlockPos.containing(pos).mutable();
		// If inside a block, move up a bit
		for (int up = 0; up < 3; up++) {
			if (!level.getBlockState(cursor).isSolidRender(level, cursor)) {
				break;
			}
			cursor.move(0, 1, 0);
		}
		// Drop down to floor
		for (int drop = 0; drop <= maxDrop; drop++) {
			BlockPos below = cursor.below();
			if (level.getBlockState(below).isSolidRender(level, below)
					|| level.getFluidState(cursor).is(FluidTags.WATER)) {
				return new Vec3(pos.x, cursor.getY(), pos.z);
			}
			cursor.move(0, -1, 0);
		}
		return null;
	}

	private static boolean isSpaceFree(ServerLevel level, Vec3 pos, float width, float height) {
		AABB box = new AABB(
				pos.x - width / 2.0, pos.y, pos.z - width / 2.0,
				pos.x + width / 2.0, pos.y + height, pos.z + width / 2.0
		);
		return level.noCollision(box);
	}
}
