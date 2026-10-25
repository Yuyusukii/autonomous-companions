package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.mining.DropIndex;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.world.ResourceKnowledge;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Fluxo completo de um pedido de item: preparação proporcional ao risco → obtenção da quantidade exata (planejamento
 * recursivo) → recolhimento das estações próprias → retorno e entrega. Inventário cheio no meio do caminho gera uma
 * entrega parcial e a missão continua; falha real gera entrega do que foi obtido e o motivo fica registrado.
 */
public final class MissionStep extends Step {
	private enum Phase {
		PREPARE,
		OBTAIN,
		RECOVER,
		DELIVER,
		PARTIAL_DELIVERY,
		FAIL_DELIVERY,
		DONE
	}

	private final Order order;
	private Phase phase = Phase.PREPARE;
	private @Nullable StepResult failure;
	private int partialDeliveries;
	private boolean equipChecked;
	private boolean toolChecked;
	/** Quantas vezes voltou a obter itens que sumiram antes da entrega. */
	private int refetches;

	public MissionStep(Order order) {
		this.order = order;
	}

	@Override
	public StepResult tick(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		switch (phase) {
			case PREPARE -> {
				// A fase só avança quando as etapas filhas terminam (onChildFinished), nunca antes de empilhá-las.
				order.setPreparing(true);
				if (!equipChecked) {
					equipChecked = true;
					EquipStep.Risk risk = riskFor(ctx);
					if (risk != null && c.settings().autoEquip) {
						return StepResult.push(new EquipStep(risk));
					}
				}
				if (!toolChecked) {
					toolChecked = true;
					BlockState work = workBlock(ctx);
					if (work != null && !EnsureToolStep.ownsSuitable(ctx, work, false) && hasFasterTool(work)) {
						// Pensa como uma jogadora: para várias unidades, vale fazer antes a ferramenta certa
						// (machado para madeira, picareta para pedra, pá para terra...).
						c.debug("Preparando a ferramenta para " + work.getBlock().getName().getString() + " antes de começar.");
						return StepResult.push(new EnsureToolStep(work, false, Set.of()));
					}
				}
				order.setPreparing(false);
				phase = Phase.OBTAIN;
				return StepResult.running();
			}
			case OBTAIN -> {
				// Estado verdadeiro: obtendo (inclusive quando volta a obter itens que sumiram antes da entrega).
				if (order.status() == OrderStatus.PENDING || order.status() == OrderStatus.RETURNING || order.status() == OrderStatus.DELIVERING) {
					order.setStatus(OrderStatus.ACTIVE);
				}
				if (order.skill() != null) {
					// Pedido de habilidade: executa o procedimento dela (a entrega e a conclusão seguem como nos outros pedidos).
					var skill = dev.autonomouscompanions.mind.skills.SkillLibrary.get(order.skill());
					if (skill == null || !skill.orderable()) {
						return StepResult.fail(StepResult.FailureCode.IMPOSSIBLE, Component.literal("Habilidade indisponível: " + order.skill()));
					}
					return StepResult.push(new dev.autonomouscompanions.mind.skills.SkillStep(skill, order.remainingToDeliver()));
				}
				ItemTarget target = ItemTarget.anyOf(order.targetItems());
				if (order.targetId().equals(dev.autonomouscompanions.task.Order.ANY_COOKED_MEAT)) {
					// Qualquer carne assada: caça qualquer animal de carne (o mais perto) e assa o que trouxer.
					return StepResult.push(new CookMeatStep(target, order.remainingToDeliver(), true));
				}
				return StepResult.push(new ObtainStep(target, order.remainingToDeliver(), true, Set.of()));
			}
			case RECOVER -> {
				return StepResult.push(new RecoverStationsStep());
			}
			case DELIVER, PARTIAL_DELIVERY, FAIL_DELIVERY -> {
				return StepResult.push(new ReturnDeliverStep(order));
			}
			case DONE -> {
				return failure != null ? failure : StepResult.success();
			}
		}
		return StepResult.running();
	}

	/**
	 * Bloco que ela vai trabalhar para obter o item: a fonte natural do próprio item (minério, tronco, pedra) ou, se o
	 * item é fabricado, a fonte do ingrediente principal (ex.: tábuas → troncos). Nulo para poucas unidades ou quando
	 * nada é minerado.
	 */
	private @Nullable BlockState workBlock(TaskContext ctx) {
		// Só o que ainda falta obter: se ela já carrega o pedido, não precisa de ferramenta nenhuma.
		int missing = order.remainingToDeliver() - ItemTarget.anyOf(order.targetItems()).count(ctx.companion(), false);
		if (missing < 3) {
			return null;
		}
		var costs = ctx.costs();
		var methods = costs.methods(dev.autonomouscompanions.task.exec.ItemTarget.anyOf(order.targetItems()), missing, Set.of(), false);
		if (methods.isEmpty()) {
			return null;
		}
		if (methods.getFirst() instanceof dev.autonomouscompanions.crafting.CostModel.Mine mine) {
			return mine.sources().getFirst().state();
		}
		if (methods.getFirst() instanceof dev.autonomouscompanions.crafting.CostModel.Craft craft) {
			int crafts = (missing + craft.option().outputCount() - 1) / craft.option().outputCount();
			for (var group : craft.option().grouped()) {
				// Desconta o que ela já tem (ex.: fornalha com pedregulho no inventário: nada a minerar, nada de picareta).
				var ingredient = dev.autonomouscompanions.task.exec.ItemTarget.anyOf(group.items());
				int need = group.count() * crafts - ingredient.count(ctx.companion(), false);
				if (need < 3) {
					continue;
				}
				var inner = costs.methods(ingredient, need, Set.of(order.targetItem()), false);
				if (!inner.isEmpty() && inner.getFirst() instanceof dev.autonomouscompanions.crafting.CostModel.Mine mine) {
					return mine.sources().getFirst().state();
				}
			}
		}
		return null;
	}

	/** Existe ferramenta que acelera este bloco (machado, picareta, pá...)? */
	private static boolean hasFasterTool(BlockState state) {
		for (var tool : dev.autonomouscompanions.inventory.Gear.allToolItems()) {
			if (new ItemStack(tool).getDestroySpeed(state) > 1.0F) {
				return true;
			}
		}
		return false;
	}

	private EquipStep.@Nullable Risk riskFor(TaskContext ctx) {
		var sources = DropIndex.blockSources(order.targetItem());
		int cost = sources.isEmpty() ? 4 : ResourceKnowledge.of(sources.getFirst().state()).cost();
		boolean dangerous = !sources.isEmpty() && ResourceKnowledge.of(sources.getFirst().state()).dangerous();
		SearchProfile p = order.profile() == SearchProfile.AUTO ? ResourceKnowledge.autoProfile(cost) : order.profile();
		if (dangerous || p == SearchProfile.EXPEDITION) {
			return EquipStep.Risk.MODERATE;
		}
		if (p == SearchProfile.RARE) {
			return EquipStep.Risk.LIGHT;
		}
		return null;
	}

	@Override
	public void onChildFinished(TaskContext ctx, Step child, StepResult result) {
		ctx.invalidateCosts();
		switch (phase) {
			case PREPARE -> {
				// Equipamento e ferramenta são opcionais: com ou sem sucesso, a preparação continua.
			}
			case RECOVER -> phase = Phase.DELIVER;
			case OBTAIN -> {
				if (result.isSuccess()) {
					phase = Phase.RECOVER;
					return;
				}
				int have = order.countIn(ctx.companion());
				if (result.code() == StepResult.FailureCode.INVENTORY_FULL && have > 0 && partialDeliveries < 64) {
					// Política de carga: entrega a parte concluída e continua a missão.
					partialDeliveries++;
					ctx.companion().debug("Inventário cheio: entregando parte concluída (" + have + ").");
					phase = Phase.PARTIAL_DELIVERY;
					return;
				}
				failure = result.code() == StepResult.FailureCode.INVENTORY_FULL
					? StepResult.fail(StepResult.FailureCode.INVENTORY_FULL, Component.translatable("reason.autonomouscompanions.inventory_full_other"))
					: result;
				phase = have > 0 && result.code() != StepResult.FailureCode.CANCELLED ? Phase.FAIL_DELIVERY : Phase.DONE;
			}
			case PARTIAL_DELIVERY -> phase = Phase.OBTAIN;
			case DELIVER -> {
				// Verificação: só termina com tudo entregue. Se os itens sumiram antes da entrega, volta a obter o que falta.
				if (result.isFailure() && result.code() == StepResult.FailureCode.ITEMS_MISSING && refetches < 2) {
					refetches++;
					ctx.companion().debug("Verificação: faltam " + order.remainingToDeliver() + " para entregar; obtendo de novo (" + refetches + "ª vez).");
					phase = Phase.OBTAIN;
					return;
				}
				phase = Phase.DONE;
				if (result.isFailure()) {
					failure = result;
				}
			}
			case FAIL_DELIVERY -> phase = Phase.DONE;
			default -> {
			}
		}
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.mission", order.requested(), order.targetName());
	}

	@Override
	public CompanionState state() {
		return CompanionState.PLANNING;
	}
}
