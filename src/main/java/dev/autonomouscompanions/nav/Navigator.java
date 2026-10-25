package dev.autonomouscompanions.nav;

import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Camada de navegação sobre o A* do jogo ({@code GroundPathNavigation}). Acrescenta: segmentação de viagens longas em
 * pontos intermediários, limitação de recálculo (no máximo um caminho a cada {@value #REPATH_INTERVAL} ticks),
 * detecção de travamento, tentativas alternativas e falha explícita depois de várias tentativas (nunca fica
 * eternamente tentando um caminho impossível).
 */
public final class Navigator {
	public enum Status {
		MOVING,
		ARRIVED,
		FAILED
	}

	private static final int REPATH_INTERVAL = 20;
	private static final int SEGMENT = 32;

	private final CompanionEntity mob;
	private @Nullable BlockPos goal;
	private double reach;
	private double speed = 1.0;
	private int repathCooldown;
	private int failures;
	private int stuckTicks;
	private Vec3 lastCheckPos = Vec3.ZERO;
	private int checkTimer;
	private double bestDistance = Double.MAX_VALUE;
	private int noImprovementTicks;
	private int detourIndex;
	private @Nullable BlockPos currentTarget;

	public Navigator(CompanionEntity mob) {
		this.mob = mob;
	}

	public @Nullable BlockPos goal() {
		return goal;
	}

	public void setGoal(BlockPos goal, double reach, double speed) {
		if (this.goal == null || !this.goal.equals(goal) || Math.abs(this.reach - reach) > 0.01) {
			this.goal = goal.immutable();
			this.reach = reach;
			this.failures = 0;
			this.stuckTicks = 0;
			this.repathCooldown = 0;
			this.bestDistance = Double.MAX_VALUE;
			this.noImprovementTicks = 0;
			this.detourIndex = 0;
			this.currentTarget = null;
		}
		this.speed = speed;
	}

	public void setSpeed(double speed) {
		this.speed = speed;
		this.mob.getNavigation().setSpeedModifier(speed);
	}

	public void clear() {
		this.goal = null;
		this.mob.setSprinting(false);
		this.currentTarget = null;
		this.mob.getNavigation().stop();
	}

	public int failures() {
		return failures;
	}

	public boolean isNear(BlockPos pos, double distance) {
		return distanceTo(pos) <= distance;
	}

	public double distanceTo(BlockPos pos) {
		Vec3 p = mob.position();
		double dx = pos.getX() + 0.5 - p.x;
		double dy = pos.getY() - p.y;
		double dz = pos.getZ() + 0.5 - p.z;
		return Math.sqrt(dx * dx + dy * dy * 1.5 + dz * dz);
	}

	public Status tick() {
		if (goal == null) {
			return Status.ARRIVED;
		}
		double dist = distanceTo(goal);
		if (dist <= reach + 0.35) {
			mob.getNavigation().stop();
			mob.setSprinting(false);
			return Status.ARRIVED;
		}

		// Trechos longos: corre (30% mais rápido, como a jogadora); perto do destino ou na água, anda.
		// Passeio (velocidade de caminhada, < 1) nunca vira corrida: antes ela saía correndo ao explorar à toa.
		boolean run = dist > 10 && speed >= 1.0 && !mob.isInWater() && !mob.isUsingItem();
		if (mob.isSprinting() != run) {
			mob.setSprinting(run);
		}
		// Detecção de travamento a cada 20 ticks.
		if (++checkTimer >= 20) {
			checkTimer = 0;
			double moved = mob.position().distanceTo(lastCheckPos);
			lastCheckPos = mob.position();
			if (moved < 0.35) {
				stuckTicks += 20;
				if (stuckTicks >= 40) {
					// Tenta pular obstáculo e força recálculo.
					mob.getJumpControl().jump();
					repathCooldown = 0;
				}
			} else {
				stuckTicks = 0;
			}
			if (dist < bestDistance - 0.75) {
				bestDistance = dist;
				noImprovementTicks = 0;
			} else {
				noImprovementTicks += 20;
			}
		}

		if (--repathCooldown <= 0 || mob.getNavigation().isDone()) {
			if (repathCooldown <= 0) {
				repath(dist);
				repathCooldown = REPATH_INTERVAL;
			}
		}

		if (noImprovementTicks >= 200 || stuckTicks >= 160) {
			failures++;
			noImprovementTicks = 0;
			stuckTicks = 0;
			detourIndex++;
			repathCooldown = 0;
		}
		if (failures >= 5) {
			mob.getNavigation().stop();
			return Status.FAILED;
		}
		return Status.MOVING;
	}

	private void repath(double dist) {
		ServerLevel level = (ServerLevel) mob.level();
		BlockPos target = goal;
		if (dist > SEGMENT + 8 || detourIndex > 0) {
			target = intermediate(level, dist);
		}
		if (target == null) {
			failures++;
			return;
		}
		int accuracy = target.equals(goal) ? Math.max(0, (int) Math.floor(reach)) : 2;
		Path path = mob.getNavigation().createPath(target, accuracy);
		mob.brain().metrics().pathComputed();
		if (path == null) {
			failures++;
			return;
		}
		// Caminho parcial que não aproxima o suficiente conta como falha leve.
		if (!path.canReach() && path.getEndNode() != null) {
			BlockPos end = path.getEndNode().asBlockPos();
			if (distance(end, goal) >= dist - 0.5 && path.getNodeCount() <= 1) {
				failures++;
				detourIndex++;
				return;
			}
		}
		currentTarget = target;
		mob.getNavigation().moveTo(path, speed);
	}

	/** Ponto intermediário na direção do objetivo; com desvios laterais crescentes após falhas. */
	private @Nullable BlockPos intermediate(ServerLevel level, double dist) {
		Vec3 from = mob.position();
		double dx = goal.getX() + 0.5 - from.x;
		double dz = goal.getZ() + 0.5 - from.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		if (horiz < 1.0e-3) {
			return goal;
		}
		double angle = Math.atan2(dz, dx);
		if (detourIndex > 0) {
			double sign = (detourIndex % 2 == 0) ? 1 : -1;
			angle += sign * Math.toRadians(35 * ((detourIndex + 1) / 2));
		}
		double step = Math.min(SEGMENT, horiz);
		int x = Mth.floor(from.x + Math.cos(angle) * step);
		int z = Mth.floor(from.z + Math.sin(angle) * step);
		BlockPos column = new BlockPos(x, 0, z);
		if (!level.hasChunkAt(column)) {
			// Mantém-se dentro da área carregada.
			step = Math.min(step, 12);
			x = Mth.floor(from.x + Math.cos(angle) * step);
			z = Mth.floor(from.z + Math.sin(angle) * step);
			column = new BlockPos(x, 0, z);
			if (!level.hasChunkAt(column)) {
				return null;
			}
		}
		int y;
		if (horiz <= SEGMENT && detourIndex == 0) {
			y = goal.getY();
		} else if (goal.getY() < from.y - 8 && horiz < 24) {
			y = Mth.floor(from.y);
		} else {
			y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (Math.abs(y - from.y) > 24 && Math.abs(goal.getY() - from.y) < 16) {
				y = Mth.floor(from.y);
			}
		}
		return new BlockPos(x, y, z);
	}

	private static double distance(BlockPos a, BlockPos b) {
		return Math.sqrt(a.distSqr(b));
	}

	public @Nullable BlockPos currentTarget() {
		return currentTarget;
	}

	public static int maxNodes() {
		return ConfigManager.get().maxNosCaminho;
	}
}
