package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.crafting.CostModel;
import dev.autonomouscompanions.mining.DropIndex;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Garante que o inventário tenha {@code count} unidades do alvo. Consulta o planejador, tenta o método mais barato e,
 * se ele falhar, passa ao próximo. Itens da própria missão contam inclusive os reservados; itens intermediários só
 * contam unidades livres (nunca consome o que pertence a uma entrega).
 */
public class ObtainStep extends Step {
	private final ItemTarget target;
	private final int count;
	private final boolean missionItem;
	private final Set<Item> chain;
	private final boolean allowHunt;
	private @Nullable List<CostModel.Method> methods;
	private int methodIndex;
	private int noProgress;
	private int lastHave = -1;
	private @Nullable Component lastReason;
	private StepResult.@Nullable FailureCode lastCode;
	/** Falha do método principal (o primeiro do plano): é ela que explica por que não deu certo. */
	private StepResult.@Nullable FailureCode primaryCode;
	private @Nullable Component primaryReason;
	private int rounds;

	public ObtainStep(ItemTarget target, int count, boolean missionItem, Set<Item> chain) {
		this(target, count, missionItem, chain, true);
	}

	public ObtainStep(ItemTarget target, int count, boolean missionItem, Set<Item> chain, boolean allowHunt) {
		this.target = target;
		this.count = count;
		this.missionItem = missionItem;
		this.chain = chain;
		this.allowHunt = allowHunt;
	}

	public ItemTarget target() {
		return target;
	}

	public int count() {
		return count;
	}

	
	public void workingItems(java.util.Map<Item, Integer> out) {
		if (!missionItem) {
			for (Item i : target.items()) {
				out.merge(i, count, Math::max);
			}
		}
	}

	public int have(TaskContext ctx) {
		return target.count(ctx.companion(), missionItem);
	}

	@Override
	public StepResult tick(TaskContext ctx) {
		int have = have(ctx);
		if (have >= count) {
			return StepResult.success();
		}
		if (!DropIndex.isReady()) {
			DropIndex.ensureReady(ctx.level().getServer());
		}
		if (methods == null) {
			methods = ctx.costs().methods(target, count - have, chain, allowHunt);
			methodIndex = 0;
			ctx.companion().debug("Obter " + (count - have) + "x " + target.describe().getString() + ": " + methods.stream().limit(3).map(Object::toString).toList() + " [" + ctx.costs().budget() + "]");
			if (methods.isEmpty()) {
				return StepResult.fail(StepResult.FailureCode.IMPOSSIBLE, Explainer.whyImpossible(ctx, target));
			}
		}
		if (methodIndex >= methods.size()) {
			// Todos os métodos falharam: informa a falha do método principal (o primeiro do plano, o mais barato), que é o que
			// de fato bloqueou — ex.: "não achei minério de carvão" —, e não a da última alternativa tentada (antes vinha "os
			// ingredientes do bloco de carvão não podem ser obtidos", que confundia e impedia a recuperação certa).
			boolean usePrimary = primaryCode != null && primaryCode != StepResult.FailureCode.IMPOSSIBLE;
			Component reason = usePrimary ? primaryReason : lastReason != null ? lastReason : Explainer.whyImpossible(ctx, target);
			StepResult.FailureCode code = usePrimary ? primaryCode : lastCode != null ? lastCode : StepResult.FailureCode.IMPOSSIBLE;
			return StepResult.fail(code, reason != null ? reason : Explainer.whyImpossible(ctx, target));
		}
		if (++rounds > 200) {
			return StepResult.fail(StepResult.FailureCode.IMPOSSIBLE, Component.translatable("reason.autonomouscompanions.no_progress", target.describe()));
		}
		lastHave = have;
		Set<Item> nextChain = new HashSet<>(chain);
		nextChain.addAll(target.items());
		return StepResult.push(MethodSteps.create(ctx, methods.get(methodIndex), target, count, missionItem, nextChain));
	}

	@Override
	public void onChildFinished(TaskContext ctx, Step child, StepResult result) {
		ctx.invalidateCosts();
			if (result.isFailure()) {
				lastReason = result.reason();
				lastCode = result.code();
			if (methodIndex == 0 && primaryCode == null) {
				primaryCode = result.code();
				primaryReason = result.reason();
			}
			if (result.code() == StepResult.FailureCode.INVENTORY_FULL || result.code() == StepResult.FailureCode.CANCELLED
				|| result.code() == StepResult.FailureCode.DANGER) {
				// Não adianta trocar de método: propaga imediatamente.
				methodIndex = Integer.MAX_VALUE / 2;
				return;
			}
			methodIndex++;
			return;
		}
		int have = have(ctx);
		if (have <= lastHave) {
			if (++noProgress >= 2) {
				methodIndex++;
				noProgress = 0;
			}
		} else {
			noProgress = 0;
		}
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.obtain", count, target.describe());
	}

	@Override
	public CompanionState state() {
		return CompanionState.PLANNING;
	}
}
