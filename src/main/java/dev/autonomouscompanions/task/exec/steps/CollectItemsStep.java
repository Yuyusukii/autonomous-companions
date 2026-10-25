package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Recolhe itens caídos no chão (drops de animais, itens que não couberam) dentro de uma área e tempo limitados. */
public final class CollectItemsStep extends Step {
	private final BlockPos center;
	private final double radius;
	private final int timeout;
	private final Predicate<ItemStack> filter;
	private int ticks;
	private @Nullable ItemEntity current;
	/** Itens sem caminho a pé (num buraco fundo, no alto de um bloco, do outro lado da água): compartilhado com quem chamou. */
	private final java.util.Set<java.util.UUID> unreachable;
	private int noPathTicks;
	private int repath;

	public CollectItemsStep(BlockPos center, double radius, int timeout, Predicate<ItemStack> filter) {
		this(center, radius, timeout, filter, new java.util.HashSet<>());
	}

	public CollectItemsStep(BlockPos center, double radius, int timeout, Predicate<ItemStack> filter, java.util.Set<java.util.UUID> unreachable) {
		this.center = center.immutable();
		this.radius = radius;
		this.timeout = timeout;
		this.filter = filter;
		this.unreachable = unreachable;
	}

	public static void pickUp(ServerLevel level, CompanionEntity c, ItemEntity item) {
		ItemStack stack = item.getItem();
		int before = stack.getCount();
		ItemStack rest = c.inventory().insert(stack);
		int taken = before - rest.getCount();
		if (taken > 0) {
			c.take(item, taken);
			if (rest.isEmpty()) {
				item.discard();
			} else {
				item.setItem(rest);
			}
		}
	}

	@Override
	public StepResult tick(TaskContext ctx) {
		if (++ticks > timeout) {
			return StepResult.success();
		}
		CompanionEntity c = ctx.companion();
		if (current == null || !current.isAlive()) {
			List<ItemEntity> items = ctx.level().getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(radius),
				e -> e.isAlive() && !e.hasPickUpDelay() && !unreachable.contains(e.getUUID()) && filter.test(e.getItem()) && c.inventory().spaceFor(e.getItem()) > 0);
			if (items.isEmpty()) {
				// Pode ainda haver drops aguardando o atraso de coleta.
				boolean waiting = !ctx.level().getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(radius), e -> filter.test(e.getItem())).isEmpty();
				return waiting && ticks < timeout ? StepResult.running() : StepResult.success();
			}
			current = items.stream().min((a, b) -> Double.compare(a.distanceToSqr(c), b.distanceToSqr(c))).orElse(null);
			noPathTicks = 0;
			repath = 0;
		}
		if (current == null) {
			return StepResult.success();
		}
		if (c.distanceTo(current) <= 1.6) {
			pickUp(ctx.level(), c, current);
			current = null;
			return StepResult.running();
		}
		var navigation = c.getNavigation();
		if (--repath <= 0) {
			// Caminho até o próprio bloco do item (precisão 0). Com a precisão 1 do "ir até a entidade" do jogo, o caminho já
			// terminava no bloco vizinho, a ~1,8 bloco do item — fora do alcance de pegar —, e ela ficava parada ao lado dele
			// (lã da cama branca e carne caídas ao lado dela nas baterias de repetição).
			net.minecraft.world.level.pathfinder.Path path = navigation.createPath(current.blockPosition(), 0);
			if (path != null) {
				navigation.moveTo(path, 1.1);
			}
			repath = 10;
		}
		if (navigation.isDone() && c.distanceTo(current) < 2.5) {
			// O último passo, sem nó de caminho até ele: anda direto até o item, como a jogadora.
			c.getMoveControl().setWantedPosition(current.getX(), current.getY(), current.getZ(), 1.0);
		}
		// Sem conseguir chegar ao item: antes ela ficava parada olhando para ele até o tempo acabar (e a caça tentava o mesmo
		// item de novo, 4 vezes). Agora desiste dele em 2 s e segue com outros itens ou presas.
		if (navigation.isDone() && ++noPathTicks > 40) {
			unreachable.add(current.getUUID());
			c.debug("Item " + current.getItem().getHoverName().getString() + " em " + current.blockPosition().toShortString() + " sem caminho a pé (ela em "
				+ c.blockPosition().toShortString() + "); deixando para trás.");
			current = null;
		}
		return StepResult.running();
	}

	@Override
	public void stop(TaskContext ctx) {
		ctx.companion().getNavigation().stop();
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.collect_items");
	}

	@Override
	public CompanionState state() {
		return CompanionState.COLLECTING;
	}
}
