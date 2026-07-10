package com.soleysus.cobblemounts.client.gui;

import com.soleysus.cobblemounts.MountStyle;
import com.soleysus.cobblemounts.network.payload.MountActionPayload;
import com.soleysus.cobblemounts.network.payload.MountSyncPayload;
import com.soleysus.cobblemounts.storage.MountBankStore;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Mount manager UI: styles, slots, search, pagination, dismount, mega.
 */
public class MountMenuScreen extends Screen {
	private static final int PAGE_SIZE = 6;

	private MountStyle selectedStyle = MountStyle.LAND;
	private int selectedSlot = 0;
	private int candidatePage = 0;
	private String searchQuery = "";
	@Nullable
	private EditBox searchBox;
	@Nullable
	private UUID pendingAssignPokemon;
	/** When true, next search responder ignores rebuild (avoids recursion). */
	private boolean suppressSearchRebuild;

	public MountMenuScreen() {
		super(Component.translatable("screen.cobble_mounts.menu"));
	}

	@Override
	protected void init() {
		rebuild();
	}

	public void refreshFromState() {
		if (searchBox != null) {
			searchQuery = searchBox.getValue();
		}
		rebuild();
	}

	private void rebuild() {
		clearWidgets();

		int left = this.width / 2 - 170;
		int top = 24;

		int x = left;
		for (MountStyle style : MountStyle.values()) {
			final MountStyle s = style;
			boolean selected = s == selectedStyle;
			String label = styleLabel(style);
			if (selected) {
				label = "§a" + label;
			} else if (style == MountStyle.TELEPORT && !MountMenuState.isWaystonesPresent()) {
				label = "§8" + label;
			}
			int w = style == MountStyle.TELEPORT ? 90 : 62;
			addRenderableWidget(Button.builder(Component.literal(label), b -> {
				selectedStyle = s;
				selectedSlot = 0;
				candidatePage = 0;
				pendingAssignPokemon = null;
				rebuild();
			}).bounds(x, top, w, 20).build());
			x += w + 4;
		}

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(this.width / 2 + 110, top, 50, 20).build());

		int slotY = top + 36;
		for (int i = 0; i < MountBankStore.SLOTS_PER_STYLE; i++) {
			final int slot = i;
			MountSyncPayload.SlotInfo info = MountMenuState.slot(selectedStyle, i);
			String label;
			if (info == null) {
				label = Component.translatable("screen.cobble_mounts.slot_empty", i + 1).getString();
			} else {
				String monName = isMissingSlot(info)
						? Component.translatable("screen.cobble_mounts.lost").getString()
						: info.displayName();
				label = Component.translatable("screen.cobble_mounts.slot_filled", i + 1, monName, info.level())
						.getString();
				if (info.megaEnabled()) {
					label += " §d[MEGA]";
				}
			}
			if (selectedSlot == i) {
				label = "§e> " + label;
			}
			addRenderableWidget(Button.builder(Component.literal(label), b -> {
				selectedSlot = slot;
				rebuild();
			}).bounds(left, slotY + i * 22, 220, 20).build());
		}

		int actionY = slotY + MountBankStore.SLOTS_PER_STYLE * 22 + 6;
		MountSyncPayload.SlotInfo selected = MountMenuState.slot(selectedStyle, selectedSlot);
		int actionX = left;
		if (selected != null) {
			String summonLabel = selectedStyle == MountStyle.TELEPORT
					? Component.translatable("screen.cobble_mounts.teleport").getString()
					: Component.translatable("screen.cobble_mounts.summon").getString();
			addRenderableWidget(Button.builder(Component.literal(summonLabel), b -> {
				ClientPlayNetworking.send(new MountActionPayload(
						MountActionPayload.Action.SUMMON,
						selectedStyle.name(),
						selectedSlot,
						null
				));
				if (selectedStyle != MountStyle.TELEPORT) {
					onClose();
				}
			}).bounds(actionX, actionY, 100, 20).build());
			actionX += 104;

			// Dismount sits beside Ride/Teleport, only while mounted
			if (MountMenuState.isCurrentlyMounted()) {
				addRenderableWidget(Button.builder(Component.translatable("screen.cobble_mounts.dismount"), b ->
						ClientPlayNetworking.send(new MountActionPayload(
								MountActionPayload.Action.DISMOUNT,
								selectedStyle.name(),
								selectedSlot,
								null
						))
				).bounds(actionX, actionY, 80, 20).build());
				actionX += 84;
			}

			addRenderableWidget(Button.builder(Component.translatable("screen.cobble_mounts.unassign"), b ->
					ClientPlayNetworking.send(new MountActionPayload(
							MountActionPayload.Action.UNASSIGN,
							selectedStyle.name(),
							selectedSlot,
							null
					))
			).bounds(actionX, actionY, 80, 20).build());
			actionX += 84;

			boolean canMega = selected.canMega();
			boolean megaOn = selected.megaEnabled();
			String megaLabel = megaOn
					? Component.translatable("screen.cobble_mounts.mega_on").getString()
					: Component.translatable("screen.cobble_mounts.mega_off").getString();
			Button megaBtn = Button.builder(Component.literal(megaLabel), b ->
					ClientPlayNetworking.send(new MountActionPayload(
							MountActionPayload.Action.SET_MEGA,
							selectedStyle.name(),
							selectedSlot,
							null,
							!megaOn
					))
			).bounds(actionX, actionY, 90, 20).build();
			megaBtn.active = canMega && selectedStyle.isRideable();
			addRenderableWidget(megaBtn);
		} else if (MountMenuState.isCurrentlyMounted()) {
			// Empty slot selected, but player is still riding — allow dismount
			addRenderableWidget(Button.builder(Component.translatable("screen.cobble_mounts.dismount"), b ->
					ClientPlayNetworking.send(new MountActionPayload(
							MountActionPayload.Action.DISMOUNT,
							selectedStyle.name(),
							selectedSlot,
							null
					))
			).bounds(left, actionY, 100, 20).build());
		}

		int searchY = actionY + 28;
		searchBox = new EditBox(this.font, left, searchY, 220, 18,
				Component.translatable("screen.cobble_mounts.search"));
		searchBox.setMaxLength(48);
		suppressSearchRebuild = true;
		searchBox.setValue(searchQuery);
		suppressSearchRebuild = false;
		searchBox.setHint(Component.translatable("screen.cobble_mounts.search_hint"));
		searchBox.setResponder(value -> {
			if (suppressSearchRebuild) {
				return;
			}
			searchQuery = value == null ? "" : value;
			candidatePage = 0;
			// Debounced full rebuild would steal focus; only re-filter on enter/page for simplicity.
			// Live filter: rebuild but restore focus to search
			boolean keepFocus = true;
			rebuild();
			if (keepFocus && searchBox != null) {
				setFocused(searchBox);
				searchBox.setFocused(true);
			}
		});
		addRenderableWidget(searchBox);

		addCandidateWidgets(left, searchY + 22);
	}

	private void addCandidateWidgets(int left, int candY) {
		List<MountSyncPayload.CandidateInfo> filtered = MountMenuState.candidatesFor(selectedStyle, searchQuery);
		int totalPages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		if (candidatePage >= totalPages) {
			candidatePage = totalPages - 1;
		}
		if (candidatePage < 0) {
			candidatePage = 0;
		}

		int from = candidatePage * PAGE_SIZE;
		int to = Math.min(from + PAGE_SIZE, filtered.size());
		int shown = 0;
		for (int i = from; i < to; i++) {
			final MountSyncPayload.CandidateInfo cand = filtered.get(i);
			// Assigned mons stay listed (and can fill other styles); PC UI is what locks them.
			String text = formatCandidate(cand, cand.assignedAsMount());
			Button btn = Button.builder(Component.literal(text), b -> {
				pendingAssignPokemon = cand.uuid();
				ClientPlayNetworking.send(new MountActionPayload(
						MountActionPayload.Action.ASSIGN,
						selectedStyle.name(),
						selectedSlot,
						cand.uuid()
				));
			}).bounds(left, candY + shown * 20, 340, 18).build();
			addRenderableWidget(btn);
			shown++;
		}

		int pageY = candY + PAGE_SIZE * 20 + 2;
		Button prev = Button.builder(Component.literal("◀"), b -> {
			if (candidatePage > 0) {
				candidatePage--;
				rebuild();
			}
		}).bounds(left, pageY, 30, 18).build();
		prev.active = candidatePage > 0;
		addRenderableWidget(prev);

		Button pageInfo = Button.builder(
				Component.literal((candidatePage + 1) + " / " + totalPages + "  (" + filtered.size() + ")"),
				b -> {
				}
		).bounds(left + 34, pageY, 110, 18).build();
		pageInfo.active = false;
		addRenderableWidget(pageInfo);

		Button next = Button.builder(Component.literal("▶"), b -> {
			if (candidatePage < totalPages - 1) {
				candidatePage++;
				rebuild();
			}
		}).bounds(left + 148, pageY, 30, 18).build();
		next.active = candidatePage < totalPages - 1;
		addRenderableWidget(next);
	}

	private String formatCandidate(MountSyncPayload.CandidateInfo c, boolean inUse) {
		String src = switch (c.source()) {
			case "party" -> Component.translatable("screen.cobble_mounts.source.party").getString();
			case "pc" -> Component.translatable("screen.cobble_mounts.source.pc").getString();
			case "mount" -> Component.translatable("screen.cobble_mounts.source.mount").getString();
			default -> c.source();
		};
		String text = c.displayName() + " §7Lv" + c.level() + " §8[" + src + "]";
		if (inUse) {
			text += " §c[" + Component.translatable("screen.cobble_mounts.in_use").getString() + "]";
		}
		if (c.hasMegaStone()) {
			text += " §d◆";
		}
		if (pendingAssignPokemon != null && pendingAssignPokemon.equals(c.uuid())) {
			text = "§a* " + text;
		}
		return text;
	}

	private static boolean isMissingSlot(MountSyncPayload.SlotInfo info) {
		return "?".equals(info.species()) || "?".equals(info.displayName());
	}

	private String styleLabel(MountStyle style) {
		return Component.translatable(style.displayKey()).getString();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

		int left = this.width / 2 - 170;
		int top = 24;
		int slotY = top + 36;
		int actionY = slotY + MountBankStore.SLOTS_PER_STYLE * 22 + 6;
		int candHeaderY = actionY + 16;

		graphics.drawString(this.font,
				Component.translatable("screen.cobble_mounts.selected_style", styleLabel(selectedStyle)).getString(),
				left, 48, 0xA0A0A0, false);
		graphics.drawString(this.font,
				Component.translatable("screen.cobble_mounts.candidates_header").getString(),
				left, candHeaderY, 0xA0A0A0, false);
		graphics.drawString(this.font,
				Component.translatable("screen.cobble_mounts.hint").getString(),
				left, this.height - 18, 0x808080, false);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY > 0 && candidatePage > 0) {
			candidatePage--;
			rebuild();
			return true;
		}
		if (scrollY < 0) {
			List<MountSyncPayload.CandidateInfo> filtered = MountMenuState.candidatesFor(selectedStyle, searchQuery);
			int totalPages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
			if (candidatePage < totalPages - 1) {
				candidatePage++;
				rebuild();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
