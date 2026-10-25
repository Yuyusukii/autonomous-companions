package dev.autonomouscompanions.crafting;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.CompanionInventory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import org.jspecify.annotations.Nullable;

/**
 * Executa uma receita de bancada usando os itens do inventário, exatamente como a grade de crafting faz:
 * monta a entrada, confere {@code matches}, chama {@code assemble}, consome um item por espaço e devolve os restos
 * ({@code getRemainingItems}, ex.: baldes). Nunca consome itens reservados para entregas.
 */
public final class Crafter {
	private Crafter() {
	}

	/** Escolhe, para cada espaço, o item livre do inventário com maior quantidade. Nulo se faltar algo. */
	public static @Nullable List<ItemStack> chooseInputs(CompanionInventory inv, RecipeIndex.CraftingOption option) {
		Map<Item, Integer> used = new HashMap<>();
		List<ItemStack> inputs = new ArrayList<>();
		for (Optional<Ingredient> slot : option.slots()) {
			if (slot.isEmpty()) {
				inputs.add(ItemStack.EMPTY);
				continue;
			}
			Set<Item> options = RecipeIndex.items(slot.get());
			Item best = null;
			int bestFree = 0;
			for (Item item : options) {
				int free = inv.countFree(item) - used.getOrDefault(item, 0);
				if (free > bestFree) {
					bestFree = free;
					best = item;
				}
			}
			if (best == null) {
				return null;
			}
			used.merge(best, 1, Integer::sum);
			inputs.add(new ItemStack(best));
		}
		return inputs;
	}

	public static boolean canCraftNow(CompanionInventory inv, RecipeIndex.CraftingOption option) {
		return chooseInputs(inv, option) != null;
	}

	/** Quantas vezes a receita pode ser feita com os itens livres atuais. */
	public static int maxCrafts(CompanionInventory inv, RecipeIndex.CraftingOption option) {
		int max = Integer.MAX_VALUE;
		for (RecipeIndex.IngredientCount ic : option.grouped()) {
			int free = 0;
			for (Item item : ic.items()) {
				free += inv.countFree(item);
			}
			max = Math.min(max, free / ic.count());
		}
		return max == Integer.MAX_VALUE ? 0 : max;
	}

	/** Fabrica uma vez. Retorna o item produzido ou vazio se não foi possível. */
	public static ItemStack craftOnce(ServerLevel level, CompanionEntity c, RecipeIndex.CraftingOption option) {
		CompanionInventory inv = c.inventory();
		List<ItemStack> inputs = chooseInputs(inv, option);
		if (inputs == null) {
			return ItemStack.EMPTY;
		}
		CraftingInput input = CraftingInput.of(option.width(), option.height(), inputs);
		CraftingRecipe recipe = option.holder().value();
		if (!recipe.matches(input, level)) {
			return ItemStack.EMPTY;
		}
		ItemStack result = recipe.assemble(input);
		if (result.isEmpty()) {
			return ItemStack.EMPTY;
		}
		NonNullList<ItemStack> remainders = recipe.getRemainingItems(input);
		// Consome os ingredientes (um por espaço).
		for (ItemStack in : inputs) {
			if (!in.isEmpty()) {
				int removed = inv.removeFree(in.getItem(), 1);
				if (removed != 1) {
					// Não deveria acontecer (conferido antes); aborta sem criar o resultado.
					return ItemStack.EMPTY;
				}
			}
		}
		give(level, c, result.copy());
		for (ItemStack rem : remainders) {
			if (!rem.isEmpty()) {
				give(level, c, rem);
			}
		}
		c.stats().itemsCrafted += result.getCount();
		return result;
	}

	public static void give(ServerLevel level, CompanionEntity c, ItemStack stack) {
		ItemStack rest = c.inventory().insert(stack);
		if (!rest.isEmpty()) {
			ItemEntity entity = new ItemEntity(level, c.getX(), c.getY() + 0.5, c.getZ(), rest);
			entity.setDefaultPickUpDelay();
			level.addFreshEntity(entity);
		}
	}
}
