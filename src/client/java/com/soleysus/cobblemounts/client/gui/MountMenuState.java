package com.soleysus.cobblemounts.client.gui;

import com.soleysus.cobblemounts.MountStyle;
import com.soleysus.cobblemounts.network.payload.MountSyncPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side cache of mount bank data.
 */
public final class MountMenuState {
	private static Map<MountStyle, List<MountSyncPayload.SlotInfo>> slots = new EnumMap<>(MountStyle.class);
	private static List<MountSyncPayload.CandidateInfo> candidates = List.of();
	private static boolean waystonesPresent;
	private static boolean currentlyMounted;
	private static Set<UUID> assignedUuids = Set.of();

	private MountMenuState() {
	}

	public static void update(MountSyncPayload payload) {
		Map<MountStyle, List<MountSyncPayload.SlotInfo>> copy = new EnumMap<>(MountStyle.class);
		Set<UUID> assigned = new HashSet<>();
		for (var entry : payload.slots().entrySet()) {
			copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
			for (MountSyncPayload.SlotInfo info : entry.getValue()) {
				if (info != null) {
					assigned.add(info.uuid());
				}
			}
		}
		slots = copy;
		candidates = List.copyOf(payload.candidates());
		waystonesPresent = payload.waystonesPresent();
		currentlyMounted = payload.currentlyMounted();
		assignedUuids = Set.copyOf(assigned);
	}

	public static List<MountSyncPayload.SlotInfo> slotsFor(MountStyle style) {
		return slots.getOrDefault(style, Collections.emptyList());
	}

	@Nullable
	public static MountSyncPayload.SlotInfo slot(MountStyle style, int index) {
		List<MountSyncPayload.SlotInfo> list = slotsFor(style);
		if (index < 0 || index >= list.size()) {
			return null;
		}
		return list.get(index);
	}

	public static List<MountSyncPayload.CandidateInfo> candidates() {
		return candidates;
	}

	public static List<MountSyncPayload.CandidateInfo> candidatesFor(MountStyle style, String search) {
		String name = style.name();
		List<MountSyncPayload.CandidateInfo> out = new ArrayList<>();
		for (MountSyncPayload.CandidateInfo c : candidates) {
			if (!c.styles().contains(name)) {
				continue;
			}
			if (!c.matchesSearch(search)) {
				continue;
			}
			out.add(c);
		}
		return out;
	}

	public static boolean isWaystonesPresent() {
		return waystonesPresent;
	}

	public static boolean isCurrentlyMounted() {
		return currentlyMounted;
	}

	public static boolean isAssignedAsMount(UUID uuid) {
		return assignedUuids.contains(uuid);
	}

	public static Set<UUID> assignedUuids() {
		return assignedUuids;
	}
}
