package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.world.BlockPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Sai de um buraco ou espaço fechado por conta própria, como uma jogadora faria:
 * <ol>
 *   <li>se tiver blocos de enchimento e espaço acima, sobe um pilar um bloco por vez;</li>
 *   <li>senão, cava degraus em direção ao destino (só blocos naturais, nunca construções);</li>
 *   <li>em último caso (40 s sem conseguir), é levada a um lugar seguro perto da dona.</li>
 * </ol>
 * Termina assim que existir caminho a pé até o destino.
 */
public final class EscapeStep extends Step {
	private final BlockPos target;
	private int ticks;
	private int climbs;
	private int steps;
	private int stepTicks;
	private BlockPos stepGoal;
	private boolean stepJump;
	/** De onde ela saiu no último passo: voltar para lá desfaz o passo (subia um degrau e descia de novo, sem fim). */
	private @org.jspecify.annotations.Nullable BlockPos previousFeet;
	private int noOptionTicks;

	public EscapeStep(BlockPos target) {
		this.target = target.immutable();
	}

	@Override
	public StepResult tick(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		c.setCompanionState(CompanionState.RECOVERING_FROM_DANGER, Component.translatable("detail.autonomouscompanions.escaping"));
		ticks++;
		if (ticks % 20 == 1 && reachable(c)) {
			c.debug("Saiu do buraco.");
			return StepResult.success();
		}
		if (ticks > 20 * 40) {
			ServerPlayer owner = c.ownerInLevel();
			if (owner != null && c.brain().bringNearOwner(owner)) {
				return StepResult.success();
			}
			return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("detail.autonomouscompanions.no_route_owner"));
		}
		// Executando um degrau: pula para o bloco da frente.
		if (stepGoal != null) {
			if (c.blockPosition().equals(stepGoal) || ++stepTicks > 30) {
				stepGoal = null;
				return StepResult.running();
			}
			if (stepJump && c.onGround()) {
				c.getJumpControl().jump();
			}
			c.getMoveControl().setWantedPosition(stepGoal.getX() + 0.5, stepGoal.getY(), stepGoal.getZ() + 0.5, 1.0);
			return StepResult.running();
		}
		if (!c.onGround()) {
			return StepResult.running();
		}
		BlockPos feet = c.blockPosition();
		// 1) Pilar: só faz sentido se houver espaço acima e ela estiver cercada.
		if (climbs < 6 && free(level, feet.above(2)) && PillarStep.hasFiller(ctx, 1) && enclosed(level, feet)) {
			climbs++;
			c.debug("Presa: subindo um bloco para sair (" + climbs + "); terra=" + ctx.inventory().count(net.minecraft.world.item.Items.DIRT) + " pedregulho=" + ctx.inventory().count(net.minecraft.world.item.Items.COBBLESTONE) + ".");
			return StepResult.push(new PillarStep(1));
		}
		// 2) Degraus em direção ao destino.
		if (steps < 24) {
			for (Direction d : directionsToward(c)) {
				BlockPos front = feet.relative(d);
				// Neve fofa no caminho: tira primeiro (ela não segura o peso; pisar ou entrar nela prende e congela).
				for (BlockPos p : new BlockPos[] {front.above(), front}) {
					if (level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW) && breakable(level, c, p)) {
						c.debug("Presa: tirando neve fofa do caminho para " + d.getName() + ".");
						return StepResult.push(new BreakSingleStep(p));
					}
				}
				if (free(level, front) && free(level, front.above())) {
					// Andar em frente ou descer até 3 blocos (ex.: do alto da parede do buraco para fora).
					BlockPos landing = landing(level, front);
					if (landing != null && !landing.equals(previousFeet)) {
						previousFeet = feet;
						noOptionTicks = 0;
						stepGoal = landing;
						stepJump = false;
						stepTicks = 0;
						if (!landing.equals(front)) {
							c.debug("Presa: descendo " + (front.getY() - landing.getY()) + " bloco(s) para " + d.getName() + ".");
						}
						return StepResult.running();
					}
				}
				// Degrau para cima: precisa de espaço na frente (cabeça) e acima da própria cabeça para o pulo.
				BlockPos[] needed = {feet.above(2), front.above(), front.above(2)};
				boolean possible = !free(level, front) || !free(level, front.below());
				for (BlockPos p : needed) {
					if (!free(level, p) && !breakable(level, c, p)) {
						possible = false;
						break;
					}
				}
				if (!possible) {
					continue;
				}
				for (BlockPos p : needed) {
					if (!free(level, p)) {
						return StepResult.push(new BreakSingleStep(p));
					}
				}
				BlockPos up = free(level, front) ? front : front.above();
				if (up.equals(previousFeet)) {
					continue;
				}
				steps++;
				previousFeet = feet;
				noOptionTicks = 0;
				stepGoal = up;
				stepJump = true;
				stepTicks = 0;
				c.debug("Presa: cavando degrau para " + d.getName() + ".");
				return StepResult.running();
			}
		}
		// Nenhuma opção além de voltar: depois de 3 s, voltar passa a valer (pode ser o único caminho).
		if (previousFeet != null && ++noOptionTicks > 60) {
			previousFeet = null;
			noOptionTicks = 0;
		}
		return StepResult.running();
	}

	/** Onde ela pisa ao sair para {@code front}: o próprio bloco (chão firme) ou até 3 blocos abaixo, sem lava. */
	private static @org.jspecify.annotations.Nullable BlockPos landing(ServerLevel level, BlockPos front) {
		BlockPos p = front;
		for (int drop = 0; drop <= 3; drop++) {
			if (!free(level, p.below())) {
				return level.getBlockState(p.below()).getFluidState().is(FluidTags.LAVA) ? null : p;
			}
			if (level.getBlockState(p.below()).getFluidState().is(FluidTags.LAVA)) {
				return null;
			}
			p = p.below();
		}
		return null;
	}

	private boolean reachable(CompanionEntity c) {
		Path p = c.getNavigation().createPath(target, 1);
		if (dev.autonomouscompanions.nav.Paths.reaches(p, target, 2.5) && p.getNodeCount() > 1) {
			return true;
		}
		// Destino além do alcance da busca de caminho do jogo (48 blocos): nenhum caminho "chega", então basta haver um
		// caminho a pé que a leve bem mais perto (o navegador faz o resto da viagem em trechos).
		double now = Math.sqrt(c.blockPosition().distSqr(target));
		if (now > 40 && p != null && p.getEndNode() != null) {
			return Math.sqrt(p.getEndNode().asBlockPos().distSqr(target)) < now - 12;
		}
		return false;
	}

	private List<Direction> directionsToward(CompanionEntity c) {
		List<Direction> list = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
		double dx = target.getX() + 0.5 - c.getX();
		double dz = target.getZ() + 0.5 - c.getZ();
		list.sort(Comparator.comparingDouble(d -> -(d.getStepX() * dx + d.getStepZ() * dz)));
		return list;
	}

	/** Cercada: nenhum vizinho horizontal tem espaço livre de dois blocos com chão para pisar. */
	private static boolean enclosed(ServerLevel level, BlockPos feet) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = feet.relative(d);
			if (free(level, n) && free(level, n.above())) {
				return false;
			}
		}
		return true;
	}

	private static boolean free(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		// Neve fofa não tem colisão, mas prende e congela: não é espaço livre para pisar nem para atravessar.
		return s.getCollisionShape(level, p).isEmpty() && !s.getFluidState().is(FluidTags.LAVA) && !s.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
	}

	private static boolean breakable(ServerLevel level, CompanionEntity c, BlockPos p) {
		return BlockPolicy.canBreak(level, p, level.getBlockState(p), BlockPolicy.Purpose.TUNNEL, c.getUUID());
	}

	@Override
	public void stop(TaskContext ctx) {
		ctx.breaker().reset(ctx.level(), ctx.companion());
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.escape");
	}

	@Override
	public CompanionState state() {
		return CompanionState.RECOVERING_FROM_DANGER;
	}
}
