package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.combat.ThreatTracker;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.SafeSpot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Prioridade máxima: evitar a morte iminente. Foge de lava/fogo, de afogamento, da neve fofa (congelamento), de creepers prestes a explodir, de
 * grupos grandes ou do Warden e, com pouca vida, se afasta das ameaças (preferindo ir para perto do dono).
 */
public final class FleeBehavior extends Behavior {
	private enum Reason {
		NONE,
		FIRE,
		DROWNING,
		FREEZING,
		CREEPER,
		LOW_HEALTH,
		OVERWHELMED
	}

	private Reason reason = Reason.NONE;
	private @Nullable Vec3 fleeTarget;
	private int safeTicks;
	private int retarget;
	private final dev.autonomouscompanions.mining.BlockBreaker breaker = new dev.autonomouscompanions.mining.BlockBreaker();

	public FleeBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	private Reason evaluate() {
		ThreatTracker t = brain.threats();
		if ((companion.isInLava() || companion.isOnFire() && !companion.isInWater()) && !companion.fireImmune()) {
			return Reason.FIRE;
		}
		if (companion.isEyeInFluid(FluidTags.WATER) && companion.getAirSupply() < companion.getMaxAirSupply() / 3) {
			return Reason.DROWNING;
		}
		if ((companion.isInPowderSnow || companion.wasInPowderSnow) && companion.canFreeze()) {
			return Reason.FREEZING;
		}
		if (t.creeperDanger()) {
			return Reason.CREEPER;
		}
		if (t.overwhelming() && companion.getHealth() < companion.getMaxHealth() * 0.8f) {
			return Reason.OVERWHELMED;
		}
		float threshold = (float) (ConfigManager.get().vidaParaFugirPercentual * companion.role().fleeThresholdMultiplier);
		if (companion.getHealth() <= companion.getMaxHealth() * threshold && t.hostilesNearSelf() > 0) {
			return Reason.LOW_HEALTH;
		}
		return Reason.NONE;
	}

	@Override
	public int priority(ServerLevel level) {
		Reason r = evaluate();
		if (r != Reason.NONE) {
			return Priorities.FLEE;
		}
		// Continua fugindo até ficar seguro por alguns segundos.
		return brain.active() == this && safeTicks < 60 ? Priorities.FLEE : 0;
	}

	@Override
	public void start(ServerLevel level) {
		fleeTarget = null;
		safeTicks = 0;
		retarget = 0;
		if (companion.isSleeping()) {
			companion.stopSleeping();
		}
	}

	@Override
	public boolean tick(ServerLevel level) {
		Reason r = evaluate();
		if (r != Reason.NONE) {
			reason = r;
			safeTicks = 0;
		} else if (++safeTicks >= 60) {
			return false;
		}
		companion.setCompanionState(CompanionState.RECOVERING_FROM_DANGER, Component.translatable("detail.autonomouscompanions.flee." + reason.name().toLowerCase()));
		switch (reason) {
			case FIRE -> escapeFire(level);
			case FREEZING -> escapeFreezing(level);
			case DROWNING -> {
				companion.getJumpControl().jump();
				if (--retarget <= 0) {
					retarget = 20;
					BlockPos land = SafeSpot.findNear(level, companion.blockPosition(), 1, 8, 6, companion.getType());
					if (land != null) {
						companion.getNavigation().moveTo(land.getX() + 0.5, land.getY(), land.getZ() + 0.5, 1.4);
					}
				}
			}
			default -> {
				runAway(level);
				eatWhileRunning();
			}
		}
		return true;
	}

	/** Ferida e fugindo: come correndo (como uma jogadora) quando a ameaça mais próxima não está colada. */
	private void eatWhileRunning() {
		if (companion.isUsingItem() || companion.needs().hunger() >= dev.autonomouscompanions.survival.CompanionNeeds.MAX_HUNGER) {
			return;
		}
		LivingEntity threat = brain.threats().nearestThreat();
		if (threat != null && threat.distanceTo(companion) < 5) {
			return;
		}
		if (dev.autonomouscompanions.survival.FoodHelper.startEating(companion, true)) {
			companion.debug("Fugindo e comendo para recuperar a vida.");
		}
	}

	private void escapeFire(ServerLevel level) {
		if (--retarget <= 0) {
			retarget = 10;
			BlockPos safe = SafeSpot.findNear(level, companion.blockPosition(), 1, 6, 3, companion.getType());
			if (safe != null) {
				companion.getNavigation().moveTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, 1.6);
			}
		}
		companion.getJumpControl().jump();
	}

	/**
	 * Neve fofa: a navegação do jogo não sai dela (o bloco é proibido no caminho, então nenhum caminho começa ali dentro) e
	 * ela ficava parada até congelar — três mortes "congelou até a morte" numa sessão de jogo. Agora, como uma jogadora,
	 * quebra a neve fofa que a prende (de cima para baixo, com o tempo real de quebra e a melhor ferramenta que tiver) e vai
	 * para a borda firme mais próxima. Se sobrar um buraco, a fuga de buraco (pilar ou degraus) termina o serviço.
	 */
	private void escapeFreezing(ServerLevel level) {
		BlockPos snow = null;
		net.minecraft.world.phys.AABB box = companion.getBoundingBox();
		for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			var s = level.getBlockState(p);
			if (s.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW) && (snow == null || p.getY() > snow.getY())
				&& dev.autonomouscompanions.world.BlockPolicy.canBreak(level, p, s, dev.autonomouscompanions.world.BlockPolicy.Purpose.TUNNEL, companion.getUUID())) {
				snow = p.immutable();
			}
		}
		if (snow != null) {
			var state = level.getBlockState(snow);
			if (!snow.equals(breaker.target())) {
				dev.autonomouscompanions.inventory.Gear.equipBestToolFor(companion, state, level, false);
				companion.debug("Presa na neve fofa (congelando: " + companion.getTicksFrozen() + "): quebrando a neve em " + snow.toShortString() + ".");
			}
			breaker.begin(snow, state);
			if (breaker.tick(level, companion) == dev.autonomouscompanions.mining.BlockBreaker.Status.BROKEN) {
				dev.autonomouscompanions.mining.BlockBreaker.storeDrops(level, companion, snow, breaker.lastDrops());
			}
			return;
		}
		if (--retarget <= 0) {
			retarget = 10;
			BlockPos safe = SafeSpot.findNear(level, companion.blockPosition(), 1, 4, 3, companion.getType());
			fleeTarget = safe == null ? null : Vec3.atBottomCenterOf(safe);
		}
		if (fleeTarget != null) {
			companion.getMoveControl().setWantedPosition(fleeTarget.x, fleeTarget.y, fleeTarget.z, 1.0);
			if (fleeTarget.y > companion.getY() + 0.5) {
				companion.getJumpControl().jump();
			}
		}
	}

	private void runAway(ServerLevel level) {
		LivingEntity threat = brain.threats().nearestThreat();
		if (--retarget <= 0 || fleeTarget == null || companion.getNavigation().isDone()) {
			retarget = 20;
			Vec3 from = threat != null ? threat.position() : companion.position();
			ServerPlayer owner = companion.ownerInLevel();
			Vec3 target = null;
			// Prefere fugir em direção ao dono se ele estiver longe da ameaça.
			if (owner != null && (threat == null || owner.distanceToSqr(threat) > 12 * 12) && owner.distanceToSqr(companion) > 9) {
				target = owner.position();
			}
			if (target == null) {
				target = LandRandomPos.getPosAway(companion, 16, 7, from);
			}
			fleeTarget = target;
			if (target != null) {
				companion.getNavigation().moveTo(target.x, target.y, target.z, 1.6);
			}
		}
		companion.setSprinting(true);
	}

	@Override
	public void stop(ServerLevel level) {
		companion.setSprinting(false);
		// Se ainda está comendo, termina de comer (o próximo comportamento guarda o resto da comida).
		if (!companion.isUsingItem() && companion.mainHandItem().has(net.minecraft.core.component.DataComponents.FOOD)) {
			dev.autonomouscompanions.inventory.Gear.stowMainHand(companion);
		}
		companion.getNavigation().stop();
		breaker.reset(level, companion);
		reason = Reason.NONE;
	}
}
