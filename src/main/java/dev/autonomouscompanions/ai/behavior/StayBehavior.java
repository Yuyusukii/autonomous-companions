package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Modo "aguardar aqui": permanece perto da posição marcada em vez de seguir o dono. */
public final class StayBehavior extends Behavior {
	public StayBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		return companion.isNamed() && companion.settings().stay && companion.settings().stayPos != null ? Priorities.STAY : 0;
	}

	@Override
	public boolean tick(ServerLevel level) {
		BlockPos pos = companion.settings().stayPos;
		if (pos == null || !companion.settings().stay) {
			return false;
		}
		companion.setCompanionState(CompanionState.STAYING, Component.translatable("detail.autonomouscompanions.staying", pos.getX(), pos.getY(), pos.getZ()));
		if (brain.navigator().distanceTo(pos) > 2.5) {
			brain.navigator().setGoal(pos, 1.0, 1.0);
			brain.navigator().tick();
		} else {
			brain.navigator().clear();
			if (companion.tickCount % 60 == 0) {
				companion.getLookControl().setLookAt(companion.getX() + companion.getRandom().nextGaussian() * 5, companion.getEyeY(),
					companion.getZ() + companion.getRandom().nextGaussian() * 5);
			}
		}
		return true;
	}
}
