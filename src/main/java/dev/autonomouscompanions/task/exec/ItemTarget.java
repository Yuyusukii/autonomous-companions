package dev.autonomouscompanions.task.exec;

import dev.autonomouscompanions.entity.CompanionEntity;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Conjunto de itens aceitáveis para uma necessidade (ex.: "qualquer tábua"); {@code display} é usado na interface. */
public record ItemTarget(Set<Item> items, Item display) {
	public static ItemTarget of(Item item) {
		return new ItemTarget(Set.of(item), item);
	}

	public static ItemTarget anyOf(Set<Item> items) {
		Set<Item> copy = new LinkedHashSet<>(items);
		return new ItemTarget(copy, copy.iterator().next());
	}

	public boolean test(ItemStack stack) {
		return !stack.isEmpty() && items.contains(stack.getItem());
	}

	public boolean single() {
		return items.size() == 1;
	}

	/** Quantidade disponível: todos (itens da própria missão) ou só os livres de reservas. */
	public int count(CompanionEntity c, boolean includeReserved) {
		int total = 0;
		for (Item item : items) {
			total += includeReserved ? c.inventory().count(item) : c.inventory().countFree(item);
			if (c.mainHandItem().is(item) && !c.mainHandItem().isEmpty()) {
				total += c.mainHandItem().getCount();
			}
		}
		return total;
	}

	public Component describe() {
		Component name = new ItemStack(display).getHoverName();
		return single() ? name : Component.translatable("target.autonomouscompanions.any_of", name);
	}
}
