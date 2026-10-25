package dev.autonomouscompanions.inventory;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.task.Order;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Transferência efetiva de itens para o jogador. Regras anti-duplicação/perda:
 * <ul>
 *   <li>nunca entrega mais que {@code pedido - já entregue} (repetir a entrega não duplica);</li>
 *   <li>cada pilha é inserida primeiro no inventário do jogador e só a quantidade realmente aceita é removida da
 *       companheira, na mesma operação do servidor;</li>
 *   <li>se o inventário do jogador estiver cheio, o restante fica com a companheira (ou é largado aos pés do dono,
 *       só para ele recolher, se a configuração permitir).</li>
 * </ul>
 */
public final class DeliveryService {
	private DeliveryService() {
	}

	public record Result(int delivered, int remaining, boolean playerFull) {
	}

	public static Result deliverOrder(CompanionEntity c, Order order, ServerPlayer player, boolean dropIfFull) {
		int remaining = order.remainingToDeliver();
		if (remaining <= 0) {
			return new Result(0, 0, false);
		}
		int given = 0;
		boolean full = false;
		// Pedidos de grupo ("qualquer carne"): entrega qualquer item do grupo até completar.
		for (Item item : order.targetItems()) {
			if (given >= remaining) {
				break;
			}
			// O item pode estar na mão (ex.: pedido de uma ferramenta).
			ItemStack hand = c.getItemBySlot(EquipmentSlot.MAINHAND);
			if (!hand.isEmpty() && hand.is(item)) {
				ItemStack rest = c.inventory().insert(hand);
				c.setItemSlot(EquipmentSlot.MAINHAND, rest);
			}
			int k = transfer(c, item, remaining - given, player);
			given += k;
			int stillOwed = remaining - given;
			if (stillOwed > 0 && c.inventory().count(item) > 0) {
				full = true;
				if (dropIfFull) {
					given += dropForOwner(c, item, stillOwed, player);
				}
			}
		}
		if (given > 0) {
			order.progress().delivered += given;
			order.progress().deliveryCount++;
			c.stats().itemsDelivered += given;
			player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.3f, 1.4f);
		}
		return new Result(given, order.remainingToDeliver(), full);
	}

	/** Entrega livre (botão "entregar" da interface): não ultrapassa {@code max} e respeita reservas se pedido. */
	public static int transfer(CompanionEntity c, Item item, int max, ServerPlayer player) {
		int given = 0;
		var inv = c.inventory();
		for (int i = inv.getContainerSize() - 1; i >= 0 && given < max; i--) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || !stack.is(item)) {
				continue;
			}
			// Só oferece o que cabe: no modo criativo o inventário do jogador descarta silenciosamente o excesso.
			int k = Math.min(Math.min(stack.getCount(), max - given), roomFor(player, stack));
			if (k <= 0) {
				break;
			}
			ItemStack offer = stack.copyWithCount(k);
			player.getInventory().add(offer);
			int inserted = k - offer.getCount();
			if (inserted > 0) {
				stack.shrink(inserted);
				if (stack.isEmpty()) {
					inv.setItem(i, ItemStack.EMPTY);
				}
				given += inserted;
			}
			if (offer.getCount() > 0) {
				break; // inventário do jogador cheio
			}
		}
		if (given > 0) {
			inv.setChanged();
			player.containerMenu.broadcastChanges();
		}
		return given;
	}

	/** Quantas unidades deste item cabem no inventário principal e na mão secundária do jogador. */
	public static int roomFor(ServerPlayer player, ItemStack stack) {
		var inv = player.getInventory();
		int room = 0;
		for (ItemStack s : inv.getNonEquipmentItems()) {
			room += roomIn(inv, s, stack);
		}
		room += roomIn(inv, inv.getItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND), stack);
		return room;
	}

	private static int roomIn(net.minecraft.world.entity.player.Inventory inv, ItemStack slot, ItemStack stack) {
		int max = Math.min(stack.getMaxStackSize(), inv.getMaxStackSize(stack));
		if (slot.isEmpty()) {
			return stack.isDamaged() ? 1 : max;
		}
		if (stack.isStackable() && ItemStack.isSameItemSameComponents(slot, stack)) {
			return Math.max(0, max - slot.getCount());
		}
		return 0;
	}

	private static int dropForOwner(CompanionEntity c, Item item, int max, ServerPlayer player) {
		int dropped = 0;
		var inv = c.inventory();
		for (int i = inv.getContainerSize() - 1; i >= 0 && dropped < max; i--) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || !stack.is(item)) {
				continue;
			}
			int k = Math.min(stack.getCount(), max - dropped);
			ItemStack out = stack.split(k);
			if (stack.isEmpty()) {
				inv.setItem(i, ItemStack.EMPTY);
			}
			ItemEntity e = new ItemEntity(player.level(), player.getX(), player.getY() + 0.2, player.getZ(), out);
			e.setNoPickUpDelay();
			e.setTarget(player.getUUID());
			player.level().addFreshEntity(e);
			dropped += k;
		}
		return dropped;
	}
}
