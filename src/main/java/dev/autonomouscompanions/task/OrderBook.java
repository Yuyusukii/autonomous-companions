package dev.autonomouscompanions.task;

import com.mojang.serialization.Codec;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.OrderType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/** Fila de ordens de uma companheira (persistente). A primeira ordem executável e não suspensa é a ativa. */
public final class OrderBook {
	public static final Codec<List<Order>> LIST_CODEC = Order.CODEC.listOf();
	private static final int MAX_HISTORY = 8;
	public static final int MAX_QUEUED = 12;

	private final List<Order> orders = new ArrayList<>();
	private int version;

	public List<Order> all() {
		return Collections.unmodifiableList(orders);
	}

	public void load(List<Order> loaded) {
		orders.clear();
		orders.addAll(loaded);
		version++;
	}

	public int version() {
		return version;
	}

	public void touch() {
		version++;
	}

	public boolean canAdd() {
		return orders.stream().filter(o -> !o.status().isFinished()).count() < MAX_QUEUED;
	}

	public void add(Order order) {
		if (order.urgent()) {
			int idx = 0;
			while (idx < orders.size() && orders.get(idx).urgent() && !orders.get(idx).status().isFinished()) {
				idx++;
			}
			orders.add(idx, order);
		} else {
			orders.add(order);
		}
		trimHistory();
		version++;
	}

	public @Nullable Order active() {
		for (Order o : orders) {
			if (o.status().isRunnable()) {
				return o;
			}
		}
		return null;
	}

	public @Nullable Order byId(UUID id) {
		for (Order o : orders) {
			if (o.id().equals(id)) {
				return o;
			}
		}
		return null;
	}

	public @Nullable Order firstOfType(OrderType type) {
		for (Order o : orders) {
			if (o.type() == type && !o.status().isFinished()) {
				return o;
			}
		}
		return null;
	}

	/** Materiais em uso pela missão em andamento (não persistido: recalculado a cada tick pela pilha de etapas). */
	private final java.util.Map<Item, Integer> working = new java.util.HashMap<>();

	public void setWorking(java.util.Map<Item, Integer> items) {
		working.clear();
		working.putAll(items);
	}

	public int working(Item item) {
		int w = working.getOrDefault(item, 0);
		// Pedido de carne assada: a carne crua fica protegida desde a criação do pedido (antes de a etapa de assar começar,
		// ela podia comer a matéria-prima se estivesse com fome).
		Order a = active();
		if (a != null && a.targetId().equals(Order.ANY_COOKED_MEAT) && Order.RAW_MEATS_TO_COOK.contains(item)) {
			w = Math.max(w, a.remainingToDeliver());
		}
		return w;
	}

	public java.util.Map<Item, Integer> workingItems() {
		return java.util.Collections.unmodifiableMap(working);
	}

	/** Unidades reservadas de um item: soma do que falta entregar em pedidos não finalizados (inclusive suspensos). */
	public int reserved(Item item) {
		int total = 0;
		for (Order o : orders) {
			if (o.reservesItems() && o.targetItems().contains(item)) {
				total += o.remainingToDeliver();
			}
		}
		return total;
	}

	public boolean hasPendingDeliveries() {
		for (Order o : orders) {
			if (o.reservesItems() && o.remainingToDeliver() > 0) {
				return true;
			}
		}
		return false;
	}

	public void setStatus(Order order, OrderStatus status) {
		order.setStatus(status);
		version++;
		if (status.isFinished()) {
			trimHistory();
		}
	}

	public void remove(Order order) {
		orders.remove(order);
		version++;
	}

	public void clearFinished() {
		orders.removeIf(o -> o.status().isFinished());
		version++;
	}

	private void trimHistory() {
		int finished = 0;
		for (int i = orders.size() - 1; i >= 0; i--) {
			if (orders.get(i).status().isFinished()) {
				finished++;
				if (finished > MAX_HISTORY) {
					orders.remove(i);
				}
			}
		}
	}
}
