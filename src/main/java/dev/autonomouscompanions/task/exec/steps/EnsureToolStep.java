package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.crafting.CostModel;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Garante uma ferramenta capaz de minerar o bloco (exigida para drops, ou opcional para acelerar). Usa ferramentas já
 * possuídas antes de fabricar novas e escolhe a ferramenta mais barata de produzir entre todas as registradas.
 */
public final class EnsureToolStep extends CompositeStep {
	private final BlockState state;
	private final boolean requireCorrect;
	private final Set<Item> chain;
	private boolean pushed;

	public EnsureToolStep(BlockState state, boolean requireCorrect, Set<Item> chain) {
		this.state = state;
		this.requireCorrect = requireCorrect;
		this.chain = chain;
	}

	public static boolean ownsSuitable(TaskContext ctx, BlockState state, boolean requireCorrect) {
		ItemStack owned = Gear.bestOwnedFor(ctx.companion(), state, requireCorrect, false, ctx.level());
		if (owned == null) {
			return false;
		}
		// Ferramenta prestes a quebrar não conta como garantida para trabalhos longos.
		return !owned.isDamageableItem() || owned.getMaxDamage() - owned.getDamageValue() > 2;
	}

	@Override
	protected StepResult run(TaskContext ctx) {
		if (ownsSuitable(ctx, state, requireCorrect)) {
			Gear.equipBestToolFor(ctx.companion(), state, ctx.level(), false);
			return StepResult.success();
		}
		if (pushed) {
			return StepResult.fail(StepResult.FailureCode.IMPOSSIBLE, Component.translatable("reason.autonomouscompanions.tool_unavailable", state.getBlock().getName()));
		}
		CostModel costs = ctx.costs();
		Item best = null;
		double bestCost = CostModel.INF;
		for (Item tool : Gear.allToolItems()) {
			if (chain.contains(tool)) {
				continue;
			}
			ItemStack s = new ItemStack(tool);
			boolean ok = requireCorrect && state.requiresCorrectToolForDrops() ? s.isCorrectToolForDrops(state) : s.getDestroySpeed(state) > 1.0f;
			if (!ok) {
				continue;
			}
			double c = costs.unitCost(tool);
			// Desempate: ferramentas mais duráveis valem um pouco mais.
			c -= Math.min(2.0, s.getMaxDamage() / 1000.0);
			if (c < bestCost) {
				bestCost = c;
				best = tool;
			}
		}
		if (best == null || bestCost >= CostModel.INF / 2) {
			return StepResult.fail(StepResult.FailureCode.IMPOSSIBLE, Component.translatable("reason.autonomouscompanions.tool_unavailable", state.getBlock().getName()));
		}
		pushed = true;
		// A cadeia NÃO inclui a própria ferramenta: a obtenção a acrescenta ao planejar os ingredientes (evita ciclos sem
		// impedir que ela seja fabricada).
		ctx.companion().debug("Precisa de ferramenta para " + state.getBlock().getName().getString() + ": " + best);
		return StepResult.push(new ObtainStep(ItemTarget.of(best), ctx.inventory().countFree(best) + 1, false, chain));
	}

	@Override
	protected StepResult onFailure(TaskContext ctx, StepResult failure) {
		return failure;
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.tool", state.getBlock().getName());
	}

	@Override
	public CompanionState state() {
		return CompanionState.CRAFTING_TOOLS;
	}
}
