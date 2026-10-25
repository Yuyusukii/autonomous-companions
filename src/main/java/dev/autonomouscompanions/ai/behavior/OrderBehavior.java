package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.build.BuildStep;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.coop.Cooperation;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.task.exec.TaskRunner;
import dev.autonomouscompanions.task.exec.steps.EquipStep;
import dev.autonomouscompanions.task.exec.steps.MissionStep;
import dev.autonomouscompanions.task.exec.steps.ReturnDeliverStep;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Executa a ordem ativa da fila. A pilha de etapas é transitória: ao recarregar o mundo, a ordem salva é replanejada a
 * partir do inventário real (sem duplicar itens, pois o progresso de entrega é persistido). Inclui um vigia que falha
 * a missão com explicação se nada progredir por tempo demais.
 */
public final class OrderBehavior extends Behavior {
	private @Nullable TaskRunner runner;
	private @Nullable UUID runnerOrder;
	private @Nullable TaskContext context;
	private final List<BlockPos> ownStations = new ArrayList<>();
	private final Set<Long> pillarBlocks = new HashSet<>();
	private double lastProgressValue = -1;
	/** Quantas vezes o plano da ordem atual foi refeito depois de travar. */
	private int stallRestarts;
	private boolean keepRestarts;
	private int ticksWithoutProgress;
	private int completedShowTicks;

	public OrderBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	public boolean hasWork() {
		return companion.isNamed() && companion.orders().active() != null;
	}

	@Override
	public int priority(ServerLevel level) {
		Order order = companion.orders().active();
		if (order == null || !companion.isNamed()) {
			return 0;
		}
		return order.urgent() ? Priorities.URGENT_ORDER : Priorities.ORDER;
	}

	@Override
	public int hysteresis() {
		return 2;
	}

	public @Nullable TaskRunner runner() {
		return runner;
	}

	public int ticksWithoutProgress() {
		return ticksWithoutProgress;
	}

	public int stallRestarts() {
		return stallRestarts;
	}

	@Override
	public void start(ServerLevel level) {
		ensureRunner(level);
	}

	private void ensureRunner(ServerLevel level) {
		Order order = companion.orders().active();
		if (order == null) {
			runner = null;
			runnerOrder = null;
			return;
		}
		if (runner != null && order.id().equals(runnerOrder) && !runner.isFinished()) {
			return;
		}
		if (!keepRestarts) {
			stallRestarts = 0; // ordem nova: zera as tentativas de replanejar
		}
		keepRestarts = false;
		context = new TaskContext(companion, brain, order, ownStations);
		runner = new TaskRunner(rootFor(order));
		runnerOrder = order.id();
		lastProgressValue = -1;
		ticksWithoutProgress = 0;
		if (order.status() == OrderStatus.PENDING) {
			companion.orders().setStatus(order, OrderStatus.ACTIVE);
		}
		companion.debug("Iniciando ordem " + order.type() + " " + order.targetId() + " x" + order.requested());
	}

	private Step rootFor(Order order) {
		return switch (order.type()) {
			case GATHER, CRAFT -> new MissionStep(order);
			case BUILD -> new BuildStep(order);
			case EQUIP -> new EquipStep(EquipStep.Risk.HEAVY);
			case RETURN -> new ReturnAllStep(companion);
		};
	}

	@Override
	public boolean tick(ServerLevel level) {
		Order order = companion.orders().active();
		if (order == null) {
			return false;
		}
		ensureRunner(level);
		if (runner == null || context == null) {
			return false;
		}
		context.refresh(level);
		Step before = runner.current();
		long t0 = System.nanoTime();
		StepResult r = runner.tick(context);
		// A mineração mede as próprias fases (mais detalhe).
		if (before != null && !(before instanceof dev.autonomouscompanions.task.exec.steps.MineStep)) {
			brain.metrics().stepTick(before.getClass().getSimpleName(), System.nanoTime() - t0);
		}
		// Materiais em uso pela missão (protegidos de comida, apoio e descarte enquanto a missão precisa deles).
		companion.orders().setWorking(runner.isFinished() ? java.util.Map.of() : runner.workingItems());
		Step current = runner.current();
		if (current != null && companion.stateSetTick() != companion.tickCount) {
			// A etapa não detalhou o estado neste tick: usa o estado e a descrição da etapa atual.
			companion.setCompanionState(runner.currentState(), current.describe());
		}
		watchdog(order);
		if (r.isSuccess()) {
			complete(level, order);
			return false;
		}
		if (r.isFailure()) {
			fail(level, order, r);
			return false;
		}
		return true;
	}

	/** Atividade real que não aparece nos contadores (andaimes provisórios, por exemplo): não é travamento. */
	public void noteActivity() {
		ticksWithoutProgress = 0;
	}

	private void watchdog(Order order) {
		Order.Progress p = order.progress();
		double value = p.obtained + p.blocksMined + p.itemsCrafted + p.delivered * 2 + p.blocksPlaced + Math.floor(p.distance / 6.0);
		if (value != lastProgressValue) {
			lastProgressValue = value;
			ticksWithoutProgress = 0;
			return;
		}
		// Fundição e espera pelo dono não contam como travamento.
		CompanionState s = companion.companionState();
		if (s == CompanionState.SMELTING || s == CompanionState.DELIVERING || s == CompanionState.RETURNING && companion.ownerInLevel() == null) {
			ticksWithoutProgress = 0;
			return;
		}
		if (++ticksWithoutProgress > ConfigManager.get().segundosSemProgressoParaFalha * 20) {
			ticksWithoutProgress = 0;
			if (runner != null && context != null) {
				runner.abort(context);
			}
			runner = null;
			if (stallRestarts < 2 && (order.type() == dev.autonomouscompanions.task.OrderEnums.OrderType.GATHER
				|| order.type() == dev.autonomouscompanions.task.OrderEnums.OrderType.CRAFT)) {
				// Travou: em vez de desistir, refaz o plano do zero (sai do buraco, esquece os alvos que deram errado e escolhe
				// de novo), como uma jogadora mudando de estratégia. O que ela já obteve continua com ela.
				stallRestarts++;
				keepRestarts = true;
				companion.debug("Ordem " + order.type() + " sem progresso: refazendo o plano (" + stallRestarts + "ª vez).");
				brain.navigator().clear();
				return;
			}
			order.setReason(Component.translatable("reason.autonomouscompanions.stalled"));
			companion.debug("Ordem " + order.type() + " sem progresso por tempo demais: marcada como falha.");
			companion.orders().setStatus(order, OrderStatus.FAILED);
			notifyOwner(Component.translatable("message.autonomouscompanions.order_failed", companion.getDisplayName(), order.reason()));
		}
	}

	/** Falha a ordem ativa depois de erros internos repetidos (o motivo aparece no painel). */
	public void failActiveWithError(ServerLevel level) {
		Order order = companion.orders().active();
		if (order != null) {
			if (runner != null && context != null) {
				try {
					runner.abort(context);
				} catch (RuntimeException ignored) {
					// A etapa já falhou; o importante é liberar a ordem.
				}
			}
			fail(level, order, StepResult.fail(Component.translatable("reason.autonomouscompanions.internal_error")));
		}
	}

	private void complete(ServerLevel level, Order order) {
		companion.orders().setWorking(java.util.Map.of());
		// Conclusão só com a entrega verificada: coleta e fabricação com itens pendentes não viram "concluída".
		if ((order.type() == dev.autonomouscompanions.task.OrderEnums.OrderType.GATHER || order.type() == dev.autonomouscompanions.task.OrderEnums.OrderType.CRAFT)
			&& order.remainingToDeliver() > 0) {
			fail(level, order, StepResult.fail(StepResult.FailureCode.ITEMS_MISSING, Component.translatable("reason.autonomouscompanions.partial_delivery",
				order.progress().delivered, order.requested(), order.targetName())));
			return;
		}
		companion.orders().setStatus(order, OrderStatus.COMPLETED);
		order.setReason(null);
		remember(level, order, true, "");
		runner = null;
		runnerOrder = null;
		companion.setCompanionState(CompanionState.TASK_COMPLETED, Component.translatable("detail.autonomouscompanions.completed"));
		Component what = switch (order.type()) {
			case GATHER, CRAFT -> Component.translatable("message.autonomouscompanions.delivered", order.progress().delivered,
				order.targetName());
			case BUILD -> buildDoneMessage(level, order);
			case EQUIP -> Component.translatable("message.autonomouscompanions.equip_done");
			case RETURN -> Component.translatable("message.autonomouscompanions.returned");
		};
		notifyOwner(Component.translatable("message.autonomouscompanions.order_done", companion.getDisplayName(), what));
		brain.markReturnAfterOrder();
		Cooperation.get(level.getServer()).releaseAll(companion.getUUID());
		brain.chunkLoader().release();
	}

	private void fail(ServerLevel level, Order order, StepResult r) {
		companion.orders().setWorking(java.util.Map.of());
		runner = null;
		runnerOrder = null;
		if (r.code() == StepResult.FailureCode.CANCELLED) {
			return;
		}
		Component reason = r.reason() != null ? r.reason() : Component.translatable("reason.autonomouscompanions.unknown");
		companion.debug("Ordem " + order.type() + " " + order.targetId() + " falhou: " + reason.getString());
		remember(level, order, false, (r.code() == null ? "" : r.code().name() + ": ") + reason.getString());
		order.setReason(reason);
		companion.orders().setStatus(order, OrderStatus.FAILED);
		companion.setCompanionState(CompanionState.TASK_SUSPENDED, reason);
		notifyOwner(Component.translatable("message.autonomouscompanions.order_failed", companion.getDisplayName(), reason));
		brain.markReturnAfterOrder();
		Cooperation.get(level.getServer()).releaseAll(companion.getUUID());
		brain.chunkLoader().release();
	}

	/** Memória: registra a tentativa (resultado, motivo, duração desde o pedido) — a base do que ela aprende com o tempo. */
	private void remember(ServerLevel level, Order order, boolean success, String reason) {
		var e = new dev.autonomouscompanions.mind.memory.WorldMemory.Experience(level.getGameTime(), order.type().name(), order.targetId(), success, reason,
			companion.blockPosition(), (int) Math.min(Integer.MAX_VALUE, Math.max(0, level.getGameTime() - order.createdTime())));
		dev.autonomouscompanions.mind.memory.WorldMemory.get(level.getServer()).recordExperience(companion.getUUID(), e);
	}

	/** Texto de conclusão de obra: limpeza de terreno, ajuda numa casa ou a casa em si. */
	private Component buildDoneMessage(ServerLevel level, Order order) {
		var project = order.projectId() == null ? null
			: dev.autonomouscompanions.persist.CompanionWorldData.get(level.getServer()).project(order.projectId());
		if (project != null && project.isTerrain()) {
			return Component.translatable("message.autonomouscompanions.terrain_done", project.area(), project.area());
		}
		if (project != null && project.workerIndex(companion.getUUID()) > 0) {
			return Component.translatable("message.autonomouscompanions.build_helped");
		}
		return Component.translatable("message.autonomouscompanions.build_done");
	}

	private void notifyOwner(Component message) {
		ServerPlayer owner = companion.ownerAnywhere();
		if (owner != null) {
			owner.sendSystemMessage(message);
		}
	}

	@Override
	public void stop(ServerLevel level) {
		if (runner != null && context != null) {
			runner.pause(context);
		}
	}

	/** Cancela a ordem (se for a atual, desmonta a pilha de etapas e libera reservas de área/alvos). */
	public void cancel(ServerLevel level, Order order) {
		companion.orders().setWorking(java.util.Map.of());
		if (runner != null && context != null && order.id().equals(runnerOrder)) {
			runner.abort(context);
			runner = null;
			runnerOrder = null;
		}
		companion.orders().setStatus(order, OrderStatus.CANCELLED);
		Cooperation.get(level.getServer()).releaseAll(companion.getUUID());
		brain.navigator().clear();
		brain.requestDecision();
	}

	public void suspend(ServerLevel level, Order order) {
		companion.orders().setWorking(java.util.Map.of());
		if (runner != null && context != null && order.id().equals(runnerOrder)) {
			runner.abort(context);
			runner = null;
			runnerOrder = null;
		}
		companion.orders().setStatus(order, OrderStatus.SUSPENDED);
		brain.navigator().clear();
		brain.requestDecision();
	}

	public List<Component> describeStack() {
		return runner == null ? List.of() : runner.describeStack();
	}

	// ------------------------------------------------------------------ pilares provisórios

	public void notePillarBlock(BlockPos pos) {
		pillarBlocks.add(pos.asLong());
	}

	public boolean isPillarBlock(BlockPos pos) {
		return pillarBlocks.contains(pos.asLong());
	}

	public void forgetPillarBlock(BlockPos pos) {
		pillarBlocks.remove(pos.asLong());
	}

	// ------------------------------------------------------------------ persistência

	public void save(ValueOutput out) {
		out.store("Stations", BlockPos.CODEC.listOf(), List.copyOf(ownStations));
		out.store("Pillars", com.mojang.serialization.Codec.LONG.listOf(), List.copyOf(pillarBlocks));
	}

	public void load(ValueInput in) {
		ownStations.clear();
		in.read("Stations", BlockPos.CODEC.listOf()).ifPresent(ownStations::addAll);
		pillarBlocks.clear();
		in.read("Pillars", com.mojang.serialization.Codec.LONG.listOf()).ifPresent(pillarBlocks::addAll);
	}

	/** Etapa de "voltar e entregar tudo que estiver pendente" (ordem RETURN). */
	private static final class ReturnAllStep extends Step {
		private final CompanionEntity companion;

		ReturnAllStep(CompanionEntity companion) {
			this.companion = companion;
		}

		@Override
		public StepResult tick(TaskContext ctx) {
			for (Order o : companion.orders().all()) {
				if (o.reservesItems() && o.remainingToDeliver() > 0 && o.countIn(companion) > 0) {
					return StepResult.push(new ReturnDeliverStep(o));
				}
			}
			ServerPlayer owner = companion.ownerInLevel();
			if (owner == null || companion.distanceTo(owner) <= 4) {
				return StepResult.success();
			}
			ctx.brain().navigator().setGoal(owner.blockPosition(), 2.5, 1.4);
			if (ctx.brain().navigator().tick() == dev.autonomouscompanions.nav.Navigator.Status.FAILED) {
				return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("detail.autonomouscompanions.no_route_owner"));
			}
			return StepResult.running();
		}

		@Override
		public Component describe() {
			return Component.translatable("step.autonomouscompanions.return");
		}

		@Override
		public CompanionState state() {
			return CompanionState.RETURNING;
		}
	}
}
