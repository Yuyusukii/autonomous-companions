package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.nav.SafeSpot;
import dev.autonomouscompanions.world.KnownResources;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Exploração moderada quando o dono está parado. Nunca escolhe pontos a mais de {@code idleRadius} blocos
 * horizontais do dono (limitado pela configuração, 100 por padrão) e interrompe ao ultrapassar o limite.
 */
public final class IdleExploreBehavior extends Behavior {
	private @Nullable BlockPos target;
	private int duration;
	private int lingerTicks;

	public IdleExploreBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		if (!companion.isNamed() || !companion.settings().idleExplore || companion.settings().stay) {
			return 0;
		}
		ServerPlayer owner = companion.ownerInLevel();
		// Só depois de a dona ficar parada por 30 s, e nunca logo depois do apito.
		if (owner == null || brain.ownerStillTicks() < 600 || brain.isHoldingNearOwner(level)) {
			return 0;
		}
		if (brain.active() == this) {
			return Priorities.EXPLORE + 6;
		}
		// Começa com probabilidade para não parecer mecânico.
		return companion.getRandom().nextInt(6) == 0 ? Priorities.EXPLORE + 6 : 0;
	}

	@Override
	public void start(ServerLevel level) {
		target = null;
		duration = 0;
		lingerTicks = 0;
	}

	@Override
	public boolean tick(ServerLevel level) {
		ServerPlayer owner = companion.ownerInLevel();
		if (owner == null || brain.ownerStillTicks() < 100) {
			return false;
		}
		// Passeio curto em volta da dona (no máximo 12 blocos), andando.
		int limit = Math.min(12, Math.min(companion.settings().idleRadius, ConfigManager.get().limiteExploracaoOciosa));
		double fromOwner = SafeSpot.horizontalDistance(companion.blockPosition(), owner.getX(), owner.getZ());
		if (fromOwner > limit + 2) {
			return false; // passa o controle para o acompanhamento, que a traz de volta
		}
		if (++duration > 20 * 60) {
			return false;
		}
		companion.setCompanionState(CompanionState.EXPLORING, Component.translatable("detail.autonomouscompanions.exploring", limit));
		if (target == null) {
			if (lingerTicks > 0) {
				lingerTicks--;
				if (lingerTicks % 30 == 0) {
					companion.getLookControl().setLookAt(companion.getX() + companion.getRandom().nextGaussian() * 4, companion.getEyeY(),
						companion.getZ() + companion.getRandom().nextGaussian() * 4);
				}
				return true;
			}
			target = pickTarget(level, owner, limit);
			if (target == null) {
				return false;
			}
			brain.navigator().setGoal(target, 1.5, 0.9);
		}
		Navigator.Status status = brain.navigator().tick();
		if (status != Navigator.Status.MOVING) {
			// Observa os arredores e registra recursos visíveis para a equipe.
			KnownResources.observeAround(level, companion.blockPosition(), 6);
			target = null;
			lingerTicks = 40 + companion.getRandom().nextInt(80);
		}
		return true;
	}

	private @Nullable BlockPos pickTarget(ServerLevel level, ServerPlayer owner, int limit) {
		for (int attempt = 0; attempt < 12; attempt++) {
			double angle = companion.getRandom().nextDouble() * Math.PI * 2;
			double r = 4 + companion.getRandom().nextDouble() * Math.min(limit - 2, 20 + attempt * 4);
			int x = (int) Math.floor(owner.getX() + Math.cos(angle) * r);
			int z = (int) Math.floor(owner.getZ() + Math.sin(angle) * r);
			BlockPos column = new BlockPos(x, 0, z);
			if (!level.hasChunkAt(column)) {
				continue;
			}
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos p = new BlockPos(x, y, z);
			if (Math.abs(y - owner.getY()) > 12) {
				continue;
			}
			if (SafeSpot.isSafeStand(level, p, companion.getType()) && SafeSpot.horizontalDistance(p, owner.getX(), owner.getZ()) <= limit) {
				return p;
			}
		}
		return null;
	}

	@Override
	public void stop(ServerLevel level) {
		brain.navigator().clear();
	}
}
