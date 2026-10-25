package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Ajudante: obtém (se ainda não tiver) e leva itens até uma das companheiras, entregando direto no inventário dela.
 * Só sai do inventário da ajudante o que realmente coube no da companheira.
 */
public final class SupplyCompanionStep extends Step {
	private final UUID target;
	private final Component targetName;
	private final ItemTarget items;
	private final int count;
	private boolean obtainTried;
	private int walkTicks;

	public SupplyCompanionStep(CompanionEntity target, ItemTarget items, int count) {
		this.target = target.getUUID();
		this.targetName = target.getDisplayName();
		this.items = items;
		this.count = count;
	}

	@Override
	public StepResult tick(TaskContext ctx) {
		CompanionEntity helper = ctx.companion();
		Entity e = ctx.level().getEntity(target);
		if (!(e instanceof CompanionEntity to) || !to.isAlive()) {
			return StepResult.success(); // ela morreu ou foi para outra dimensão: nada a entregar
		}
		int have = items.count(helper, false);
		// Se a outra não tem nada disso (ex.: com fome e sem comida), entrega já o que tiver e busca o resto depois (a
		// reposição completa). Antes ela só entregava com a quantidade toda e, com poucos animais, nunca entregava.
		boolean recipientHasNone = items.count(to, false) == 0;
		if (have < count && !obtainTried && !(have > 0 && recipientHasNone)) {
			obtainTried = true;
			return StepResult.push(new ObtainStep(items, count, false, Set.of()));
		}
		if (have <= 0) {
			return StepResult.fail(StepResult.FailureCode.NOT_FOUND, Component.translatable("reason.autonomouscompanions.helper_no_items", items.describe()));
		}
		helper.setCompanionState(CompanionState.DELIVERING, Component.translatable("detail.autonomouscompanions.helper_delivering", items.describe(), targetName));
		if (helper.distanceTo(to) > 2.5) {
			if (++walkTicks > 20 * 90) {
				return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("detail.autonomouscompanions.no_route_owner"));
			}
			ctx.brain().navigator().setGoal(to.blockPosition(), 1.5, 1.45);
			helper.setSprinting(helper.distanceTo(to) > 8);
			if (ctx.brain().navigator().tick() == Navigator.Status.FAILED) {
				return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("detail.autonomouscompanions.no_route_owner"));
			}
			return StepResult.running();
		}
		helper.setSprinting(false);
		ctx.brain().navigator().clear();
		int moved = handOver(helper, to, items, Math.min(count, have));
		if (moved <= 0) {
			return StepResult.fail(StepResult.FailureCode.INVENTORY_FULL, Component.translatable("message.autonomouscompanions.companion_full", targetName));
		}
		helper.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
		helper.getLookControl().setLookAt(to, 30.0F, 30.0F);
		helper.debug("Entregou " + moved + " item(ns) (" + items.describe().getString() + ") para " + targetName.getString() + ".");
		to.debug("Recebeu " + moved + " item(ns) da ajudante.");
		Gear.equipBestArmor(to);
		return StepResult.success();
	}

	/** Move até {@code max} itens aceitos por {@code items} do inventário de {@code from} para o de {@code to}. */
	public static int handOver(CompanionEntity from, CompanionEntity to, ItemTarget items, int max) {
		int moved = 0;
		var inv = from.inventory();
		// A mão principal também conta (ex.: a espada recém-fabricada pode estar empunhada).
		if (items.test(from.mainHandItem()) && moved < max) {
			Gear.stowMainHand(from);
		}
		for (int i = 0; i < inv.getContainerSize() && moved < max; i++) {
			ItemStack s = inv.getItem(i);
			if (!items.test(s) || inv.countFree(s.getItem()) <= 0) {
				continue;
			}
			int k = Math.min(Math.min(max - moved, s.getCount()), to.inventory().spaceFor(s));
			if (k <= 0) {
				continue;
			}
			ItemStack rest = to.inventory().insert(s.copyWithCount(k));
			int in = k - rest.getCount();
			s.shrink(in);
			if (s.isEmpty()) {
				inv.setItem(i, ItemStack.EMPTY);
			}
			moved += in;
		}
		return moved;
	}

	@Override
	public void onChildFinished(TaskContext ctx, Step child, StepResult result) {
		ctx.invalidateCosts();
	}

	@Override
	public void stop(TaskContext ctx) {
		ctx.companion().setSprinting(false);
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.helper_supply", count, items.describe(), targetName);
	}

	@Override
	public CompanionState state() {
		return CompanionState.DELIVERING;
	}
}
