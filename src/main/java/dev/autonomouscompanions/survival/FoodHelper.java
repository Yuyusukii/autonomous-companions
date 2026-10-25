package dev.autonomouscompanions.survival;

import dev.autonomouscompanions.entity.CompanionEntity;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;

/** Escolha de alimentos: eficiente, segura e sem gastar itens raros em refeições comuns. */
public final class FoodHelper {
	private FoodHelper() {
	}

	/** Itens raros ou valiosos que ela só come em emergência. */
	public static boolean isPrecious(ItemStack s) {
		return s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_CARROT) || s.is(Items.SUSPICIOUS_STEW)
			|| s.is(Items.CHORUS_FRUIT) || s.is(Items.HONEY_BOTTLE) || s.is(Items.CAKE);
	}

	/** Alimentos com efeitos negativos (carne podre, frango cru, olho de aranha, baiacu...). */
	public static boolean isHarmful(ItemStack s) {
		Consumable c = s.get(DataComponents.CONSUMABLE);
		if (c == null) {
			return false;
		}
		for (var effect : c.onConsumeEffects()) {
			if (effect instanceof ApplyStatusEffectsConsumeEffect apply) {
				for (var inst : apply.effects()) {
					if (!inst.getEffect().value().isBeneficial()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static boolean isEdible(ItemStack s) {
		return !s.isEmpty() && s.has(DataComponents.FOOD) && s.has(DataComponents.CONSUMABLE);
	}

	/** Pontuação de uma comida para comer agora (0 = não comer). */
	public static int score(ItemStack s, boolean critical) {
		if (!isEdible(s)) {
			return 0;
		}
		if (isPrecious(s) && !critical) {
			return 0;
		}
		if (isHarmful(s) && !critical) {
			return 0;
		}
		FoodProperties food = s.get(DataComponents.FOOD);
		int base = food.nutrition() * 10 + (int) (food.saturation() * 4);
		if (isHarmful(s)) {
			base /= 4;
		}
		return Math.max(1, base);
	}

	/** Espaço do inventário com a melhor comida livre (não reservada) ou -1. */
	public static int bestFoodSlot(CompanionEntity c, boolean critical) {
		var inv = c.inventory();
		int best = -1;
		int bestScore = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			// Fome crítica pode usar o que não é entrega; fora disso, nem os materiais em uso pela missão (ex.: carne crua
			// que ela caçou para assar).
			if (s.isEmpty() || (critical ? inv.countFree(s.getItem()) : inv.countSpare(s.getItem())) <= 0) {
				continue;
			}
			int sc = score(s, critical);
			if (sc > bestScore) {
				bestScore = sc;
				best = i;
			}
		}
		return best;
	}

	public static int freeFoodCount(CompanionEntity c) {
		var inv = c.inventory();
		int total = 0;
		java.util.Set<Item> seen = new java.util.HashSet<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (score(s, false) > 0 && seen.add(s.getItem())) {
				total += inv.countFree(s.getItem());
			}
		}
		return total;
	}

	/** Conjunto de alimentos aceitáveis para ela buscar sozinha. */
	public static Set<Item> acceptableFoods() {
		Set<Item> set = new LinkedHashSet<>();
		for (Item item : BuiltInRegistries.ITEM) {
			ItemStack s = new ItemStack(item);
			if (isEdible(s) && !isPrecious(s) && !isHarmful(s)) {
				set.add(item);
			}
		}
		return set;
	}

	/**
	 * Começa a comer a melhor comida livre (com o fluxo real de consumo do item). A arma/ferramenta da mão volta para o
	 * inventário. Retorna verdadeiro se começou a comer.
	 */
	public static boolean startEating(CompanionEntity c, boolean critical) {
		int slot = bestFoodSlot(c, critical);
		if (slot < 0) {
			return false;
		}
		dev.autonomouscompanions.inventory.Gear.stowMainHand(c);
		ItemStack food = c.inventory().getItem(slot);
		c.inventory().setItem(slot, ItemStack.EMPTY);
		c.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, food);
		c.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
		if (!c.isUsingItem()) {
			dev.autonomouscompanions.inventory.Gear.stowMainHand(c);
			return false;
		}
		return true;
	}

	/** Ferida o bastante para que comer seja obrigatório (comida eleva a fome e a regeneração volta a funcionar). */
	public static boolean needsHealing(CompanionEntity c) {
		return c.getHealth() < c.getMaxHealth() * 0.7f && c.needs().hunger() < CompanionNeeds.MAX_HUNGER;
	}
}
