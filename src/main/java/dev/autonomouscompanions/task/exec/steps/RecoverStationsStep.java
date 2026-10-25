package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.mining.BlockBreaker;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Recolhe as estações que ela mesma colocou (bancada, fornalha vazia) para reaproveitá-las. Estações de outros
 * jogadores nunca são removidas: só as registradas pela própria companheira.
 */
public final class RecoverStationsStep extends Step {
	private @Nullable BlockPos current;
	private int ticks;

	@Override
	public StepResult tick(TaskContext ctx) {
		if (++ticks > 20 * 60) {
			return StepResult.success();
		}
		if (current == null) {
			var list = ctx.ownStations();
			while (!list.isEmpty()) {
				BlockPos p = list.getFirst();
				BlockState s = ctx.level().getBlockState(p);
				boolean ours = s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE);
				boolean near = ctx.companion().distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5) < 32 * 32;
				boolean empty = !(ctx.level().getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f) || f.isEmpty();
				if (ours && near && empty) {
					current = p;
					break;
				}
				list.removeFirst();
			}
			if (current == null) {
				return StepResult.success();
			}
		}
		Navigator nav = ctx.brain().navigator();
		if (nav.distanceTo(current) > 3.5) {
			nav.setGoal(current, 2.5, 1.0);
			if (nav.tick() == Navigator.Status.FAILED) {
				ctx.ownStations().remove(current);
				current = null;
				nav.clear();
			}
			return StepResult.running();
		}
		nav.clear();
		BlockState s = ctx.level().getBlockState(current);
		if (s.isAir()) {
			ctx.ownStations().remove(current);
			current = null;
			return StepResult.running();
		}
		Gear.equipBestToolFor(ctx.companion(), s, ctx.level(), false);
		ctx.breaker().begin(current, s);
		BlockBreaker.Status st = ctx.breaker().tick(ctx.level(), ctx.companion());
		if (st != BlockBreaker.Status.BREAKING) {
			if (st == BlockBreaker.Status.BROKEN) {
				BlockBreaker.storeDrops(ctx.level(), ctx.companion(), current, ctx.breaker().lastDrops());
			}
			ctx.ownStations().remove(current);
			current = null;
		}
		return StepResult.running();
	}

	@Override
	public void stop(TaskContext ctx) {
		ctx.breaker().reset(ctx.level(), ctx.companion());
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.recover_stations");
	}

	@Override
	public CompanionState state() {
		return CompanionState.COLLECTING;
	}
}
