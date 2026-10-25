package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.crafting.RecipeIndex;
import dev.autonomouscompanions.mining.DropIndex;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.world.ResourceKnowledge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/** Produz explicações objetivas de por que um item não pode ser obtido. */
public final class Explainer {
	private Explainer() {
	}

	public static Component whyImpossible(TaskContext ctx, ItemTarget target) {
		return whyImpossible(ctx.level(), target, ctx.costs().hasSilkTool());
	}

	public static Component whyImpossible(ServerLevel level, ItemTarget target, boolean hasSilk) {
		boolean anyBlock = false;
		boolean anyNatural = false;
		boolean silkOnly = true;
		ResourceKnowledge.Dim otherDim = null;
		boolean anyRecipe = false;
		boolean anyAnimal = false;
		for (Item item : target.items()) {
			for (DropIndex.BlockSource src : DropIndex.blockSources(item)) {
				anyBlock = true;
				if (!src.natural()) {
					continue;
				}
				anyNatural = true;
				if (!src.silkOnly()) {
					silkOnly = false;
				}
				ResourceKnowledge.Info info = ResourceKnowledge.of(src.state());
				if (!info.dim().matches(level)) {
					otherDim = info.dim();
				}
			}
			anyRecipe |= RecipeIndex.hasAnyRecipe(item);
			anyAnimal |= !DropIndex.entitySources(item).isEmpty();
		}
		Component name = target.describe();
		if (anyNatural && otherDim != null) {
			return Component.translatable("reason.autonomouscompanions.wrong_dimension", name, Component.translatable(otherDim.translationKey()));
		}
		if (anyNatural && silkOnly && !hasSilk) {
			return Component.translatable("reason.autonomouscompanions.needs_silk", name);
		}
		if (anyBlock && !anyNatural && !anyRecipe) {
			return Component.translatable("reason.autonomouscompanions.only_structures", name);
		}
		if (anyRecipe) {
			return Component.translatable("reason.autonomouscompanions.ingredients_impossible", name);
		}
		if (anyAnimal) {
			return Component.translatable("reason.autonomouscompanions.no_animals", name);
		}
		return Component.translatable("reason.autonomouscompanions.unobtainable", name);
	}
}
