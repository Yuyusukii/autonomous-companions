package dev.autonomouscompanions.inventory;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.mining.HarvestAnalyzer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Seleção e uso de equipamentos: ferramentas, armas e armaduras. A mão principal fica fora do inventário (como no
 * jogador); trocar de ferramenta move o item atual de volta para o inventário.
 */
public final class Gear {
	private static @Nullable List<Item> toolItems;

	private Gear() {
	}

	/** Todos os itens registrados que são ferramentas de mineração (componente TOOL), inclusive de outros mods. */
	public static synchronized List<Item> allToolItems() {
		if (toolItems == null) {
			List<Item> list = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item.components().has(DataComponents.TOOL)) {
					list.add(item);
				}
			}
			toolItems = list;
		}
		return toolItems;
	}

	public static boolean isTool(ItemStack stack) {
		return stack.has(DataComponents.TOOL);
	}

	public static double attackDamage(ItemStack stack) {
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
	}

	public static double attackSpeed(ItemStack stack) {
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND);
	}

	/** Dano por segundo aproximado (dano × velocidade), usado para escolher armas. */
	public static double weaponScore(ItemStack stack) {
		if (stack.isEmpty()) {
			return 1.0;
		}
		// Lingotes, blocos, comida...: nunca são arma (antes um monte de lingotes de ferro "ganhava" da mão vazia e ela caçava
		// segurando 64 lingotes).
		if (!isWeaponLike(stack)) {
			return 0.0;
		}
		double dmg = attackDamage(stack);
		double speed = Math.max(0.5, attackSpeed(stack));
		return dmg * Math.min(speed, 1.6) + (dmg > 4 ? dmg * 0.3 : 0);
	}

	/** Serve como arma: ferramentas, espadas, machados, tridente, maça ou qualquer item com dano de ataque próprio. */
	public static boolean isWeaponLike(ItemStack s) {
		return s.has(DataComponents.TOOL) || s.is(net.minecraft.tags.ItemTags.SWORDS) || s.is(net.minecraft.tags.ItemTags.AXES)
			|| s.is(net.minecraft.world.item.Items.TRIDENT) || s.is(net.minecraft.world.item.Items.MACE) || attackDamage(s) > 1.0;
	}

	public static @Nullable EquipmentSlot armorSlot(ItemStack stack) {
		Equippable eq = stack.get(DataComponents.EQUIPPABLE);
		if (eq == null) {
			return null;
		}
		EquipmentSlot slot = eq.slot();
		return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? slot : null;
	}

	public static double armorValue(ItemStack stack, EquipmentSlot slot) {
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.ARMOR, 0.0, slot) + mods.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot) * 0.5;
	}

	/** Melhor ferramenta possuída (mão + inventário) para o bloco. */
	public static @Nullable ItemStack bestOwnedFor(CompanionEntity c, BlockState state, boolean requireCorrect, boolean requireSilk, ServerLevel level) {
		ItemStack best = null;
		float bestScore = 0;
		List<ItemStack> candidates = new ArrayList<>();
		candidates.add(c.mainHandItem());
		for (int i = 0; i < c.inventory().getContainerSize(); i++) {
			candidates.add(c.inventory().getItem(i));
		}
		for (ItemStack s : candidates) {
			if (s.isEmpty() || !isTool(s)) {
				continue;
			}
			boolean correct = s.isCorrectToolForDrops(state);
			if (requireCorrect && state.requiresCorrectToolForDrops() && !correct) {
				continue;
			}
			if (requireSilk && !HarvestAnalyzer.hasSilkTouch(level, s)) {
				continue;
			}
			float speed = s.getDestroySpeed(state);
			if (speed <= 1.0f && !(requireSilk || requireCorrect && correct)) {
				continue;
			}
			// Prefere ferramentas mais rápidas, mas preserva as quase quebradas.
			float durability = s.isDamageableItem() ? 1.0f - (float) s.getDamageValue() / Math.max(1, s.getMaxDamage()) : 1.0f;
			float score = speed * (0.6f + 0.4f * durability);
			if (score > bestScore) {
				bestScore = score;
				best = s;
			}
		}
		return best;
	}

	/** Coloca a pilha indicada (que deve pertencer ao inventário ou à mão) na mão principal. */
	public static void equipMainHand(CompanionEntity c, ItemStack stack) {
		if (stack == c.mainHandItem()) {
			return;
		}
		int slot = -1;
		for (int i = 0; i < c.inventory().getContainerSize(); i++) {
			if (c.inventory().getItem(i) == stack) {
				slot = i;
				break;
			}
		}
		if (slot < 0) {
			return;
		}
		ItemStack previous = c.mainHandItem();
		c.inventory().setItem(slot, previous.isEmpty() ? ItemStack.EMPTY : previous);
		c.setItemSlot(EquipmentSlot.MAINHAND, stack);
	}

	/** Guarda a mão principal no inventário (se couber). */
	public static void stowMainHand(CompanionEntity c) {
		ItemStack hand = c.mainHandItem();
		if (hand.isEmpty()) {
			return;
		}
		ItemStack rest = c.inventory().insert(hand);
		c.setItemSlot(EquipmentSlot.MAINHAND, rest);
	}

	public static boolean equipBestToolFor(CompanionEntity c, BlockState state, ServerLevel level, boolean requireSilk) {
		ItemStack best = bestOwnedFor(c, state, true, requireSilk, level);
		if (best != null) {
			equipMainHand(c, best);
			return true;
		}
		if (!requireSilk && !state.requiresCorrectToolForDrops()) {
			// Sem ferramenta útil: usa a mão (guarda armas/ferramentas para não gastar durabilidade à toa).
			if (isTool(c.mainHandItem()) || !c.mainHandItem().isEmpty()) {
				stowMainHand(c);
			}
			return true;
		}
		return false;
	}

	/** Equipa a melhor arma possuída (não reservada). */
	public static void equipBestWeapon(CompanionEntity c) {
		ItemStack hand = c.mainHandItem();
		double bestScore = weaponScore(hand);
		ItemStack best = null;
		for (int i = 0; i < c.inventory().getContainerSize(); i++) {
			ItemStack s = c.inventory().getItem(i);
			if (s.isEmpty() || c.inventory().reserved(s.getItem()) >= c.inventory().count(s.getItem())) {
				continue;
			}
			double score = weaponScore(s);
			if (score > bestScore + 0.01) {
				bestScore = score;
				best = s;
			}
		}
		if (best != null) {
			equipMainHand(c, best);
		} else if (!hand.isEmpty() && !isWeaponLike(hand)) {
			stowMainHand(c); // sem arma: luta de mão vazia em vez de segurar lingotes, blocos ou comida
		}
	}

	/** Veste as melhores armaduras disponíveis no inventário. */
	public static int equipBestArmor(CompanionEntity c) {
		int changed = 0;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack current = c.getItemBySlot(slot);
			double bestValue = current.isEmpty() ? 0 : armorValue(current, slot);
			int bestSlot = -1;
			for (int i = 0; i < c.inventory().getContainerSize(); i++) {
				ItemStack s = c.inventory().getItem(i);
				if (s.isEmpty() || armorSlot(s) != slot) {
					continue;
				}
				if (c.inventory().reserved(s.getItem()) >= c.inventory().count(s.getItem())) {
					continue;
				}
				double v = armorValue(s, slot);
				if (v > bestValue + 0.01) {
					bestValue = v;
					bestSlot = i;
				}
			}
			if (bestSlot >= 0) {
				ItemStack newArmor = c.inventory().getItem(bestSlot);
				c.inventory().setItem(bestSlot, current.isEmpty() ? ItemStack.EMPTY : current);
				c.setItemSlot(slot, newArmor);
				changed++;
			}
		}
		// Escudo na mão secundária.
		if (c.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
			int shield = c.inventory().findSlot(s -> s.is(net.minecraft.world.item.Items.SHIELD));
			if (shield >= 0) {
				c.setItemSlot(EquipmentSlot.OFFHAND, c.inventory().getItem(shield));
				c.inventory().setItem(shield, ItemStack.EMPTY);
				changed++;
			}
		}
		return changed;
	}

	/** Conta itens possuídos em qualquer lugar (inventário + mãos + armadura). */
	public static int countOwned(CompanionEntity c, Predicate<ItemStack> predicate) {
		int n = c.inventory().count(predicate);
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (slot.getType() == EquipmentSlot.Type.HAND || slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
				ItemStack s = c.getItemBySlot(slot);
				if (!s.isEmpty() && predicate.test(s)) {
					n += s.getCount();
				}
			}
		}
		return n;
	}
}
