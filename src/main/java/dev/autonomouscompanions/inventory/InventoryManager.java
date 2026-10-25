package dev.autonomouscompanions.inventory;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.TaskContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Política de carga. Quando falta espaço: 1) organiza (junta pilhas), 2) descarta somente excedentes de materiais
 * comuns e sem valor (pedregulho, terra, cascalho, pedras decorativas além de uma reserva de 64, carne podre, sementes
 * excedentes). Ferramentas, armas, armaduras, comida, itens reservados e materiais raros nunca são descartados.
 * Se ainda assim não houver espaço, quem chamou deve entregar a parte concluída ou suspender a missão.
 */
public final class InventoryManager {
	private static final int KEEP_FILLER = 64;

	private InventoryManager() {
	}

	public static boolean hasRoomFor(TaskContext ctx, ItemTarget target) {
		CompanionInventory inv = ctx.inventory();
		return inv.emptySlots() > 0 || inv.spaceFor(new ItemStack(target.display())) > 0;
	}

	public static boolean isJunk(ItemStack s) {
		if (s.isEmpty() || s.has(DataComponents.TOOL) || s.has(DataComponents.EQUIPPABLE) || s.has(DataComponents.FOOD) && !s.is(Items.ROTTEN_FLESH)
			|| s.isDamageableItem()) {
			return false;
		}
		if (s.is(Items.ROTTEN_FLESH) || s.is(Items.WHEAT_SEEDS) || s.is(Items.POISONOUS_POTATO)) {
			return true;
		}
		if (s.getItem() instanceof BlockItem bi) {
			BlockState b = bi.getBlock().defaultBlockState();
			return dev.autonomouscompanions.task.exec.steps.TunnelStep.isFiller(bi) || b.is(net.minecraft.world.level.block.Blocks.GRAVEL);
		}
		return false;
	}

	/** @return verdadeiro se liberou pelo menos um espaço. */
	public static boolean makeRoom(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		CompanionInventory inv = ctx.inventory();
		int before = inv.emptySlots();
		inv.compact();
		if (inv.emptySlots() > before) {
			return true;
		}
		// Excedentes de lixo (somando todos os tipos de enchimento).
		int fillerKept = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!isJunk(s)) {
				continue;
			}
			int free = inv.countSpare(s.getItem()); // nunca descarta material em uso pela missão
			if (free <= 0) {
				continue;
			}
			int keep = Math.max(0, KEEP_FILLER - fillerKept);
			if (s.getCount() <= keep) {
				fillerKept += s.getCount();
				continue;
			}
			int discard = Math.min(free, s.getCount() - keep);
			fillerKept += s.getCount() - discard;
			if (discard <= 0) {
				continue;
			}
			ItemStack dropped = s.split(discard);
			if (s.isEmpty()) {
				inv.setItem(i, ItemStack.EMPTY);
			}
			ItemEntity e = new ItemEntity(ctx.level(), c.getX(), c.getY() + 0.3, c.getZ(), dropped);
			e.setDefaultPickUpDelay();
			ctx.level().addFreshEntity(e);
			c.debug("Descartou excedente de " + dropped.getCount() + "x " + dropped.getHoverName().getString() + " para abrir espaço.");
		}
		inv.compact();
		return inv.emptySlots() > before;
	}
}
