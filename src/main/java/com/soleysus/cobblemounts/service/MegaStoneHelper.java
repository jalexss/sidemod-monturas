package com.soleysus.cobblemounts.service;

import com.cobblemon.mod.common.api.pokemon.feature.StringSpeciesFeature;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.helditem.CobblemonHeldItemManager;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Detects matching mega stones for a Pokémon and applies/restores mega aspects for mounts.
 * Works with vanilla Cobblemon mega forms and addon stones (Cobbleverse, etc.).
 */
public final class MegaStoneHelper {
	private MegaStoneHelper() {
	}

	/**
	 * @return mega aspect ({@code mega}, {@code mega_x}, {@code mega_y}) when the held item
	 * is the correct mega stone for <em>this</em> species' mega form (not any random stone).
	 */
	public static Optional<String> matchingMegaAspect(Pokemon pokemon) {
		ItemStack held = pokemon.heldItem();
		if (held.isEmpty()) {
			return Optional.empty();
		}

		String itemKey = normalize(BuiltInRegistries.ITEM.getKey(held.getItem()).getPath());
		String showdown = "";
		try {
			String sd = CobblemonHeldItemManager.INSTANCE.showdownId(held);
			if (sd != null) {
				showdown = normalize(sd);
			}
		} catch (Exception ignored) {
		}
		String descId = normalize(held.getDescriptionId());
		String hay = itemKey + "|" + showdown + "|" + descId;

		// Must resemble a mega stone / -ite item
		if (!(hay.contains("ite") || hay.contains("mega"))) {
			return Optional.empty();
		}

		Species species = pokemon.getSpecies();
		String speciesNorm = normalize(species.getName());
		String showdownSpecies = normalize(species.showdownId());

		for (FormData form : species.getForms()) {
			String aspect = megaAspectOf(form);
			if (aspect == null) {
				continue;
			}
			if (itemMatches(hay, speciesNorm, showdownSpecies, aspect)) {
				return Optional.of(aspect);
			}
		}
		return Optional.empty();
	}

	public static boolean hasMatchingMegaStone(Pokemon pokemon) {
		return matchingMegaAspect(pokemon).isPresent();
	}

	/**
	 * Applies mega form for the duration of a mount summon.
	 *
	 * @return opaque token to pass to {@link #restoreMegaForm(Pokemon, String)} (null if nothing applied)
	 */
	@Nullable
	public static String applyMegaForm(Pokemon pokemon, String megaAspect) {
		StringSpeciesFeature feature = pokemon.getFeature("mega_evolution");
		if (feature != null) {
			String previous = feature.getValue();
			feature.setValue(megaAspect);
			pokemon.markFeatureDirty(feature);
			pokemon.updateAspects();
			return previous == null ? "none" : previous;
		}
		Set<String> forced = new LinkedHashSet<>(pokemon.getForcedAspects());
		String previous = forced.stream().filter(a -> a.startsWith("mega")).findFirst().orElse("");
		forced.removeIf(a -> a.startsWith("mega"));
		forced.add(megaAspect);
		pokemon.setForcedAspects(forced);
		return previous.isEmpty() ? "" : previous;
	}

	public static void restoreMegaForm(Pokemon pokemon, @Nullable String previousToken) {
		if (previousToken == null) {
			return;
		}
		StringSpeciesFeature feature = pokemon.getFeature("mega_evolution");
		if (feature != null) {
			feature.setValue(previousToken.isEmpty() ? "none" : previousToken);
			pokemon.markFeatureDirty(feature);
			pokemon.updateAspects();
			return;
		}
		Set<String> forced = new LinkedHashSet<>(pokemon.getForcedAspects());
		forced.removeIf(a -> a.startsWith("mega"));
		if (!previousToken.isEmpty() && !previousToken.equals("none")) {
			forced.add(previousToken);
		}
		pokemon.setForcedAspects(forced);
	}

	@Nullable
	private static String megaAspectOf(FormData form) {
		boolean megaLabel = form.getLabels() != null && form.getLabels().contains("mega");
		for (String a : form.getAspects()) {
			if (a == null) {
				continue;
			}
			String lower = a.toLowerCase(Locale.ROOT);
			if (lower.equals("mega_x") || lower.equals("megax")) {
				return "mega_x";
			}
			if (lower.equals("mega_y") || lower.equals("megay")) {
				return "mega_y";
			}
			if (lower.equals("mega")) {
				return "mega";
			}
		}
		String name = form.getName() == null ? "" : form.getName().toLowerCase(Locale.ROOT);
		if (name.contains("mega-x") || name.contains("mega_x") || name.endsWith("-x")) {
			return "mega_x";
		}
		if (name.contains("mega-y") || name.contains("mega_y") || name.endsWith("-y")) {
			return "mega_y";
		}
		if (megaLabel || name.contains("mega")) {
			return "mega";
		}
		return null;
	}

	private static boolean itemMatches(String hay, String speciesNorm, String showdownSpecies, String aspect) {
		// Require species identity in the item id (correct stone, not any -ite)
		boolean hasSpecies = (!speciesNorm.isEmpty() && hay.contains(speciesNorm))
				|| (!showdownSpecies.isEmpty() && hay.contains(showdownSpecies));
		if (!hasSpecies) {
			// Oddball stones: latiasite, diancite, sablenite, manectite, absolite...
			// still need a strong fragment of the name (first 4+ chars)
			String prefix = speciesNorm.length() >= 4 ? speciesNorm.substring(0, 4) : speciesNorm;
			if (prefix.isEmpty() || !hay.contains(prefix)) {
				return false;
			}
		}

		boolean hasIte = hay.contains("ite") || hay.contains("mega" + speciesNorm) || hay.contains(speciesNorm + "mega");
		if (!hasIte) {
			return false;
		}

		boolean hasX = hay.contains("itex") || hay.contains("ite_x") || hay.contains("megax") || hay.contains("mega_x")
				|| (hay.contains("x") && (hay.contains("ite") || hay.contains("mega")));
		boolean hasY = hay.contains("itey") || hay.contains("ite_y") || hay.contains("megay") || hay.contains("mega_y")
				|| (hay.contains("y") && (hay.contains("ite") || hay.contains("mega")));

		return switch (aspect) {
			case "mega_x" -> hasX && !hasY;
			case "mega_y" -> hasY && !hasX;
			default -> !hasX && !hasY; // plain mega stone
		};
	}

	private static String normalize(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
