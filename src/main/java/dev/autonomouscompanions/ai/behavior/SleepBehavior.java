package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.nav.SafeSpot;
import dev.autonomouscompanions.survival.CompanionNeeds;
import dev.autonomouscompanions.task.exec.steps.Stations;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.jspecify.annotations.Nullable;

/**
 * Descanso. Com cansaço alto (ou cansada à noite, sem missão urgente), procura uma cama livre e segura — nunca a cama
 * de nascimento do dono —, coloca a própria cama se tiver uma, ou descansa sentada em local seguro como alternativa.
 * Dormir não altera a hora do mundo: só jogadores contam para pular a noite.
 */
public final class SleepBehavior extends Behavior {
	private @Nullable BlockPos bed;
	private boolean resting;
	private int searchCooldown;
	private int dangerCheck;

	public SleepBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	private boolean night(ServerLevel level) {
		return level.isDarkOutside();
	}

	private boolean danger(ServerLevel level) {
		return brain.threats().hostilesNearSelf() > 0 || brain.threats().target() != null;
	}

	@Override
	public int priority(ServerLevel level) {
		if (!ConfigManager.get().sonoHabilitado || !companion.isNamed()) {
			return 0;
		}
		CompanionNeeds needs = companion.needs();
		boolean activeSleep = brain.active() == this && (companion.isSleeping() || resting || bed != null);
		// Descanso pedido pela dona: até zerar o cansaço (no mínimo 30 s). Uma ordem nova encerra.
		boolean requested = brain.restRequested();
		if (requested && companion.orders().active() != null) {
			brain.clearRest();
			requested = false;
		}
		boolean requestDone = requested && needs.fatigue() < 1 && level.getGameTime() - brain.restSince() > 20 * 30;
		if (activeSleep) {
			boolean rested = requested ? requestDone : needs.fatigue() < 3 || !night(level) && needs.fatigue() < 30;
			if (rested) {
				brain.clearRest();
				return 0;
			}
			if (danger(level)) {
				// Levanta para lutar, mas lembra que estava descansando: volta quando o perigo passar.
				brain.requestRest(level);
				return 0;
			}
			return Priorities.EXHAUSTED_SLEEP;
		}
		if (danger(level)) {
			return 0;
		}
		if (requested) {
			if (requestDone) {
				brain.clearRest();
				return 0;
			}
			return Priorities.EXHAUSTED_SLEEP;
		}
		if (needs.isExhausted()) {
			return Priorities.EXHAUSTED_SLEEP;
		}
		var order = companion.orders().active();
		boolean urgent = order != null && order.urgent();
		if (needs.isTired() && night(level) && !urgent) {
			return order != null ? Priorities.ORDER + 1 : Priorities.NIGHT_SLEEP;
		}
		return 0;
	}

	@Override
	public void start(ServerLevel level) {
		bed = null;
		resting = false;
		searchCooldown = 0;
	}

	@Override
	public boolean tick(ServerLevel level) {
		CompanionNeeds needs = companion.needs();
		if (companion.isSleeping()) {
			companion.setCompanionState(CompanionState.SLEEPING, Component.translatable("detail.autonomouscompanions.sleeping", (int) needs.fatigue()));
			if (++dangerCheck % 20 == 0 && danger(level)) {
				companion.stopSleeping();
				brain.requestRest(level); // volta a dormir quando o perigo passar
				return false;
			}
			return true;
		}
		if (resting) {
			companion.setCompanionState(CompanionState.RESTING, Component.translatable("detail.autonomouscompanions.resting", (int) needs.fatigue()));
			companion.getNavigation().stop();
			if (danger(level)) {
				brain.requestRest(level); // volta a descansar quando o perigo passar
				return false;
			}
			return true;
		}
		if (bed == null) {
			if (--searchCooldown > 0) {
				return true;
			}
			searchCooldown = 40;
			bed = findBed(level);
			if (bed == null) {
				bed = placeOwnBed(level);
			}
			if (bed == null) {
				// Alternativa explícita: descansar sentada em local seguro.
				if (SafeSpot.isSafeStand(level, companion.blockPosition(), companion.getType())) {
					resting = true;
					companion.debug("Sem cama disponível: descansando sentada.");
				}
				return true;
			}
		}
		companion.setCompanionState(CompanionState.SEEKING_REST, Component.translatable("detail.autonomouscompanions.seeking_bed"));
		BlockState state = level.getBlockState(bed);
		if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.OCCUPIED)) {
			bed = null;
			return true;
		}
		Navigator nav = brain.navigator();
		if (nav.distanceTo(bed) > 2.0) {
			nav.setGoal(bed, 1.5, 1.0);
			if (nav.tick() == Navigator.Status.FAILED) {
				nav.clear();
				bed = null;
				searchCooldown = 100;
			}
			return true;
		}
		nav.clear();
		BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD ? bed : bed.relative(state.getValue(BedBlock.FACING));
		if (!companion.startSleeping(head)) {
			bed = null;
		}
		return true;
	}

	private @Nullable BlockPos ownerRespawnBed() {
		ServerPlayer owner = companion.ownerAnywhere();
		if (owner == null || owner.getRespawnConfig() == null) {
			return null;
		}
		return owner.getRespawnConfig().respawnData().globalPos().pos();
	}

	private @Nullable BlockPos findBed(ServerLevel level) {
		BlockPos ownerBed = ownerRespawnBed();
		Optional<BlockPos> found = level.getPoiManager().findClosest(h -> h.is(PoiTypes.HOME), p -> {
			BlockState s = level.getBlockState(p);
			if (!(s.getBlock() instanceof BedBlock) || s.getValue(BedBlock.OCCUPIED)) {
				return false;
			}
			if (ownerBed != null && (p.equals(ownerBed) || p.relative(BedBlock.getConnectedDirection(s)).equals(ownerBed))) {
				return false;
			}
			return level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(p).inflate(10),
				m -> m instanceof net.minecraft.world.entity.monster.Enemy).isEmpty();
		}, companion.blockPosition(), 48, PoiManager.Occupancy.ANY);
		return found.orElse(null);
	}

	/** Coloca a própria cama (se tiver uma livre no inventário) em um local plano e seguro. */
	private @Nullable BlockPos placeOwnBed(ServerLevel level) {
		var inv = companion.inventory();
		int slot = inv.findSlot(s -> s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof BedBlock && inv.countFree(s.getItem()) > 0);
		if (slot < 0) {
			return null;
		}
		ItemStack bedItem = inv.getItem(slot);
		Block block = ((BlockItem) bedItem.getItem()).getBlock();
		BlockPos feet = companion.blockPosition();
		for (int r = 1; r <= 3; r++) {
			for (BlockPos p : SafeSpot.ring(feet, r)) {
				for (Direction dir : Direction.Plane.HORIZONTAL) {
					BlockPos headPos = p.relative(dir);
					if (!canPlaceBedHalf(level, p) || !canPlaceBedHalf(level, headPos)) {
						continue;
					}
					BlockState foot = block.defaultBlockState().setValue(BedBlock.FACING, dir).setValue(BedBlock.PART, BedPart.FOOT);
					BlockState head = foot.setValue(BedBlock.PART, BedPart.HEAD);
					if (Stations.place(level, companion, p, foot)) {
						level.setBlock(headPos, head, Block.UPDATE_ALL);
						inv.removeFree(bedItem.getItem(), 1);
						companion.debug("Colocou a própria cama em " + p.toShortString());
						return p;
					}
				}
			}
		}
		return null;
	}

	private boolean canPlaceBedHalf(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		BlockPos below = p.below();
		return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty() && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
			&& !level.getBlockState(below).is(BlockTags.LEAVES) && level.getBlockState(p.above()).isAir()
			&& !dev.autonomouscompanions.coop.Cooperation.get(level.getServer()).isInsideProtectedArea(level, p)
			&& level.getEntities(companion, new net.minecraft.world.phys.AABB(p)).isEmpty() && !p.equals(companion.blockPosition());
	}

	@Override
	public void stop(ServerLevel level) {
		if (companion.isSleeping()) {
			companion.stopSleeping();
		}
		resting = false;
		bed = null;
		brain.navigator().clear();
	}
}
