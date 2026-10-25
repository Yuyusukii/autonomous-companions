package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.DeliveryService;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Volta até o dono (por navegação real, mesmo de longe) e entrega a quantidade pendente do pedido. */
public final class ReturnDeliverStep extends Step {
	private static final int MAX_ESCAPES = 3;
	private final Order order;
	private int fullNoticeCooldown;
	private int waitTicks;
	private int escapes;

	public ReturnDeliverStep(Order order) {
		this.order = order;
	}

	@Override
	public StepResult tick(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		if (order.remainingToDeliver() <= 0) {
			return StepResult.success();
		}
		// Sem os itens não há entrega: antes isso contava como sucesso e o pedido virava "concluído" sem entregar.
		if (order.countIn(c) <= 0 && !order.targetItems().contains(c.mainHandItem().getItem())) {
			return missing(c);
		}
		ServerPlayer owner = c.ownerInLevel();
		Component item = order.targetName();
		if (owner == null) {
			ctx.brain().navigator().clear();
			c.setCompanionState(CompanionState.RETURNING, Component.translatable("detail.autonomouscompanions.waiting_owner", item));
			if (++waitTicks % 200 == 0) {
				c.debug("Aguardando o dono para entregar.");
			}
			return StepResult.running();
		}
		ctx.brain().chunkLoader().keepActive();
		order.setStatus(order.remainingToDeliver() > 0 ? OrderStatus.RETURNING : order.status());
		double dist = c.distanceTo(owner);
		if (dist > 3.0) {
			c.setCompanionState(CompanionState.RETURNING, Component.translatable("detail.autonomouscompanions.returning", item, (int) dist));
			Navigator nav = ctx.brain().navigator();
			nav.setGoal(owner.blockPosition(), 2.0, dist > 12 ? 1.4 : 1.1);
			Navigator.Status st = nav.tick();
			if (st == Navigator.Status.FAILED) {
				nav.clear();
				// Sem caminho a pé até a dona (ex.: num túnel ou poço que ela mesma cavou): sai como uma jogadora, cavando
				// degraus ou subindo um pilar rumo à dona. Antes ela recomeçava o mesmo caminho impossível para sempre e
				// ficava andando sem chegar (a recuperação de emergência nunca vale durante missões).
				if (escapes < MAX_ESCAPES) {
					escapes++;
					c.debug("Entrega: sem caminho até a dona a " + (int) dist + " blocos; saindo rumo a ela (tentativa " + escapes + "/" + MAX_ESCAPES + ").");
					return StepResult.push(new EscapeStep(owner.blockPosition()));
				}
				c.setCompanionState(CompanionState.NO_ROUTE, Component.translatable("detail.autonomouscompanions.no_route_owner"));
				c.debug("Entrega: sem caminho até a dona depois de " + MAX_ESCAPES + " tentativas de sair.");
				return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("detail.autonomouscompanions.no_route_owner"));
			}
			return StepResult.running();
		}
		ctx.brain().navigator().clear();
		c.getLookControl().setLookAt(owner, 30, 30);
		order.setStatus(OrderStatus.DELIVERING);
		c.setCompanionState(CompanionState.DELIVERING, Component.translatable("detail.autonomouscompanions.delivering", item));
		DeliveryService.Result result = DeliveryService.deliverOrder(c, order, owner, ConfigManager.get().soltarItensSeInventarioDoDonoCheio);
		if (result.remaining() <= 0) {
			return StepResult.success();
		}
		if (order.countIn(c) <= 0) {
			return missing(c);
		}
		if (result.playerFull()) {
			order.setStatus(OrderStatus.WAITING_SPACE);
			c.setCompanionState(CompanionState.DELIVERING, Component.translatable("detail.autonomouscompanions.owner_full", item));
			if (--fullNoticeCooldown <= 0) {
				owner.sendOverlayMessage(Component.translatable("message.autonomouscompanions.owner_inventory_full", c.getDisplayName(), result.remaining(), item));
				fullNoticeCooldown = 600;
			}
		}
		return StepResult.running();
	}

	private StepResult missing(CompanionEntity c) {
		c.debug("Entrega: faltam " + order.remainingToDeliver() + " e ela não tem mais os itens do pedido.");
		return StepResult.fail(StepResult.FailureCode.ITEMS_MISSING, Component.translatable("reason.autonomouscompanions.items_missing", order.targetName(),
			order.remainingToDeliver()));
	}

	@Override
	public void stop(TaskContext ctx) {
		ctx.brain().navigator().clear();
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.deliver", order.remainingToDeliver(), order.targetName());
	}

	@Override
	public CompanionState state() {
		return CompanionState.RETURNING;
	}
}
