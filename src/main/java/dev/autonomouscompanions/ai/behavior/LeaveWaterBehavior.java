package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.SafeSpot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Sem nada mais importante para fazer, ela não fica parada dentro da água: nada até a terra firme mais próxima.
 * (Tarefas que passam pela água — ordens, entregas, fuga — têm prioridade maior e não são interrompidas.)
 */
public final class LeaveWaterBehavior extends Behavior {
	private @Nullable BlockPos land;
	private int ticks;

	public LeaveWaterBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		if (brain.active() == this) {
			return ticks < 20 * 20 ? Priorities.LEAVE_WATER : 0;
		}
		// Se a dona está nadando por perto, acompanhar na água é o certo.
		var owner = companion.ownerInLevel();
		if (owner != null && owner.isInWater() && owner.distanceTo(companion) < 24) {
			return 0;
		}
		return companion.isInWater() && companion.isNamed() && !companion.isPassenger() ? Priorities.LEAVE_WATER : 0;
	}

	@Override
	public void start(ServerLevel level) {
		ticks = 0;
		land = SafeSpot.findNear(level, companion.blockPosition(), 1, 16, 6, companion.getType());
		companion.debug("Na água sem motivo: indo para a terra" + (land == null ? " (nenhuma por perto)" : " em " + land.toShortString()) + ".");
	}

	@Override
	public boolean tick(ServerLevel level) {
		ticks++;
		if (land == null || !companion.isInWater() && companion.onGround()) {
			return false;
		}
		companion.setCompanionState(CompanionState.TRAVELING, Component.translatable("detail.autonomouscompanions.leaving_water"));
		if (companion.getNavigation().isDone() || ticks % 40 == 0) {
			companion.getNavigation().moveTo(land.getX() + 0.5, land.getY(), land.getZ() + 0.5, 1.3);
		}
		if (companion.isInWater()) {
			companion.getJumpControl().jump(); // mantém a cabeça fora d'água enquanto nada
		}
		return true;
	}

	@Override
	public void stop(ServerLevel level) {
		companion.getNavigation().stop();
		land = null;
	}
}
