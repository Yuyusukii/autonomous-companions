package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.entity.CompanionEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/** Antes de ser nomeada, a companheira fica parada no lugar olhando para o jogador mais próximo. */
public final class WaitInteractionBehavior extends Behavior {
	public WaitInteractionBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		return companion.isNamed() ? 0 : Priorities.WAIT_INTERACTION;
	}

	@Override
	public boolean tick(ServerLevel level) {
		if (companion.isNamed()) {
			return false;
		}
		companion.setCompanionState(CompanionState.WAITING_INTERACTION, Component.translatable("detail.autonomouscompanions.waiting_name"));
		companion.getNavigation().stop();
		if (companion.tickCount % 10 == 0) {
			Player nearest = level.getNearestPlayer(companion, 10.0);
			if (nearest != null) {
				companion.getLookControl().setLookAt(nearest, 30.0F, 30.0F);
			}
		}
		return true;
	}
}
