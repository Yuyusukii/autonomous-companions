package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.crafting.Crafter;
import dev.autonomouscompanions.crafting.RecipeIndex;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Fabrica itens por uma receita real até atingir a quantidade desejada: obtém os ingredientes que faltam (planejamento
 * recursivo), garante a bancada quando a receita exige 3x3 e executa a receita no inventário.
 */
public final class CraftStep extends CompositeStep {
	private final RecipeIndex.CraftingOption option;
	private final ItemTarget target;
	private final int count;
	private final boolean missionItem;
	private final Set<Item> chain;
	private @Nullable BlockPos table;
	private int craftCooldown;
	private int failures;

	public CraftStep(RecipeIndex.CraftingOption option, ItemTarget target, int count, boolean missionItem, Set<Item> chain) {
		this.option = option;
		this.target = target;
		this.count = count;
		this.missionItem = missionItem;
		this.chain = chain;
	}

	@Override
	protected StepResult run(TaskContext ctx) {
		int have = target.count(ctx.companion(), missionItem);
		if (have >= count) {
			return StepResult.success();
		}
		int crafts = (count - have + option.outputCount() - 1) / option.outputCount();
		// 1) Ingredientes.
		for (RecipeIndex.IngredientCount ic : option.grouped()) {
			int need = ic.count() * crafts;
			ItemTarget ingredient = ItemTarget.anyOf(ic.items());
			int free = ingredient.count(ctx.companion(), false);
			if (free < need) {
				return StepResult.push(new ObtainStep(ingredient, need, false, chain));
			}
		}
		// 2) Bancada quando necessária.
		if (option.needsTable()) {
			if (table == null || !ctx.level().getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
				table = Stations.findNearby(ctx.level(), ctx.companion().blockPosition(), Blocks.CRAFTING_TABLE, 12, 4);
				if (table == null) {
					return StepResult.push(new PlaceStationStep(Blocks.CRAFTING_TABLE, chain, p -> true));
				}
			}
			Navigator nav = ctx.brain().navigator();
			if (nav.distanceTo(table) > 3.5) {
				nav.setGoal(table, 2.5, 1.0);
				if (nav.tick() == Navigator.Status.FAILED) {
					table = null;
					nav.clear();
					return StepResult.push(new PlaceStationStep(Blocks.CRAFTING_TABLE, chain, p -> ctx.brain().navigator().distanceTo(p) <= 3.5));
				}
				return StepResult.running();
			}
			ctx.companion().getLookControl().setLookAt(table.getX() + 0.5, table.getY() + 0.5, table.getZ() + 0.5);
		}
		// 3) Fabricação (uma por vez, com animação).
		if (--craftCooldown > 0) {
			return StepResult.running();
		}
		craftCooldown = 5;
		if (option.output() != ctx.companion().mainHandItem().getItem() && Gear.isTool(ctx.companion().mainHandItem())) {
			Gear.stowMainHand(ctx.companion());
		}
		ItemStack result = Crafter.craftOnce(ctx.level(), ctx.companion(), option);
		if (result.isEmpty()) {
			if (++failures > 3) {
				return StepResult.fail(Component.translatable("reason.autonomouscompanions.craft_failed", new ItemStack(option.output()).getHoverName()));
			}
			return StepResult.running();
		}
		ctx.companion().swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
		ctx.level().playSound(null, ctx.companion().blockPosition(), SoundEvents.UI_LOOM_TAKE_RESULT, SoundSource.NEUTRAL, 0.4f, 1.2f);
		Order order = ctx.order();
		if (order != null) {
			order.progress().itemsCrafted += result.getCount();
		}
		return StepResult.running();
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.craft", count, new ItemStack(option.output()).getHoverName());
	}

	@Override
	public CompanionState state() {
		return Gear.armorSlot(new ItemStack(option.output())) != null || Gear.attackDamage(new ItemStack(option.output())) > 3
			? CompanionState.CRAFTING_EQUIPMENT : CompanionState.CRAFTING_TOOLS;
	}
}
