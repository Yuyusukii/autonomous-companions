package dev.autonomouscompanions.crafting;

import dev.autonomouscompanions.AutonomousCompanions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;

/**
 * Índice "item → receitas que o produzem", montado a partir do {@code RecipeManager} do servidor. Funciona com as
 * receitas efetivamente carregadas (jogo base, datapacks e mods). Reconstruído ao recarregar datapacks.
 */
public final class RecipeIndex {
	/** Receita de bancada. {@code slots} segue a grade da receita (largura x altura); vazio = espaço livre. */
	public record CraftingOption(RecipeHolder<CraftingRecipe> holder, int width, int height, List<Optional<Ingredient>> slots, Item output, int outputCount,
		boolean shaped) {
		public boolean needsTable() {
			return width > 2 || height > 2;
		}

		/** Ingredientes não vazios agrupados (ingrediente → quantidade por fabricação). */
		public List<IngredientCount> grouped() {
			List<IngredientCount> list = new ArrayList<>();
			outer:
			for (Optional<Ingredient> slot : slots) {
				if (slot.isEmpty()) {
					continue;
				}
				Ingredient ing = slot.get();
				for (int i = 0; i < list.size(); i++) {
					if (list.get(i).ingredient().equals(ing)) {
						list.set(i, new IngredientCount(ing, list.get(i).count() + 1));
						continue outer;
					}
				}
				list.add(new IngredientCount(ing, 1));
			}
			return list;
		}
	}

	public record IngredientCount(Ingredient ingredient, int count) {
		public Set<Item> items() {
			return RecipeIndex.items(ingredient);
		}
	}

	/** Receita de fornalha (fundição/cozimento). */
	public record CookingOption(RecipeHolder<? extends AbstractCookingRecipe> holder, Ingredient input, Item output, int outputCount, int cookTime) {
	}

	private static final Map<Item, List<CraftingOption>> CRAFTING = new HashMap<>();
	private static final Map<Item, List<CookingOption>> SMELTING = new HashMap<>();
	private static boolean built;

	private RecipeIndex() {
	}

	public static boolean isBuilt() {
		return built;
	}

	public static Set<Item> items(Ingredient ingredient) {
		Set<Item> set = new LinkedHashSet<>();
		ingredient.items().map(Holder::value).forEach(set::add);
		return set;
	}

	public static synchronized void rebuild(MinecraftServer server) {
		CRAFTING.clear();
		SMELTING.clear();
		ServerLevel level = server.overworld();
		int crafting = 0;
		int cooking = 0;
		for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
			Recipe<?> recipe = holder.value();
			try {
				if (recipe instanceof CraftingRecipe cr && !cr.isSpecial()) {
					@SuppressWarnings("unchecked")
					RecipeHolder<CraftingRecipe> h = (RecipeHolder<CraftingRecipe>) holder;
					CraftingOption option = describe(level, h, cr);
					if (option != null && option.output() != Items.AIR) {
						CRAFTING.computeIfAbsent(option.output(), k -> new ArrayList<>()).add(option);
						crafting++;
					}
				} else if (recipe instanceof AbstractCookingRecipe cook && cook.getType() == RecipeType.SMELTING) {
					@SuppressWarnings("unchecked")
					RecipeHolder<? extends AbstractCookingRecipe> h = (RecipeHolder<? extends AbstractCookingRecipe>) holder;
					Optional<Holder<Item>> sample = cook.input().items().findFirst();
					if (sample.isEmpty()) {
						continue;
					}
					ItemStack out = cook.assemble(new SingleRecipeInput(new ItemStack(sample.get())));
					if (!out.isEmpty()) {
						SMELTING.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(new CookingOption(h, cook.input(), out.getItem(), out.getCount(),
							cook.cookingTime()));
						cooking++;
					}
				}
			} catch (RuntimeException e) {
				AutonomousCompanions.LOGGER.debug("Receita ignorada {}: {}", holder.id(), e.toString());
			}
		}
		built = true;
		AutonomousCompanions.LOGGER.info("Índice de receitas: {} receitas de bancada e {} de fornalha.", crafting, cooking);
	}

	private static CraftingOption describe(ServerLevel level, RecipeHolder<CraftingRecipe> holder, CraftingRecipe recipe) {
		int width;
		int height;
		List<Optional<Ingredient>> slots;
		boolean shaped;
		if (recipe instanceof ShapedRecipe shapedRecipe) {
			width = shapedRecipe.getWidth();
			height = shapedRecipe.getHeight();
			slots = shapedRecipe.getIngredients();
			shaped = true;
		} else {
			List<Ingredient> ingredients = recipe.placementInfo().ingredients();
			if (ingredients.isEmpty() || ingredients.size() > 9) {
				return null;
			}
			slots = new ArrayList<>();
			for (Ingredient ing : ingredients) {
				slots.add(Optional.of(ing));
			}
			int n = slots.size();
			width = n <= 4 ? Math.min(n, 2) : 3;
			height = (n + width - 1) / width;
			while (slots.size() < width * height) {
				slots.add(Optional.empty());
			}
			shaped = false;
			if (!(recipe instanceof ShapelessRecipe) && recipe.placementInfo().isImpossibleToPlace()) {
				return null;
			}
		}
		List<ItemStack> sample = new ArrayList<>();
		for (Optional<Ingredient> slot : slots) {
			if (slot.isEmpty()) {
				sample.add(ItemStack.EMPTY);
			} else {
				Optional<Holder<Item>> first = slot.get().items().findFirst();
				if (first.isEmpty()) {
					return null;
				}
				sample.add(new ItemStack(first.get()));
			}
		}
		CraftingInput input = CraftingInput.of(width, height, sample);
		if (!recipe.matches(input, level)) {
			return null;
		}
		ItemStack out = recipe.assemble(input);
		if (out.isEmpty()) {
			return null;
		}
		return new CraftingOption(holder, width, height, List.copyOf(slots), out.getItem(), out.getCount(), shaped);
	}

	/** Todas as receitas de bancada indexadas (cópia), para o catálogo. */
	public static synchronized List<CraftingOption> allCrafting() {
		List<CraftingOption> out = new ArrayList<>();
		CRAFTING.values().forEach(out::addAll);
		return out;
	}

	/** Todas as receitas de fornalha indexadas (cópia), para o catálogo. */
	public static synchronized List<CookingOption> allCooking() {
		List<CookingOption> out = new ArrayList<>();
		SMELTING.values().forEach(out::addAll);
		return out;
	}

	public static synchronized List<CraftingOption> crafting(Item output) {
		return CRAFTING.getOrDefault(output, List.of());
	}

	public static synchronized List<CookingOption> smelting(Item output) {
		return SMELTING.getOrDefault(output, List.of());
	}

	/** Receitas de fornalha que aceitam o item como entrada (usado para cozinhar alimentos). */
	public static synchronized List<CookingOption> smeltingFrom(Item input) {
		List<CookingOption> list = new ArrayList<>();
		for (List<CookingOption> options : SMELTING.values()) {
			for (CookingOption o : options) {
				if (o.input().test(new ItemStack(input))) {
					list.add(o);
				}
			}
		}
		return list;
	}

	public static synchronized boolean hasAnyRecipe(Item output) {
		return CRAFTING.containsKey(output) || SMELTING.containsKey(output);
	}
}
