package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.mining.BlockBreaker;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/** Desce do pilar provisório quebrando os próprios blocos, de cima para baixo, recuperando-os. */
public final class DismantlePillarStep extends Step {
	private int ticks;

	@Override
	public StepResult tick(TaskContext ctx) {
		var runtime = ctx.companion().brain().orders();
		BlockPos below = ctx.companion().blockPosition().below();
		if (!runtime.isPillarBlock(below) || ++ticks > 20 * 30) {
			return StepResult.success();
		}
		BlockState s = ctx.level().getBlockState(below);
		if (s.isAir()) {
			runtime.forgetPillarBlock(below);
			return StepResult.running();
		}
		Gear.equipBestToolFor(ctx.companion(), s, ctx.level(), false);
		ctx.breaker().begin(below, s);
		if (ctx.breaker().tick(ctx.level(), ctx.companion()) == BlockBreaker.Status.BROKEN) {
			BlockBreaker.storeDrops(ctx.level(), ctx.companion(), below, ctx.breaker().lastDrops());
			runtime.forgetPillarBlock(below);
			ticks = 0; // o limite de tempo vale por bloco
			runtime.noteActivity();
		}
		return StepResult.running();
	}

	@Override
	public void stop(TaskContext ctx) {
		ctx.breaker().reset(ctx.level(), ctx.companion());
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.dismantle");
	}

	@Override
	public CompanionState state() {
		return CompanionState.COLLECTING;
	}
}
