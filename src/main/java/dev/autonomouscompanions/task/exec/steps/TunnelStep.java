package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.mining.BlockBreaker;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.world.BlockPolicy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Abre uma passagem controlada (escada/túnel 1x2) até um ponto: avança um passo por vez, quebrando apenas blocos
 * naturais permitidos, recusando lava, construções e contêineres, e preenchendo buracos no piso com blocos comuns.
 * Usado para alcançar minérios enterrados e para mineração em galeria durante a exploração.
 */
public final class TunnelStep extends Step {
	private final BlockPos goal;
	private final double reach;
	private final int maxSteps;
	private int steps;
	private final List<BlockPos> toClear = new ArrayList<>();
	private @Nullable BlockPos nextFeet;
	private int moveTicks;
	private int stuckMoves;
	private boolean toolTried;

	public TunnelStep(BlockPos goal, double reach, int maxSteps) {
		this.goal = goal.immutable();
		this.reach = reach;
		this.maxSteps = maxSteps;
	}

	@Override
	public StepResult tick(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		if (!c.settings().allowTerrainChanges) {
			return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("reason.autonomouscompanions.terrain_forbidden"));
		}
		Vec3 eye = c.getEyePosition();
		if (eye.distanceTo(Vec3.atCenterOf(goal)) <= reach) {
			return StepResult.success();
		}
		// 1) Blocos pendentes a remover para o próximo passo.
		if (!toClear.isEmpty()) {
			BlockPos p = toClear.getFirst();
			BlockState s = level.getBlockState(p);
			if (s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty()) {
				toClear.removeFirst();
				ctx.breaker().reset(level, c);
				return StepResult.running();
			}
			if (!BlockPolicy.canBreak(level, p, s, BlockPolicy.Purpose.TUNNEL, c.getUUID())) {
				return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("reason.autonomouscompanions.tunnel_blocked", s.getBlock().getName()));
			}
			Gear.equipBestToolFor(c, s, level, false);
			if (s.requiresCorrectToolForDrops() && !c.mainHandItem().isCorrectToolForDrops(s)) {
				// Pedra, minérios...: só com a ferramenta certa (antes ela cavava pedra com a mão quando o alvo estava abaixo).
				// Tenta fazer a ferramenta uma vez; se não der, o túnel não é caminho e quem chamou escolhe outro alvo.
				if (!toolTried && !EnsureToolStep.ownsSuitable(ctx, s, true)) {
					toolTried = true;
					c.debug("Túnel: " + s.getBlock().getName().getString() + " pede ferramenta; providenciando antes de cavar.");
					return StepResult.push(new EnsureToolStep(s, true, java.util.Set.of()));
				}
				if (!EnsureToolStep.ownsSuitable(ctx, s, true)) {
					return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("reason.autonomouscompanions.need_tool_for", s.getBlock().getName()));
				}
				Gear.equipBestToolFor(c, s, level, false);
			}
			ctx.breaker().begin(p, s);
			BlockBreaker.Status st = ctx.breaker().tick(level, c);
			if (st == BlockBreaker.Status.BROKEN) {
				BlockBreaker.storeDrops(level, c, p, ctx.breaker().lastDrops());
				Order order = ctx.order();
				if (order != null) {
					order.progress().blocksMined++;
				}
				toClear.removeFirst();
			}
			return StepResult.running();
		}
		// 2) Mover para a posição já aberta.
		if (nextFeet != null) {
			if (c.blockPosition().equals(nextFeet) || c.position().distanceTo(Vec3.atBottomCenterOf(nextFeet)) < 0.45) {
				nextFeet = null;
				stuckMoves = 0;
				return StepResult.running();
			}
			c.getMoveControl().setWantedPosition(nextFeet.getX() + 0.5, nextFeet.getY(), nextFeet.getZ() + 0.5, 1.0);
			if (nextFeet.getY() > c.getBlockY()) {
				c.getJumpControl().jump();
			}
			if (++moveTicks > 40) {
				moveTicks = 0;
				if (++stuckMoves > 3) {
					return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("reason.autonomouscompanions.tunnel_stuck"));
				}
				nextFeet = null;
			}
			return StepResult.running();
		}
		if (++steps > maxSteps) {
			return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("reason.autonomouscompanions.tunnel_too_long"));
		}
		// 3) Planejar o próximo passo da escada/túnel.
		BlockPos feet = c.blockPosition();
		int dx = goal.getX() - feet.getX();
		int dz = goal.getZ() - feet.getZ();
		int dyNeeded = goal.getY() - feet.getY();
		Direction dir;
		if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
			dir = dx > 0 ? Direction.EAST : Direction.WEST;
		} else if (dz != 0) {
			dir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
		} else {
			dir = c.getDirection();
		}
		boolean horizontalDone = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
		int dy = dyNeeded < -1 ? -1 : dyNeeded > 1 ? 1 : 0;
		// Anda reto enquanto a distância horizontal sobra em relação ao desnível: só começa a escada quando precisa
		// (assim não sobe degraus no ar livre antes de chegar à encosta, nem desce cedo demais).
		int horizontalLeft = Math.max(Math.abs(dx), Math.abs(dz));
		if (dy != 0 && horizontalLeft > Math.abs(dyNeeded) + 1) {
			dy = 0;
		}
		BlockPos next;
		if (horizontalDone && dy == -1) {
			// Desce em espiral curta ao lado do alvo (nunca cava o bloco sob os próprios pés).
			next = feet.relative(dir).below();
		} else {
			next = feet.relative(dir).above(dy);
		}
		toClear.clear();
		if (dy > 0) {
			toClear.add(feet.above(2));
		}
		toClear.add(next.above());
		toClear.add(next);
		if (dy < 0) {
			toClear.add(next.above(2));
			// Escada de descida com 3 de altura, como a jogadora faz: com só 2, a cabeça bate no pulo e ela não consegue
			// subir de volta (ficava presa no fundo do buraco depois de pegar pedra). Não abre se houver líquido logo acima.
			BlockPos headroom = next.above(3);
			if (!level.getBlockState(headroom).getCollisionShape(level, headroom).isEmpty() && level.getFluidState(headroom.above()).isEmpty()
				&& level.getFluidState(headroom).isEmpty()
				&& BlockPolicy.canBreak(level, headroom, level.getBlockState(headroom), BlockPolicy.Purpose.TUNNEL, c.getUUID())) {
				toClear.add(headroom);
			}
		}
		// Evita abrir sob blocos que caem (areia/cascalho) sem tratá-los: inclui o bloco instável acima.
		BlockPos aboveHead = next.above(2);
		if (level.getBlockState(aboveHead).getBlock() instanceof FallingBlock && !toClear.contains(aboveHead)) {
			toClear.addFirst(aboveHead);
		}
		// Piso do próximo passo.
		BlockPos floor = next.below();
		BlockState floorState = level.getBlockState(floor);
		if (floorState.getFluidState().is(FluidTags.LAVA)) {
			return StepResult.fail(StepResult.FailureCode.DANGER, Component.translatable("reason.autonomouscompanions.lava_ahead"));
		}
		if (!floorState.isFaceSturdy(level, floor, Direction.UP)) {
			if (!placeFiller(ctx, floor)) {
				return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("reason.autonomouscompanions.gap_ahead"));
			}
		}
		nextFeet = next;
		moveTicks = 0;
		return StepResult.running();
	}

	/** Coloca um bloco comum (terra, pedregulho...) para fechar buracos no caminho. */
	public static boolean placeFiller(TaskContext ctx, BlockPos pos) {
		BlockState here = ctx.level().getBlockState(pos);
		if (!(here.isAir() || here.canBeReplaced())) {
			return false;
		}
		// Prefere o enchimento mais fácil de retirar depois (terra antes de pedra): andaimes e pontes são provisórios.
		var inv = ctx.inventory();
		BlockItem best = null;
		float bestHardness = Float.MAX_VALUE;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof BlockItem bi && isFiller(bi) && inv.countSpare(s.getItem()) > 0) {
				float hardness = bi.getBlock().defaultBlockState().getDestroySpeed(ctx.level(), pos);
				if (hardness < bestHardness) {
					bestHardness = hardness;
					best = bi;
				}
			}
		}
		if (best != null && Stations.placeExact(ctx.level(), ctx.companion(), pos, best.getBlock().defaultBlockState())) {
			inv.removeFree(best, 1);
			return true;
		}
		return false;
	}

	public static boolean isFiller(BlockItem bi) {
		BlockState s = bi.getBlock().defaultBlockState();
		return s.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || s.is(net.minecraft.world.level.block.Blocks.DIRT)
			|| s.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE) || s.is(net.minecraft.world.level.block.Blocks.NETHERRACK)
			|| s.is(net.minecraft.world.level.block.Blocks.TUFF) || s.is(net.minecraft.world.level.block.Blocks.ANDESITE)
			|| s.is(net.minecraft.world.level.block.Blocks.DIORITE) || s.is(net.minecraft.world.level.block.Blocks.GRANITE);
	}

	@Override
	public void stop(TaskContext ctx) {
		ctx.breaker().reset(ctx.level(), ctx.companion());
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.tunnel", goal.getX(), goal.getY(), goal.getZ());
	}

	@Override
	public CompanionState state() {
		return CompanionState.COLLECTING;
	}
}
