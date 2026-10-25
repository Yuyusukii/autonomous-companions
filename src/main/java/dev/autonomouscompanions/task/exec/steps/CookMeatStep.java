package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.crafting.RecipeIndex;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * "Qualquer carne assada": primeiro caça o animal de carne mais perto (qualquer espécie, numa caçada só), depois assa o
 * que trouxe — porco, carneiro, vaca, coelho, frango, misturados. Antes o planejador escolhia uma receita (ex.: porco
 * assado) e ela ficava presa àquela espécie, atravessando o mapa atrás de porcos com ovelhas ao lado.
 */
public final class CookMeatStep extends CompositeStep {
	private static final List<Item> RAW = List.of(Items.MUTTON, Items.PORKCHOP, Items.BEEF, Items.RABBIT, Items.CHICKEN);

	private final ItemTarget cooked;
	private final int count;
	private final boolean missionItem;
	private int huntRounds;

	public CookMeatStep(ItemTarget cooked, int count, boolean missionItem) {
		this.cooked = cooked;
		this.count = count;
		this.missionItem = missionItem;
	}

	/** Caçar mais é tentativa (sem animais por perto, assa o que já tem); a fundição é obrigatória. */
	@Override
	protected boolean isOptional(Step child) {
		return child instanceof ObtainStep;
	}

	@Override
	protected StepResult run(TaskContext ctx) {
		int have = cooked.count(ctx.companion(), missionItem);
		if (have >= count) {
			return StepResult.success();
		}
		int need = count - have;
		ItemTarget raw = ItemTarget.anyOf(new java.util.LinkedHashSet<>(RAW));
		int rawHave = raw.count(ctx.companion(), false);
		if (rawHave < need && huntRounds < 3) {
			huntRounds++;
			ctx.companion().debug("Carne assada: faltam " + need + ", tem " + rawHave + " crua(s); caçando o animal de carne mais perto.");
			return StepResult.push(new ObtainStep(raw, need, false, Set.of(), true));
		}
		// Assa a carne crua que tiver mais (uma receita por vez).
		Item best = null;
		int bestCount = 0;
		for (Item r : RAW) {
			int n = ctx.inventory().countFree(r);
			if (n > bestCount && !RecipeIndex.smeltingFrom(r).isEmpty()) {
				best = r;
				bestCount = n;
			}
		}
		if (best == null) {
			return StepResult.fail(StepResult.FailureCode.NOT_FOUND, Component.translatable("reason.autonomouscompanions.no_animals", cooked.describe()));
		}
		RecipeIndex.CookingOption option = RecipeIndex.smeltingFrom(best).getFirst();
		int k = Math.min(bestCount, need);
		ctx.companion().debug("Carne assada: assando " + k + "x " + best.getDescriptionId());
		return StepResult.push(new SmeltStep(option, cooked, have + k, missionItem, Set.of(option.output())));
	}

	@Override
	public void workingItems(java.util.Map<Item, Integer> out) {
		// A carne crua caçada para assar não é comida no caminho (antes ela podia comer a própria matéria-prima).
		for (Item r : RAW) {
			out.merge(r, count, Math::max);
		}
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.obtain", count, cooked.describe());
	}

	@Override
	public CompanionState state() {
		return CompanionState.PLANNING;
	}
}
