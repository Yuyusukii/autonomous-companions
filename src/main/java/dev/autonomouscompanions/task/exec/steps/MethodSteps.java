package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.crafting.CostModel;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.Set;
import net.minecraft.world.item.Item;

/** Converte um método escolhido pelo planejador na etapa executável correspondente. */
public final class MethodSteps {
	private MethodSteps() {
	}

	public static Step create(TaskContext ctx, CostModel.Method method, ItemTarget target, int count, boolean missionItem, Set<Item> chain) {
		return switch (method) {
			case CostModel.Craft craft -> new CraftStep(craft.option(), target, count, missionItem, chain);
			case CostModel.Smelt smelt -> new SmeltStep(smelt.option(), target, count, missionItem, chain);
			case CostModel.Mine mine -> new MineStep(mine.sources(), mine.silk(), target, count, missionItem, chain);
			case CostModel.Hunt hunt -> new HuntStep(hunt.sources(), target, count, missionItem, chain);
		};
	}
}
