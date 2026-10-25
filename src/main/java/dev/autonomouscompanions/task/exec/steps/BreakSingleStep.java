package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.mining.BlockBreaker;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.world.BlockPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/** Remove um único bloco natural que obstrui o acesso (ex.: pedra na frente de um minério). */
public final class BreakSingleStep extends Step {
	private final BlockPos pos;
	private int ticks;
	private boolean toolTried;

	public BreakSingleStep(BlockPos pos) {
		this.pos = pos.immutable();
	}

	@Override
	public StepResult tick(TaskContext ctx) {
		BlockState s = ctx.level().getBlockState(pos);
		if (s.getCollisionShape(ctx.level(), pos).isEmpty()) {
			return StepResult.success();
		}
		if (!BlockPolicy.canBreak(ctx.level(), pos, s, BlockPolicy.Purpose.TUNNEL, ctx.companion().getUUID()) || ++ticks > 20 * 60) {
			return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("reason.autonomouscompanions.tunnel_blocked", s.getBlock().getName()));
		}
		if (s.requiresCorrectToolForDrops() && !EnsureToolStep.ownsSuitable(ctx, s, true)) {
			// Pedra e afins só com a ferramenta certa: tenta fazer uma vez; senão este bloco não é caminho.
			if (!toolTried) {
				toolTried = true;
				return StepResult.push(new EnsureToolStep(s, true, java.util.Set.of()));
			}
			return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("reason.autonomouscompanions.need_tool_for", s.getBlock().getName()));
		}
		Gear.equipBestToolFor(ctx.companion(), s, ctx.level(), false);
		ctx.breaker().begin(pos, s);
		BlockBreaker.Status st = ctx.breaker().tick(ctx.level(), ctx.companion());
		if (st == BlockBreaker.Status.BROKEN) {
			BlockBreaker.storeDrops(ctx.level(), ctx.companion(), pos, ctx.breaker().lastDrops());
			return StepResult.success();
		}
		if (st == BlockBreaker.Status.INVALID && !s.getCollisionShape(ctx.level(), pos).isEmpty()) {
			return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("reason.autonomouscompanions.tunnel_blocked", s.getBlock().getName()));
		}
		return StepResult.running();
	}

	@Override
	public void stop(TaskContext ctx) {
		ctx.breaker().reset(ctx.level(), ctx.companion());
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.clear_block");
	}

	@Override
	public CompanionState state() {
		return CompanionState.COLLECTING;
	}
}
