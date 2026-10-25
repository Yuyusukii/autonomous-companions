package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.nav.SafeSpot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Comportamento padrão: acompanhar o dono a uma distância confortável, sem bloquear portas, escadas e corredores,
 * contornando obstáculos pela navegação real. Se ficar muito longe por vontade própria, volta (limite de exploração ociosa).
 */
public final class FollowOwnerBehavior extends Behavior {
	private int repathTimer;
	private @Nullable BlockPos lastGoal;
	private int asideCooldown;

	public FollowOwnerBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		if (!companion.isNamed() || companion.settings().stay) {
			return 0;
		}
		ServerPlayer owner = companion.ownerInLevel();
		if (owner == null) {
			return 0;
		}
		double horizontal = Math.sqrt(companion.distanceToSqr(owner.getX(), companion.getY(), owner.getZ()));
		if (brain.isReturningAfterOrder()) {
			if (companion.distanceTo(owner) > companion.settings().followMax) {
				return Priorities.RETURN_AFTER_ORDER;
			}
			brain.endReturnAfterOrder();
		}
		if (horizontal > ConfigManager.get().limiteExploracaoOciosa) {
			return Priorities.RETURN_TOO_FAR;
		}
		return Priorities.FOLLOW;
	}

	@Override
	public void start(ServerLevel level) {
		repathTimer = 0;
		lastGoal = null;
	}

	@Override
	public boolean tick(ServerLevel level) {
		ServerPlayer owner = companion.ownerInLevel();
		if (owner == null) {
			return false;
		}
		var settings = companion.settings();
		double dist = companion.distanceTo(owner);
		companion.setCompanionState(CompanionState.FOLLOWING, Component.translatable("detail.autonomouscompanions.following", owner.getDisplayName()));

		Navigator nav = brain.navigator();
		if (dist > settings.followMax) {
			double speed = dist > 16 ? 1.55 : dist > 9 ? 1.25 : 1.0;
			BlockPos ownerPos = owner.blockPosition();
			if (lastGoal == null || --repathTimer <= 0 || lastGoal.distSqr(ownerPos) > 4) {
				nav.setGoal(ownerPos, settings.followMin, speed);
				lastGoal = ownerPos;
				repathTimer = 10;
			}
			nav.setSpeed(speed);
			Navigator.Status status = nav.tick();
			boolean noRoute = status == Navigator.Status.FAILED;
			if (noRoute) {
				companion.setCompanionState(CompanionState.NO_ROUTE, Component.translatable("detail.autonomouscompanions.no_route_owner"));
				nav.clear();
				lastGoal = null;
			}
			brain.tickNoRouteRecovery(level, noRoute);
			return true;
		}

		brain.clearNoRoute();
		nav.clear();
		lastGoal = null;
		if (asideCooldown > 0) {
			asideCooldown--;
		}
		// Muito perto ou parada em local de passagem: sai do caminho.
		if (asideCooldown <= 0 && (dist < 1.4 || isObstructive(level, companion.blockPosition()))) {
			BlockPos aside = findAsideSpot(level, owner);
			if (aside != null) {
				companion.getNavigation().moveTo(aside.getX() + 0.5, aside.getY(), aside.getZ() + 0.5, 1.0);
			}
			asideCooldown = 40;
		}
		if (companion.tickCount % 20 == 0 && dist < 10) {
			companion.getLookControl().setLookAt(owner, 30.0F, 30.0F);
		}
		return true;
	}

	/** Portas, portões, escadas de mão, degraus e corredores de 1 bloco são locais onde ela não deve ficar parada. */
	public static boolean isObstructive(ServerLevel level, BlockPos feet) {
		BlockState here = level.getBlockState(feet);
		BlockState below = level.getBlockState(feet.below());
		if (here.getBlock() instanceof DoorBlock || here.getBlock() instanceof FenceGateBlock || here.getBlock() instanceof LadderBlock
			|| here.getBlock() instanceof TrapDoorBlock || below.getBlock() instanceof StairBlock || here.getBlock() instanceof StairBlock) {
			return true;
		}
		boolean xBlocked = solid(level, feet.east()) && solid(level, feet.west());
		boolean zBlocked = solid(level, feet.north()) && solid(level, feet.south());
		return xBlocked || zBlocked;
	}

	private static boolean solid(ServerLevel level, BlockPos pos) {
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private @Nullable BlockPos findAsideSpot(ServerLevel level, ServerPlayer owner) {
		BlockPos center = owner.blockPosition();
		Direction facing = owner.getDirection();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (int r = 2; r <= 4; r++) {
			for (BlockPos p : SafeSpot.ring(center, r)) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos c = p.above(dy);
					if (!SafeSpot.isSafeStand(level, c, companion.getType()) || isObstructive(level, c)) {
						continue;
					}
					// Prefere ficar fora da frente do dono (onde ele costuma andar).
					double front = (c.getX() - center.getX()) * facing.getStepX() + (c.getZ() - center.getZ()) * facing.getStepZ();
					double score = c.distSqr(companion.blockPosition()) + (front > 0 ? 6 : 0);
					if (score < bestScore) {
						bestScore = score;
						best = c;
					}
				}
			}
			if (best != null) {
				return best;
			}
		}
		return null;
	}

	@Override
	public void stop(ServerLevel level) {
		brain.navigator().clear();
	}
}
