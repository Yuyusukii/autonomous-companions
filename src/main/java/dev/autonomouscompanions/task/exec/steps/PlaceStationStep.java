package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Garante uma estação de trabalho por perto: usa uma existente (até 12 blocos) ou coloca a própria — fabricando-a se
 * preciso. Estações colocadas por ela ficam registradas para serem recolhidas ao fim da tarefa.
 */
public final class PlaceStationStep extends CompositeStep {
	private final Block block;
	private final Set<Item> chain;
	private final java.util.function.Predicate<BlockPos> usable;
	private int attempts;
	private int carves;

	public PlaceStationStep(Block block, Set<Item> chain, java.util.function.Predicate<BlockPos> usable) {
		this.block = block;
		this.chain = chain;
		this.usable = usable;
	}

	/** Abrir o nicho é só uma tentativa: se falhar, ela ainda tenta dar uns passos e procurar outro lugar. */
	@Override
	protected boolean isOptional(dev.autonomouscompanions.task.exec.Step child) {
		return child instanceof BreakSingleStep;
	}

	@Override
	protected StepResult run(TaskContext ctx) {
		BlockPos existing = Stations.findNearby(ctx.level(), ctx.companion().blockPosition(), block, 12, 4);
		if (existing != null && usable.test(existing)) {
			return StepResult.success();
		}
		Item item = block.asItem();
		if (ctx.inventory().countFree(item) <= 0) {
			if (chain.contains(item)) {
				return StepResult.fail(StepResult.FailureCode.IMPOSSIBLE, Component.translatable("reason.autonomouscompanions.station_cycle", block.getName()));
			}
			return StepResult.push(new ObtainStep(ItemTarget.of(item), 1, false, chain));
		}
		BlockPos spot = Stations.placementSpot(ctx.level(), ctx.companion());
		if (spot == null && carves < 2) {
			// Num túnel ou poço estreito não há espaço livre: como uma jogadora, abre um nicho na parede (bloco natural ao
			// lado, na altura dos pés, com chão firme embaixo) e põe a estação ali.
			BlockPos niche = niche(ctx);
			if (niche != null) {
				carves++;
				ctx.companion().debug("Sem espaço para " + block.getName().getString() + ": abrindo um nicho em " + niche.toShortString());
				return StepResult.push(new BreakSingleStep(niche));
			}
		}
		if (spot == null) {
			if (++attempts > 3) {
				return StepResult.fail(Component.translatable("reason.autonomouscompanions.no_place_for", block.getName()));
			}
			// Dá alguns passos e tenta de novo.
			ctx.companion().getNavigation().moveTo(ctx.companion().getX() + ctx.companion().getRandom().nextInt(7) - 3, ctx.companion().getY(),
				ctx.companion().getZ() + ctx.companion().getRandom().nextInt(7) - 3, 1.0);
			return StepResult.running();
		}
		if (Stations.place(ctx.level(), ctx.companion(), spot, block.defaultBlockState())) {
			ctx.inventory().removeFree(item, 1);
			ctx.ownStations().add(spot.immutable());
			ctx.companion().debug("Colocou " + block.getName().getString() + " em " + spot.toShortString());
			return StepResult.success();
		}
		return StepResult.fail(Component.translatable("reason.autonomouscompanions.no_place_for", block.getName()));
	}

	private static @org.jspecify.annotations.Nullable BlockPos niche(TaskContext ctx) {
		var level = ctx.level();
		BlockPos feet = ctx.companion().blockPosition();
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos p = feet.relative(d);
			var s = level.getBlockState(p);
			if (s.isAir() || !s.getFluidState().isEmpty() || !dev.autonomouscompanions.world.BlockPolicy.isNatural(s)
				|| !dev.autonomouscompanions.world.BlockPolicy.canBreak(level, p, s, dev.autonomouscompanions.world.BlockPolicy.Purpose.TUNNEL,
					ctx.companion().getUUID())) {
				continue;
			}
			if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) {
				continue;
			}
			// Sem água ou lava encostada (não abre passagem para líquidos).
			boolean wet = false;
			for (net.minecraft.core.Direction n : net.minecraft.core.Direction.values()) {
				if (!level.getFluidState(p.relative(n)).isEmpty()) {
					wet = true;
				}
			}
			if (!wet) {
				return p;
			}
		}
		return null;
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.station", block.getName());
	}

	@Override
	public CompanionState state() {
		return CompanionState.CRAFTING_TOOLS;
	}
}
