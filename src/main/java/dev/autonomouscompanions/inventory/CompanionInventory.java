package dev.autonomouscompanions.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Inventário interno (36 espaços, como o do jogador). Reservas de itens para missões são calculadas pelo
 * {@link ReservationProvider} e respeitadas por todas as operações que consomem itens (crafting, comida, combustível).
 */
public class CompanionInventory extends SimpleContainer {
	public static final int SIZE = 36;

	/** Fornece quantas unidades de um item estão reservadas para entregas pendentes. */
	public interface ReservationProvider {
		int reserved(Item item);
	}

	private ReservationProvider reservations = item -> 0;

	/**
	 * Materiais em uso pela missão atual (ainda não são a entrega, mas a missão precisa deles: a carne crua que vai assar,
	 * o pedregulho da fornalha, as tábuas da cama...). Não podem ser comidos (salvo fome crítica), usados como apoio em
	 * túneis e pilares nem descartados como lixo.
	 */
	private ReservationProvider working = item -> 0;

	public void setWorkingProvider(ReservationProvider provider) {
		this.working = provider;
	}

	/** Livre e fora do uso da missão atual: o que pode ser comido, usado como apoio ou descartado. */
	public int countSpare(Item item) {
		return Math.max(0, countFree(item) - working.reserved(item));
	}

	public CompanionInventory() {
		super(SIZE);
	}

	public void setReservationProvider(ReservationProvider provider) {
		this.reservations = provider;
	}

	public int reserved(Item item) {
		return reservations.reserved(item);
	}

	public int count(Item item) {
		int total = 0;
		for (int i = 0; i < getContainerSize(); i++) {
			ItemStack s = getItem(i);
			if (!s.isEmpty() && s.is(item)) {
				total += s.getCount();
			}
		}
		return total;
	}

	public int count(Predicate<ItemStack> predicate) {
		int total = 0;
		for (int i = 0; i < getContainerSize(); i++) {
			ItemStack s = getItem(i);
			if (!s.isEmpty() && predicate.test(s)) {
				total += s.getCount();
			}
		}
		return total;
	}

	/** Quantidade utilizável (não reservada para missões) de um item. */
	public int countFree(Item item) {
		return Math.max(0, count(item) - reservations.reserved(item));
	}

	public int countFree(Predicate<ItemStack> predicate) {
		// Soma por tipo de item para descontar reservas corretamente.
		java.util.Map<Item, Integer> perItem = new java.util.HashMap<>();
		for (int i = 0; i < getContainerSize(); i++) {
			ItemStack s = getItem(i);
			if (!s.isEmpty() && predicate.test(s)) {
				perItem.merge(s.getItem(), s.getCount(), Integer::sum);
			}
		}
		int total = 0;
		for (var e : perItem.entrySet()) {
			total += Math.max(0, e.getValue() - reservations.reserved(e.getKey()));
		}
		return total;
	}

	/** Remove até {@code amount} unidades livres (não reservadas). Retorna a quantidade removida. */
	public int removeFree(Item item, int amount) {
		int allowed = Math.min(amount, countFree(item));
		return removeRaw(item, allowed);
	}

	/** Remove até {@code amount} unidades ignorando reservas (usado na entrega da própria missão). */
	public int removeRaw(Item item, int amount) {
		int remaining = amount;
		for (int i = getContainerSize() - 1; i >= 0 && remaining > 0; i--) {
			ItemStack s = getItem(i);
			if (!s.isEmpty() && s.is(item)) {
				int take = Math.min(remaining, s.getCount());
				s.shrink(take);
				if (s.isEmpty()) {
					setItem(i, ItemStack.EMPTY);
				}
				remaining -= take;
			}
		}
		if (remaining != amount) {
			setChanged();
		}
		return amount - remaining;
	}

	/** Quantas unidades deste item cabem (pilhas existentes + espaços vazios). */
	public int spaceFor(ItemStack stack) {
		int max = stack.getMaxStackSize();
		int space = 0;
		for (int i = 0; i < getContainerSize(); i++) {
			ItemStack s = getItem(i);
			if (s.isEmpty()) {
				space += max;
			} else if (ItemStack.isSameItemSameComponents(s, stack)) {
				space += Math.max(0, Math.min(max, s.getMaxStackSize()) - s.getCount());
			}
		}
		return space;
	}

	public int emptySlots() {
		int n = 0;
		for (int i = 0; i < getContainerSize(); i++) {
			if (getItem(i).isEmpty()) {
				n++;
			}
		}
		return n;
	}

	public boolean isNearlyFull() {
		return emptySlots() <= 1;
	}

	/** Insere uma pilha e devolve o que não coube (a pilha original não é alterada). */
	public ItemStack insert(ItemStack stack) {
		return addItem(stack.copy());
	}

	public @Nullable ItemStack findFirst(Predicate<ItemStack> predicate) {
		for (int i = 0; i < getContainerSize(); i++) {
			ItemStack s = getItem(i);
			if (!s.isEmpty() && predicate.test(s)) {
				return s;
			}
		}
		return null;
	}

	public int findSlot(Predicate<ItemStack> predicate) {
		for (int i = 0; i < getContainerSize(); i++) {
			ItemStack s = getItem(i);
			if (!s.isEmpty() && predicate.test(s)) {
				return i;
			}
		}
		return -1;
	}

	/** Melhor pilha segundo uma pontuação (maior é melhor); ignora pontuações <= 0. */
	public int findBestSlot(ToIntFunction<ItemStack> score) {
		int best = -1;
		int bestScore = 0;
		for (int i = 0; i < getContainerSize(); i++) {
			ItemStack s = getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			int sc = score.applyAsInt(s);
			if (sc > bestScore) {
				bestScore = sc;
				best = i;
			}
		}
		return best;
	}

	/** Junta pilhas incompletas do mesmo item (organização interna). */
	public void compact() {
		for (int i = 0; i < getContainerSize(); i++) {
			ItemStack a = getItem(i);
			if (a.isEmpty() || a.getCount() >= a.getMaxStackSize()) {
				continue;
			}
			for (int j = i + 1; j < getContainerSize() && a.getCount() < a.getMaxStackSize(); j++) {
				ItemStack b = getItem(j);
				if (!b.isEmpty() && ItemStack.isSameItemSameComponents(a, b)) {
					int move = Math.min(b.getCount(), a.getMaxStackSize() - a.getCount());
					a.grow(move);
					b.shrink(move);
					if (b.isEmpty()) {
						setItem(j, ItemStack.EMPTY);
					}
				}
			}
		}
		setChanged();
	}

	public List<ItemStack> snapshot() {
		List<ItemStack> list = new ArrayList<>(getContainerSize());
		for (int i = 0; i < getContainerSize(); i++) {
			list.add(getItem(i).copy());
		}
		return list;
	}
}
