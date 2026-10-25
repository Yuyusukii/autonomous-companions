package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.Navigator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Atende ao apito: vai até a dona o mais rápido possível, correndo. Se não houver caminho, se estiver muito longe ou
 * se não chegar em 20 segundos, é trazida para um lugar seguro ao lado dela (último recurso).
 */
public final class RecallBehavior extends Behavior {
	private int ticks;
	private double bestDistance;
	private int noProgress;

	public RecallBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		return brain.isRecalled(level) ? Priorities.RECALL : 0;
	}

	@Override
	public void start(ServerLevel level) {
		ticks = 0;
		bestDistance = Double.MAX_VALUE;
		noProgress = 0;
		if (companion.isSleeping()) {
			companion.stopSleeping();
		}
	}

	@Override
	public boolean tick(ServerLevel level) {
		ServerPlayer owner = companion.ownerAnywhere();
		if (owner == null) {
			brain.endRecall();
			return false;
		}
		if (owner.level() != level) {
			brain.bringNearOwner(owner);
			brain.endRecall();
			brain.holdNearOwner(level);
			return false;
		}
		double dist = companion.distanceTo(owner);
		if (dist <= 3.0) {
			brain.navigator().clear();
			companion.setSprinting(false);
			companion.getLookControl().setLookAt(owner, 30.0F, 30.0F);
			brain.endRecall();
			brain.holdNearOwner(level);
			return false;
		}
		ticks++;
		companion.setCompanionState(CompanionState.RETURNING, Component.translatable("detail.autonomouscompanions.recalled", owner.getDisplayName()));
		if (dist < bestDistance - 0.5) {
			bestDistance = dist;
			noProgress = 0;
		} else {
			noProgress++;
		}
		Navigator nav = brain.navigator();
		nav.setGoal(owner.blockPosition(), 2.0, 1.6);
		companion.setSprinting(true);
		Navigator.Status status = nav.tick();
		if (dist > 64 || status == Navigator.Status.FAILED || noProgress > 100 || ticks > 20 * 20) {
			// Sem caminho, travada ou muito longe: traz para perto com segurança.
			if (brain.bringNearOwner(owner)) {
				brain.endRecall();
				brain.holdNearOwner(level);
				return false;
			}
		}
		return true;
	}

	@Override
	public void stop(ServerLevel level) {
		brain.navigator().clear();
		companion.setSprinting(false);
	}
}
