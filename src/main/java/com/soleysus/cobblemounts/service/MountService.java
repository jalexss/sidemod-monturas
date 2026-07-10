package com.soleysus.cobblemounts.service;

import com.cobblemon.mod.common.api.riding.RidingProperties;
import com.cobblemon.mod.common.api.riding.RidingStyle;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.activestate.ActivePokemonState;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.soleysus.cobblemounts.CobbleMounts;
import com.soleysus.cobblemounts.MountStyle;
import com.soleysus.cobblemounts.network.MountNetworking;
import com.soleysus.cobblemounts.network.payload.MountSyncPayload;
import com.soleysus.cobblemounts.storage.MountBankStore;
import com.soleysus.cobblemounts.storage.MountStores;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import kotlin.Unit;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side mount assignment / summon logic.
 * <p>
 * Assigned Pokémon stay in the PC (soft UUID references). Party mons are never candidates.
 */
public final class MountService {
	public static final String MOUNT_TAG = "cobble_mounts:active_mount";

	private static final Map<UUID, UUID> ACTIVE_MOUNT_ENTITIES = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> MOUNT_WAS_RIDING = new ConcurrentHashMap<>();
	/** player → pokemon UUID that had mega form applied for the active mount */
	private static final Map<UUID, MegaRestore> ACTIVE_MEGA_RESTORE = new ConcurrentHashMap<>();
	private static final List<DelayedTask> DELAYED_TASKS = new CopyOnWriteArrayList<>();
	private static final int SUMMON_GRACE_TICKS = 100;

	/** Species showdown-style names for TELEPORT (Abra + Ralts lines). */
	private static final Set<String> TELEPORT_SPECIES = Set.of(
			"abra", "kadabra", "alakazam",
			"ralts", "kirlia", "gardevoir", "gallade"
	);

	private MountService() {
	}

	public static boolean supportsStyle(Pokemon pokemon, MountStyle style) {
		if (style == MountStyle.TELEPORT) {
			return isTeleportSpecies(pokemon) && WaystoneBridge.isAvailable();
		}
		RidingStyle riding = style.toRidingStyle();
		if (riding == null) {
			return false;
		}
		RidingProperties props = pokemon.getRiding();
		if (props == null || props.getBehaviours() == null) {
			return false;
		}
		return props.getBehaviours().containsKey(riding);
	}

	public static List<MountStyle> supportedStyles(Pokemon pokemon) {
		List<MountStyle> styles = new ArrayList<>();
		for (MountStyle style : MountStyle.values()) {
			if (supportsStyle(pokemon, style)) {
				styles.add(style);
			}
		}
		return styles;
	}

	public static boolean isTeleportSpecies(Pokemon pokemon) {
		String key = normalizeSpecies(pokemon);
		return TELEPORT_SPECIES.contains(key);
	}

	private static String normalizeSpecies(Pokemon pokemon) {
		String name = pokemon.getSpecies().getName();
		return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private static boolean hasConfiguredSeats(Pokemon pokemon) {
		RidingProperties riding = pokemon.getRiding();
		return riding != null && riding.getSeats() != null && !riding.getSeats().isEmpty();
	}

	/**
	 * Assigns a PC Pokémon into a mount style slot without removing it from the PC.
	 * <p>
	 * If the Pokémon supports multiple mount styles (e.g. Charizard → LAND + AIR),
	 * it is also placed in a free slot of each other supported style. The same UUID
	 * is shared, so unassigning from any style removes it from all of them.
	 */
	public static Result assign(ServerPlayer player, MountStyle style, int slot, UUID pokemonId) {
		if (slot < 0 || slot >= MountBankStore.SLOTS_PER_STYLE) {
			return Result.failKey("message.cobble_mounts.invalid_slot");
		}
		if (style == MountStyle.TELEPORT && !WaystoneBridge.isAvailable()) {
			return Result.failKey("message.cobble_mounts.teleport_requires_waystones");
		}

		// Party mons cannot be assigned
		if (isInParty(player, pokemonId)) {
			return Result.failKey("message.cobble_mounts.party_not_allowed");
		}

		Pokemon pokemon = findPcOrBankPokemon(player, pokemonId);
		if (pokemon == null) {
			return Result.failKey("message.cobble_mounts.not_in_pc");
		}

		if (!supportsStyle(pokemon, style)) {
			if (style == MountStyle.TELEPORT) {
				return Result.failKey("message.cobble_mounts.teleport_species_only");
			}
			return Result.failKey("message.cobble_mounts.unsupported_style", styleName(style));
		}

		MountBankStore bank = MountStores.get(player);
		UUID uuid = pokemon.getUuid();

		// Place in the chosen style/slot (drop any prior slot of the same style for this mon)
		bank.clearStyleReferences(style, uuid);
		bank.setSlot(style, slot, uuid);

		List<Component> placements = new ArrayList<>();
		placements.add(placementLabel(style, slot));

		List<Component> skippedFull = new ArrayList<>();
		for (MountStyle other : supportedStyles(pokemon)) {
			if (other == style) {
				continue;
			}
			if (other == MountStyle.TELEPORT && !WaystoneBridge.isAvailable()) {
				continue;
			}
			int existing = bank.indexOf(other, uuid);
			if (existing >= 0) {
				placements.add(placementLabel(other, existing));
				continue;
			}
			int empty = bank.firstEmptySlot(other);
			if (empty < 0) {
				skippedFull.add(styleName(other));
				continue;
			}
			bank.setSlot(other, empty, uuid);
			placements.add(placementLabel(other, empty));
		}

		// Legacy: if mon was sitting in bank, migrate back to PC while keeping soft-ref
		migrateBankMonToPcIfNeeded(player, bank, pokemon);

		syncTo(player);

		Component placeText = joinComponents(placements);
		if (skippedFull.isEmpty()) {
			return Result.okKey("message.cobble_mounts.assigned", displayNameComponent(pokemon), placeText);
		}
		return Result.okKey("message.cobble_mounts.assigned_partial",
				displayNameComponent(pokemon), placeText, joinComponents(skippedFull));
	}

	/**
	 * Unassigns the Pokémon in the given slot from <em>all</em> mount styles.
	 * Multi-type mounts (same UUID in LAND + AIR, etc.) are removed together.
	 */
	public static Result unassign(ServerPlayer player, MountStyle style, int slot) {
		if (slot < 0 || slot >= MountBankStore.SLOTS_PER_STYLE) {
			return Result.failKey("message.cobble_mounts.invalid_slot");
		}
		MountBankStore bank = MountStores.get(player);
		UUID id = bank.getSlotUuid(style, slot);
		if (id == null) {
			return Result.failKey("message.cobble_mounts.slot_empty");
		}

		Pokemon pokemon = resolvePokemon(player, bank, id);
		if (pokemon != null && pokemon.getEntity() != null) {
			PokemonEntity entity = pokemon.getEntity();
			if (player.getVehicle() == entity) {
				player.stopRiding();
			}
			safeRecall(entity, pokemon);
			clearTracking(player.getUUID());
		}

		// Same mon can occupy LAND + AIR etc.; clear every style together
		bank.clearAllReferences(id);

		// Legacy bank occupancy
		if (pokemon != null && !bank.isReferenced(id) && bank.getByUuid(id) != null) {
			maybeReturnToPc(player, bank, id);
		}
		syncTo(player);
		if (pokemon != null) {
			return Result.okKey("message.cobble_mounts.unassigned", displayNameComponent(pokemon));
		}
		return Result.okKey("message.cobble_mounts.unassigned_fallback");
	}

	/** Dismount + recall active mount without unassigning the slot. */
	public static Result dismount(ServerPlayer player) {
		if (player.getVehicle() == null && !ACTIVE_MOUNT_ENTITIES.containsKey(player.getUUID())) {
			return Result.failKey("message.cobble_mounts.not_mounted");
		}
		dismountAndRecallActive(player);
		return Result.okKey("message.cobble_mounts.dismounted");
	}

	public static Result setMegaMode(ServerPlayer player, MountStyle style, int slot, boolean enabled) {
		if (slot < 0 || slot >= MountBankStore.SLOTS_PER_STYLE) {
			return Result.failKey("message.cobble_mounts.invalid_slot");
		}
		MountBankStore bank = MountStores.get(player);
		UUID id = bank.getSlotUuid(style, slot);
		if (id == null) {
			return Result.failKey("message.cobble_mounts.slot_empty");
		}
		Pokemon pokemon = resolvePokemon(player, bank, id);
		if (pokemon == null) {
			return Result.failKey("message.cobble_mounts.slot_pokemon_missing");
		}
		if (enabled && !MegaStoneHelper.hasMatchingMegaStone(pokemon)) {
			return Result.failKey("message.cobble_mounts.no_mega_stone");
		}
		bank.setMegaEnabled(style, slot, enabled);
		syncTo(player);
		return Result.okKey(enabled
						? "message.cobble_mounts.mega_enabled"
						: "message.cobble_mounts.mega_disabled",
				displayNameComponent(pokemon));
	}

	/**
	 * Summons the mount, opens Waystones for TELEPORT, or rides a land/liquid/air mount.
	 */
	public static Result summonAndRide(ServerPlayer player, MountStyle style, int slot) {
		if (slot < 0 || slot >= MountBankStore.SLOTS_PER_STYLE) {
			return Result.failKey("message.cobble_mounts.invalid_slot");
		}

		MountBankStore bank = MountStores.get(player);
		UUID id = bank.getSlotUuid(style, slot);
		if (id == null) {
			return Result.failKey("message.cobble_mounts.no_mount_in_slot", styleName(style), slot + 1);
		}
		Pokemon pokemon = resolvePokemon(player, bank, id);
		if (pokemon == null) {
			return Result.failKey("message.cobble_mounts.assigned_missing");
		}
		if (pokemon.isFainted()) {
			return Result.failKey("message.cobble_mounts.fainted");
		}
		if (!supportsStyle(pokemon, style)) {
			return Result.failKey("message.cobble_mounts.no_longer_supports", styleName(style));
		}

		if (style == MountStyle.TELEPORT) {
			return summonTeleport(player, pokemon);
		}

		if (player.getVehicle() != null) {
			Entity vehicle = player.getVehicle();
			if (vehicle instanceof PokemonEntity pe && pe.getTags().contains(MOUNT_TAG)) {
				dismountAndRecallActive(player);
			} else {
				return Result.failKey("message.cobble_mounts.already_mounted");
			}
		}

		RidingStyle ridingStyle = style.toRidingStyle();
		if (ridingStyle == null) {
			return Result.failKey("message.cobble_mounts.invalid_style");
		}
		if (!hasConfiguredSeats(pokemon)) {
			return Result.failKey("message.cobble_mounts.no_seats", displayNameComponent(pokemon));
		}

		Result env = MountSpawnHelper.validateEnvironment(player, ridingStyle);
		if (!env.success()) {
			return env;
		}

		// Mega form for this summon
		String megaRestore = null;
		if (bank.isMegaEnabled(style, slot)) {
			Optional<String> aspect = MegaStoneHelper.matchingMegaAspect(pokemon);
			if (aspect.isEmpty()) {
				bank.setMegaEnabled(style, slot, false);
				syncTo(player);
				return Result.failKey("message.cobble_mounts.mega_disabled_missing_stone");
			}
			megaRestore = MegaStoneHelper.applyMegaForm(pokemon, aspect.get());
			ACTIVE_MEGA_RESTORE.put(player.getUUID(), new MegaRestore(pokemon.getUuid(), megaRestore));
		}

		recallOtherActiveMounts(player, pokemon.getUuid());

		if (pokemon.getState() instanceof ActivePokemonState active && active.getEntity() != null) {
			PokemonEntity entity = active.getEntity();
			prepareOwnedMountEntity(entity, player);
			pullBesidePlayer(entity, player, pokemon);
			scheduleMountAttempts(player, entity, pokemon, 3);
			return Result.okKey("message.cobble_mounts.mounting", displayNameComponent(pokemon), styleName(style));
		}

		Vec3 sendPos = MountSpawnHelper.findMountSpawnPos(player, pokemon);
		try {
			pokemon.sendOutWithAnimation(
					player,
					player.serverLevel(),
					sendPos,
					null,
					true,
					null,
					e -> {
						e.setOwnerUUID(player.getUUID());
						e.addTag(MOUNT_TAG);
						e.setPersistenceRequired();
						return Unit.INSTANCE;
					}
			).whenComplete((entity, error) -> {
				MinecraftServer server = player.getServer();
				if (server == null) {
					return;
				}
				server.execute(() -> {
					if (error != null) {
						CobbleMounts.LOGGER.error("Send-out failed for mount {}", pokemon.getUuid(), error);
						restoreMegaIfNeeded(player, pokemon);
						chat(player, Component.translatable("message.cobble_mounts.summon_failed",
								error.getMessage() == null ? "?" : error.getMessage()));
						return;
					}
					if (entity == null || !entity.isAlive() || player.isRemoved()) {
						restoreMegaIfNeeded(player, pokemon);
						chat(player, Component.translatable("message.cobble_mounts.summon_missing"));
						return;
					}
					prepareOwnedMountEntity(entity, player);
					pullBesidePlayer(entity, player, pokemon);
					scheduleMountAttempts(player, entity, pokemon, 4);
				});
			});
		} catch (Exception ex) {
			CobbleMounts.LOGGER.error("Exception summoning mount", ex);
			restoreMegaIfNeeded(player, pokemon);
			return Result.failKey("message.cobble_mounts.summon_error",
					ex.getMessage() == null ? "?" : ex.getMessage());
		}

		return Result.okKey("message.cobble_mounts.mounting", displayNameComponent(pokemon), styleName(style));
	}

	private static Result summonTeleport(ServerPlayer player, Pokemon pokemon) {
		if (!WaystoneBridge.isAvailable()) {
			return Result.failKey("message.cobble_mounts.waystones_missing");
		}
		if (!isTeleportSpecies(pokemon)) {
			return Result.failKey("message.cobble_mounts.teleport_species_only");
		}
		// Optional: brief send-out for flavor, then open waystone UI
		boolean opened = WaystoneBridge.openSelectionMenu(player);
		if (!opened) {
			return Result.failKey("message.cobble_mounts.waystones_open_failed");
		}
		return Result.okKey("message.cobble_mounts.teleport_help", displayNameComponent(pokemon));
	}

	private static void prepareOwnedMountEntity(PokemonEntity entity, ServerPlayer player) {
		entity.setOwnerUUID(player.getUUID());
		entity.setBeamMode(0);
		entity.setPhasingTargetId(-1);
		entity.addTag(MOUNT_TAG);
		entity.setPersistenceRequired();
	}

	private static void pullBesidePlayer(PokemonEntity entity, ServerPlayer player, Pokemon pokemon) {
		Vec3 pos = MountSpawnHelper.findMountSpawnPos(player, pokemon);
		entity.teleportTo(pos.x, pos.y, pos.z);
		entity.setYRot(player.getYRot());
		entity.setXRot(0);
		entity.setDeltaMovement(Vec3.ZERO);
		entity.fallDistance = 0;
	}

	private static void scheduleMountAttempts(ServerPlayer player, PokemonEntity entity, Pokemon pokemon, int initialDelayTicks) {
		ACTIVE_MOUNT_ENTITIES.put(player.getUUID(), entity.getUUID());
		MOUNT_WAS_RIDING.put(player.getUUID(), false);
		attemptMountLater(player, entity, pokemon, initialDelayTicks, 15);
	}

	private static void markRiding(ServerPlayer player, PokemonEntity entity) {
		ACTIVE_MOUNT_ENTITIES.put(player.getUUID(), entity.getUUID());
		MOUNT_WAS_RIDING.put(player.getUUID(), true);
	}

	private static void clearTracking(UUID playerId) {
		ACTIVE_MOUNT_ENTITIES.remove(playerId);
		MOUNT_WAS_RIDING.remove(playerId);
	}

	private static void restoreMegaIfNeeded(ServerPlayer player, @Nullable Pokemon pokemon) {
		MegaRestore restore = ACTIVE_MEGA_RESTORE.remove(player.getUUID());
		if (restore == null) {
			return;
		}
		Pokemon target = pokemon;
		if (target == null || !target.getUuid().equals(restore.pokemonId())) {
			MountBankStore bank = MountStores.get(player);
			target = resolvePokemon(player, bank, restore.pokemonId());
		}
		if (target != null) {
			MegaStoneHelper.restoreMegaForm(target, restore.previousToken());
		}
	}

	private static void attemptMountLater(
			ServerPlayer player,
			PokemonEntity entity,
			Pokemon pokemon,
			int delayTicks,
			int remainingAttempts
	) {
		if (remainingAttempts <= 0) {
			chat(player, Component.translatable("message.cobble_mounts.mount_failed_space",
					displayNameComponent(pokemon)));
			if (player.getVehicle() != entity && entity.isAlive()) {
				safeRecall(entity, pokemon);
				restoreMegaIfNeeded(player, pokemon);
				clearTracking(player.getUUID());
			}
			return;
		}

		scheduleAfterTicks(Math.max(1, delayTicks), () -> {
			if (player.isRemoved() || !entity.isAlive()) {
				restoreMegaIfNeeded(player, pokemon);
				clearTracking(player.getUUID());
				return;
			}
			if (player.getVehicle() != null && player.getVehicle() != entity) {
				return;
			}
			if (player.getVehicle() == entity) {
				if (!isPassengerInSeats(entity, player)) {
					player.stopRiding();
					prepareOwnedMountEntity(entity, player);
					Result retry = tryMountNow(player, entity, pokemon);
					if (!retry.success()) {
						attemptMountLater(player, entity, pokemon, 2, remainingAttempts - 1);
					} else {
						markRiding(player, entity);
						chat(player, retry.message());
					}
				} else {
					markRiding(player, entity);
				}
				return;
			}

			prepareOwnedMountEntity(entity, player);
			if (entity.getBeamMode() != 0) {
				entity.setBeamMode(0);
				entity.setPhasingTargetId(-1);
				attemptMountLater(player, entity, pokemon, 2, remainingAttempts - 1);
				return;
			}
			if (entity.getSeats().isEmpty()) {
				chat(player, Component.translatable("message.cobble_mounts.no_seats",
						displayNameComponent(pokemon)));
				safeRecall(entity, pokemon);
				restoreMegaIfNeeded(player, pokemon);
				clearTracking(player.getUUID());
				return;
			}

			Result ride = tryMountNow(player, entity, pokemon);
			if (ride.success()) {
				markRiding(player, entity);
				chat(player, ride.message());
			} else {
				attemptMountLater(player, entity, pokemon, 2, remainingAttempts - 1);
			}
		});
	}

	private static void scheduleAfterTicks(int ticks, Runnable task) {
		DELAYED_TASKS.add(new DelayedTask(ticks, task));
	}

	private static boolean isPassengerInSeats(PokemonEntity entity, Entity passenger) {
		Entity[] seats = entity.getOccupiedSeats();
		if (seats == null) {
			return false;
		}
		for (Entity seat : seats) {
			if (seat == passenger) {
				return true;
			}
		}
		return false;
	}

	private static Result tryMountNow(ServerPlayer player, PokemonEntity entity, Pokemon pokemon) {
		if (player.getVehicle() == entity) {
			return Result.okKey("message.cobble_mounts.already_on", displayNameComponent(pokemon));
		}
		if (entity.getBeamMode() != 0) {
			entity.setBeamMode(0);
			entity.setPhasingTargetId(-1);
		}
		if (entity.getSeats().isEmpty()) {
			return Result.failKey("message.cobble_mounts.no_seats", displayNameComponent(pokemon));
		}

		player.setShiftKeyDown(false);

		boolean ok = entity.tryRidingPokemon(player);
		if (!ok || player.getVehicle() != entity) {
			ok = player.startRiding(entity, false);
		}

		if (ok && player.getVehicle() == entity) {
			ensureSeatsMatchPassengers(entity);
			if (!isPassengerInSeats(entity, player)) {
				player.stopRiding();
				return Result.failKey("message.cobble_mounts.seat_not_ready");
			}
			return Result.okKey("message.cobble_mounts.mounted", displayNameComponent(pokemon));
		}
		return Result.failKey("message.cobble_mounts.mount_failed", displayNameComponent(pokemon));
	}

	private static void ensureSeatsMatchPassengers(PokemonEntity entity) {
		Entity[] seats = entity.getOccupiedSeats();
		if (seats == null || seats.length == 0) {
			return;
		}
		List<Entity> passengers = entity.getPassengers();
		boolean changed = false;
		for (Entity passenger : passengers) {
			if (isPassengerInSeats(entity, passenger)) {
				continue;
			}
			for (int i = 0; i < seats.length; i++) {
				if (seats[i] == null) {
					seats[i] = passenger;
					changed = true;
					break;
				}
			}
		}
		if (changed) {
			entity.setOccupiedSeats(seats);
		}
	}

	public static void tickActiveMounts(MinecraftServer server) {
		if (!DELAYED_TASKS.isEmpty()) {
			List<DelayedTask> due = new ArrayList<>();
			for (DelayedTask task : DELAYED_TASKS) {
				task.ticksRemaining--;
				if (task.ticksRemaining <= 0) {
					due.add(task);
				}
			}
			DELAYED_TASKS.removeAll(due);
			for (DelayedTask task : due) {
				try {
					task.runnable.run();
				} catch (Exception ex) {
					CobbleMounts.LOGGER.error("Error in delayed mount task", ex);
				}
			}
		}

		if (ACTIVE_MOUNT_ENTITIES.isEmpty()) {
			return;
		}

		Iterator<Map.Entry<UUID, UUID>> it = ACTIVE_MOUNT_ENTITIES.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, UUID> entry = it.next();
			UUID playerId = entry.getKey();
			UUID entityId = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				it.remove();
				MOUNT_WAS_RIDING.remove(playerId);
				ACTIVE_MEGA_RESTORE.remove(playerId);
				continue;
			}

			Entity vehicle = player.getVehicle();
			if (vehicle != null && vehicle.getUUID().equals(entityId)) {
				MOUNT_WAS_RIDING.put(playerId, true);
				if (vehicle instanceof PokemonEntity pe) {
					if (!player.getUUID().equals(pe.getOwnerUUID())) {
						pe.setOwnerUUID(player.getUUID());
					}
					if (!pe.getTags().contains(MOUNT_TAG)) {
						pe.addTag(MOUNT_TAG);
					}
				}
				continue;
			}

			Entity found = player.serverLevel().getEntity(entityId);
			if (found instanceof PokemonEntity pe && pe.isAlive()) {
				if (pe.getTags().contains(MOUNT_TAG) || isTrackedMountPokemon(player, pe.getPokemon())) {
					if (!pe.getPassengers().isEmpty()) {
						continue;
					}
					boolean wasRiding = Boolean.TRUE.equals(MOUNT_WAS_RIDING.get(playerId));
					if (wasRiding || pe.tickCount >= SUMMON_GRACE_TICKS) {
						safeRecall(pe, pe.getPokemon());
						restoreMegaIfNeeded(player, pe.getPokemon());
						it.remove();
						MOUNT_WAS_RIDING.remove(playerId);
					}
				} else {
					it.remove();
					MOUNT_WAS_RIDING.remove(playerId);
					ACTIVE_MEGA_RESTORE.remove(playerId);
				}
			} else {
				restoreMegaIfNeeded(player, null);
				it.remove();
				MOUNT_WAS_RIDING.remove(playerId);
			}
		}
	}

	private static boolean isTrackedMountPokemon(ServerPlayer player, Pokemon pokemon) {
		if (pokemon == null) {
			return false;
		}
		try {
			MountBankStore bank = MountStores.get(player);
			return bank.isReferenced(pokemon.getUuid());
		} catch (Exception ex) {
			return false;
		}
	}

	private static void dismountAndRecallActive(ServerPlayer player) {
		UUID entityId = ACTIVE_MOUNT_ENTITIES.remove(player.getUUID());
		MOUNT_WAS_RIDING.remove(player.getUUID());
		Entity vehicle = player.getVehicle();
		if (vehicle instanceof PokemonEntity pe) {
			player.stopRiding();
			if (pe.getTags().contains(MOUNT_TAG) || isTrackedMountPokemon(player, pe.getPokemon())) {
				safeRecall(pe, pe.getPokemon());
			}
			restoreMegaIfNeeded(player, pe.getPokemon());
			return;
		}
		if (entityId != null) {
			Entity found = player.serverLevel().getEntity(entityId);
			if (found instanceof PokemonEntity pe) {
				safeRecall(pe, pe.getPokemon());
				restoreMegaIfNeeded(player, pe.getPokemon());
			}
		} else {
			restoreMegaIfNeeded(player, null);
		}
	}

	private static void recallOtherActiveMounts(ServerPlayer player, UUID keepPokemonId) {
		UUID tracked = ACTIVE_MOUNT_ENTITIES.get(player.getUUID());
		if (tracked != null) {
			Entity e = player.serverLevel().getEntity(tracked);
			if (e instanceof PokemonEntity pe && pe.getPokemon() != null
					&& !pe.getPokemon().getUuid().equals(keepPokemonId)) {
				if (player.getVehicle() == pe) {
					player.stopRiding();
				}
				safeRecall(pe, pe.getPokemon());
				restoreMegaIfNeeded(player, pe.getPokemon());
				clearTracking(player.getUUID());
			}
		}
	}

	private static void safeRecall(PokemonEntity entity, @Nullable Pokemon pokemon) {
		try {
			List<Entity> riders = new ArrayList<>(entity.getPassengers());
			for (Entity rider : riders) {
				rider.stopRiding();
			}
			if (pokemon != null) {
				pokemon.tryRecallWithAnimation();
			} else {
				entity.recallWithAnimation();
			}
		} catch (Exception ex) {
			CobbleMounts.LOGGER.warn("Failed animated recall, discarding entity", ex);
			entity.discard();
			if (pokemon != null) {
				try {
					pokemon.recall();
				} catch (Exception ignored) {
				}
			}
		}
	}

	public static void syncTo(ServerPlayer player) {
		MountBankStore bank = MountStores.get(player);
		// Opportunistic migration of legacy bank mons back to PC
		migrateAllBankMonsToPc(player, bank);

		Map<MountStyle, List<MountSyncPayload.SlotInfo>> map = new EnumMap<>(MountStyle.class);
		Set<UUID> assigned = bank.allAssignedUuids();

		for (MountStyle style : MountStyle.values()) {
			List<MountSyncPayload.SlotInfo> list = new ArrayList<>(MountBankStore.SLOTS_PER_STYLE);
			for (int i = 0; i < MountBankStore.SLOTS_PER_STYLE; i++) {
				UUID id = bank.getSlotUuid(style, i);
				if (id == null) {
					list.add(null);
					continue;
				}
				Pokemon p = resolvePokemon(player, bank, id);
				if (p == null) {
					// Client translates displayName when species is "?"
					list.add(new MountSyncPayload.SlotInfo(
							id, "?", 0, "?", false, bank.isMegaEnabled(style, i)
					));
				} else {
					boolean canMega = MegaStoneHelper.hasMatchingMegaStone(p);
					list.add(new MountSyncPayload.SlotInfo(
							p.getUuid(),
							p.getSpecies().getName(),
							p.getLevel(),
							displayName(p),
							canMega,
							bank.isMegaEnabled(style, i) && canMega
					));
				}
			}
			map.put(style, list);
		}

		// Candidates: PC only (not party). Assigned ones stay listed but marked disabled.
		List<MountSyncPayload.CandidateInfo> candidates = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		PCStore pc = PlayerExtensionsKt.pc(player);
		for (Pokemon p : pc) {
			if (p == null) {
				continue;
			}
			if (!seen.add(p.getUuid())) {
				continue;
			}
			List<MountStyle> styles = supportedStyles(p);
			// Always include assigned mons even if they temporarily lose styles, so UI can show them
			boolean isAssigned = assigned.contains(p.getUuid());
			if (styles.isEmpty() && !isAssigned) {
				continue;
			}
			candidates.add(toCandidate(p, "pc", isAssigned));
		}
		// Legacy bank mons (should be rare after migration)
		for (Pokemon p : bank.getBank()) {
			if (!seen.add(p.getUuid())) {
				continue;
			}
			if (supportedStyles(p).isEmpty() && !assigned.contains(p.getUuid())) {
				continue;
			}
			candidates.add(toCandidate(p, "mount", assigned.contains(p.getUuid())));
		}

		boolean waystones = WaystoneBridge.isAvailable();
		boolean riding = player.getVehicle() instanceof PokemonEntity pe && pe.getTags().contains(MOUNT_TAG)
				|| ACTIVE_MOUNT_ENTITIES.containsKey(player.getUUID());

		MountNetworking.sendToPlayer(player, new MountSyncPayload(map, candidates, waystones, riding));
	}

	private static MountSyncPayload.CandidateInfo toCandidate(Pokemon p, String source, boolean assigned) {
		List<String> styles = new ArrayList<>();
		for (MountStyle style : supportedStyles(p)) {
			styles.add(style.name());
		}
		String speciesName = p.getSpecies().getName();
		String translated = p.getSpecies().getTranslatedName().getString();
		String nickname = p.getNickname() != null ? p.getNickname().getString() : "";
		return new MountSyncPayload.CandidateInfo(
				p.getUuid(),
				speciesName,
				p.getLevel(),
				displayName(p),
				source,
				styles,
				assigned,
				MegaStoneHelper.hasMatchingMegaStone(p),
				speciesName,
				translated,
				nickname
		);
	}

	private static boolean isInParty(ServerPlayer player, UUID pokemonId) {
		for (Pokemon p : PlayerExtensionsKt.party(player)) {
			if (p != null && p.getUuid().equals(pokemonId)) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	private static Pokemon findPcOrBankPokemon(ServerPlayer player, UUID pokemonId) {
		PCStore pc = PlayerExtensionsKt.pc(player);
		Pokemon fromPc = pc.get(pokemonId);
		if (fromPc != null) {
			return fromPc;
		}
		return MountStores.get(player).getByUuid(pokemonId);
	}

	@Nullable
	public static Pokemon resolvePokemon(ServerPlayer player, MountBankStore bank, UUID pokemonId) {
		Pokemon p = PlayerExtensionsKt.pc(player).get(pokemonId);
		if (p != null) {
			return p;
		}
		p = bank.getByUuid(pokemonId);
		if (p != null) {
			return p;
		}
		// Last resort: party (show slot even if moved to party by player)
		return PlayerExtensionsKt.party(player).get(pokemonId);
	}

	private static void migrateBankMonToPcIfNeeded(ServerPlayer player, MountBankStore bank, Pokemon pokemon) {
		if (bank.getByUuid(pokemon.getUuid()) == null) {
			return;
		}
		if (pokemon.getEntity() != null) {
			safeRecall(pokemon.getEntity(), pokemon);
		}
		if (!bank.remove(pokemon)) {
			return;
		}
		PCStore pc = PlayerExtensionsKt.pc(player);
		if (!pc.add(pokemon)) {
			bank.add(pokemon);
			chat(player, Component.translatable("message.cobble_mounts.pc_full_return",
					displayNameComponent(pokemon)));
		}
	}

	private static void migrateAllBankMonsToPc(ServerPlayer player, MountBankStore bank) {
		List<Pokemon> copy = new ArrayList<>(bank.getBank());
		for (Pokemon p : copy) {
			migrateBankMonToPcIfNeeded(player, bank, p);
		}
	}

	private static boolean maybeReturnToPc(ServerPlayer player, MountBankStore bank, UUID pokemonId) {
		if (bank.isReferenced(pokemonId)) {
			return false;
		}
		Pokemon pokemon = bank.getByUuid(pokemonId);
		if (pokemon == null) {
			return false;
		}
		if (pokemon.getEntity() != null) {
			safeRecall(pokemon.getEntity(), pokemon);
		}
		if (!bank.remove(pokemon)) {
			return false;
		}
		PCStore pc = PlayerExtensionsKt.pc(player);
		if (!pc.add(pokemon)) {
			bank.add(pokemon);
			chat(player, Component.translatable("message.cobble_mounts.pc_full_bank"));
			return false;
		}
		return true;
	}

	/** Resolved string for network UI payloads (already-localized names where possible). */
	private static String displayName(Pokemon pokemon) {
		if (pokemon.getNickname() != null) {
			return pokemon.getNickname().getString();
		}
		return pokemon.getSpecies().getTranslatedName().getString();
	}

	/** Component form so species names resolve in the player's language. */
	private static Component displayNameComponent(Pokemon pokemon) {
		if (pokemon.getNickname() != null) {
			return pokemon.getNickname().copy();
		}
		return pokemon.getSpecies().getTranslatedName();
	}

	private static Component styleName(MountStyle style) {
		return Component.translatable(style.displayKey());
	}

	private static Component placementLabel(MountStyle style, int slotIndex0) {
		return Component.translatable("message.cobble_mounts.placement", styleName(style), slotIndex0 + 1);
	}

	private static Component joinComponents(List<Component> parts) {
		MutableComponent out = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				out.append(Component.literal(", "));
			}
			out.append(parts.get(i));
		}
		return out;
	}

	/** Sends a chat line with the mod prefix; body is translated on the client. */
	public static void chat(ServerPlayer player, Component body) {
		player.sendSystemMessage(Component.translatable("message.cobble_mounts.chat", body));
	}

	public static void chat(ServerPlayer player, Result result) {
		chat(player, result.message());
	}

	private static final class DelayedTask {
		int ticksRemaining;
		final Runnable runnable;

		DelayedTask(int ticksRemaining, Runnable runnable) {
			this.ticksRemaining = ticksRemaining;
			this.runnable = runnable;
		}
	}

	private record MegaRestore(UUID pokemonId, String previousToken) {
	}

	/**
	 * Operation outcome. {@link #message()} is a {@link Component} (usually translatable)
	 * so the client renders it in the player's language.
	 */
	public record Result(boolean success, Component message) {
		public static Result ok() {
			return new Result(true, Component.empty());
		}

		public static Result okKey(String key, Object... args) {
			return new Result(true, Component.translatable(key, args));
		}

		public static Result failKey(String key, Object... args) {
			return new Result(false, Component.translatable(key, args));
		}

		public static Result ok(Component message) {
			return new Result(true, message);
		}

		public static Result fail(Component message) {
			return new Result(false, message);
		}
	}
}
