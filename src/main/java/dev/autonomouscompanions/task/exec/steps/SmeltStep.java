package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.crafting.RecipeIndex;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import org.jspecify.annotations.Nullable;

/**
 * Funde/cozinha em uma fornalha real do mundo: coloca entrada e combustível nos espaços da fornalha, espera o tempo
 * real de cozimento (a fornalha processa sozinha) e recolhe o resultado. Combustível é obtido quando falta.
 */
public final class SmeltStep extends CompositeStep {
	private final RecipeIndex.CookingOption option;
	private final ItemTarget target;
	private final int count;
	private final boolean missionItem;
	private final Set<Item> chain;
	private @Nullable BlockPos furnacePos;
	private int waitTicks;
	private int maxWait;
	private int loadedInputs;

	public SmeltStep(RecipeIndex.CookingOption option, ItemTarget target, int count, boolean missionItem, Set<Item> chain) {
		this.option = option;
		this.target = target;
		this.count = count;
		this.missionItem = missionItem;
		this.chain = chain;
	}

	private Set<Item> inputItems() {
		Set<Item> set = new LinkedHashSet<>(RecipeIndex.items(option.input()));
		set.remove(option.output());
		return set;
	}

	private static boolean isLit(TaskContext ctx, @Nullable BlockPos pos) {
		if (pos == null) {
			return false;
		}
		var state = ctx.level().getBlockState(pos);
		return state.hasProperty(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)
			&& state.getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
	}

	public static int estimatedBurnTicks(ItemStack stack) {
		CookingFuel fuel = stack.get(DataComponents.COOKING_FUEL);
		if (fuel == null) {
			return 0;
		}
		if (fuel.burnTime() instanceof ResolvableInt.Constant c) {
			return c.value();
		}
		return 300;
	}

	/** Combustível livre: não reservado, não é a entrada/saída e não é ferramenta/arma/armadura. */
	private boolean isUsableFuel(ItemStack s) {
		if (s.isEmpty() || !s.has(DataComponents.COOKING_FUEL)) {
			return false;
		}
		if (inputItems().contains(s.getItem()) || s.is(option.output()) || s.has(DataComponents.TOOL) || s.has(DataComponents.EQUIPPABLE)
			|| s.has(DataComponents.WEAPON) || s.isDamageableItem()) {
			return false;
		}
		// Não queima mudas, tábuas reservadas etc. além do livre.
		return true;
	}

	private int freeFuelTicks(TaskContext ctx) {
		int ticks = 0;
		var inv = ctx.inventory();
		java.util.Map<Item, Integer> perItem = new java.util.HashMap<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (isUsableFuel(s)) {
				perItem.merge(s.getItem(), s.getCount(), Integer::sum);
			}
		}
		for (var e : perItem.entrySet()) {
			int free = Math.max(0, e.getValue() - inv.reserved(e.getKey()));
			ticks += free * estimatedBurnTicks(new ItemStack(e.getKey()));
		}
		return ticks;
	}

	@Override
	protected StepResult run(TaskContext ctx) {
		int have = target.count(ctx.companion(), missionItem);
		AbstractFurnaceBlockEntity furnace = furnacePos != null && ctx.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity f ? f : null;
		int inFurnaceOut = furnace != null ? furnace.getItem(2).getCount() : 0;
		int inFurnaceIn = furnace != null && !furnace.getItem(0).isEmpty() && inputItems().contains(furnace.getItem(0).getItem())
			? furnace.getItem(0).getCount() : 0;
		if (have >= count) {
			if (furnace != null) {
				retrieveAll(ctx, furnace);
			}
			return StepResult.success();
		}
		int stillNeeded = count - have - inFurnaceOut;
		int inputsNeeded = Math.max(0, (stillNeeded + option.outputCount() - 1) / option.outputCount() - inFurnaceIn);
		ItemTarget input = ItemTarget.anyOf(inputItems());
		// 1) Entrada suficiente.
		if (inputsNeeded > 0 && input.count(ctx.companion(), false) < inputsNeeded) {
			return StepResult.push(new ObtainStep(input, inputsNeeded, false, chain));
		}
		// 2) Combustível suficiente para o que ainda vai entrar.
		int burnInFurnace = furnace != null ? estimatedBurnTicks(furnace.getItem(1)) * furnace.getItem(1).getCount() : 0;
		int needTicks = (inputsNeeded + inFurnaceIn) * option.cookTime() - burnInFurnace - (furnace != null && isLit(ctx, furnacePos) ? 100 : 0);
		if (needTicks > 0 && freeFuelTicks(ctx) < needTicks) {
			Set<Item> fuels = new LinkedHashSet<>();
			fuels.add(Items.COAL);
			fuels.add(Items.CHARCOAL);
			net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.PLANKS).forEach(h -> fuels.add(h.value()));
			net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.LOGS_THAT_BURN).forEach(h -> fuels.add(h.value()));
			fuels.removeAll(inputItems());
			int units = (needTicks - freeFuelTicks(ctx) + 299) / 300;
			ItemTarget fuel = ItemTarget.anyOf(fuels);
			return StepResult.push(new ObtainStep(fuel, fuel.count(ctx.companion(), false) + units, false, chain));
		}
		// 3) Fornalha utilizável por perto.
		if (furnace == null) {
			furnacePos = null;
			BlockPos found = Stations.findNearby(ctx.level(), ctx.companion().blockPosition(), Blocks.FURNACE, 12, 4);
			if (found != null && Stations.furnaceUsable(ctx.level(), found, s -> inputItems().contains(s.getItem()))) {
				furnacePos = found;
				maxWait = (inputsNeeded + 1) * option.cookTime() * 3 + 1200;
				waitTicks = 0;
				return StepResult.running();
			}
			return StepResult.push(new PlaceStationStep(Blocks.FURNACE, chain, p -> Stations.furnaceUsable(ctx.level(), p,
				s -> inputItems().contains(s.getItem()))));
		}
		// 4) Ir até a fornalha.
		Navigator nav = ctx.brain().navigator();
		if (nav.distanceTo(furnacePos) > 3.5) {
			nav.setGoal(furnacePos, 2.5, 1.0);
			if (nav.tick() == Navigator.Status.FAILED) {
				furnacePos = null;
				nav.clear();
			}
			return StepResult.running();
		}
		ctx.companion().getLookControl().setLookAt(furnacePos.getX() + 0.5, furnacePos.getY() + 0.5, furnacePos.getZ() + 0.5);
		// 5) Abastecer, recolher e esperar.
		if (ctx.companion().tickCount % 10 == 0) {
			collectOutput(ctx, furnace);
			loadInput(ctx, furnace, inputsNeeded);
			loadFuel(ctx, furnace);
		}
		if (++waitTicks > Math.max(maxWait, 2400)) {
			retrieveAll(ctx, furnace);
			return StepResult.fail(Component.translatable("reason.autonomouscompanions.smelt_timeout", new ItemStack(option.output()).getHoverName()));
		}
		return StepResult.running();
	}

	private void collectOutput(TaskContext ctx, AbstractFurnaceBlockEntity furnace) {
		ItemStack out = furnace.getItem(2);
		if (out.isEmpty()) {
			return;
		}
		ItemStack rest = ctx.inventory().insert(out);
		furnace.setItem(2, rest);
		furnace.setChanged();
		if (rest.getCount() != out.getCount() && ctx.order() != null && missionItem) {
			ctx.order().progress().obtained += out.getCount() - rest.getCount();
		}
	}

	private void loadInput(TaskContext ctx, AbstractFurnaceBlockEntity furnace, int inputsNeeded) {
		if (inputsNeeded <= 0) {
			return;
		}
		ItemStack slot = furnace.getItem(0);
		for (Item item : inputItems()) {
			if (!slot.isEmpty() && !slot.is(item)) {
				continue;
			}
			int free = ctx.inventory().countFree(item);
			if (free <= 0) {
				continue;
			}
			int space = slot.isEmpty() ? item.getDefaultMaxStackSize() : slot.getMaxStackSize() - slot.getCount();
			int move = Math.min(Math.min(free, space), inputsNeeded);
			if (move <= 0) {
				return;
			}
			int removed = ctx.inventory().removeFree(item, move);
			if (slot.isEmpty()) {
				furnace.setItem(0, new ItemStack(item, removed));
			} else {
				slot.grow(removed);
			}
			loadedInputs += removed;
			furnace.setChanged();
			return;
		}
	}

	private void loadFuel(TaskContext ctx, AbstractFurnaceBlockEntity furnace) {
		ItemStack fuelSlot = furnace.getItem(1);
		boolean hasInput = !furnace.getItem(0).isEmpty();
		if (!hasInput || !fuelSlot.isEmpty() || isLit(ctx, furnacePos)) {
			return;
		}
		var inv = ctx.inventory();
		int best = -1;
		int bestBurn = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (isUsableFuel(s) && inv.countFree(s.getItem()) > 0) {
				int burn = estimatedBurnTicks(s);
				if (burn > bestBurn) {
					bestBurn = burn;
					best = i;
				}
			}
		}
		if (best < 0) {
			return;
		}
		ItemStack s = inv.getItem(best);
		int needed = Math.max(1, (furnace.getItem(0).getCount() * option.cookTime() + bestBurn - 1) / bestBurn);
		int move = Math.min(Math.min(needed, inv.countFree(s.getItem())), s.getMaxStackSize());
		Item item = s.getItem();
		int removed = inv.removeFree(item, move);
		furnace.setItem(1, new ItemStack(item, removed));
		furnace.setChanged();
	}

	/** Recolhe resultado, entrada e combustível que sobraram (só itens compatíveis com esta tarefa). */
	private void retrieveAll(TaskContext ctx, AbstractFurnaceBlockEntity furnace) {
		collectOutput(ctx, furnace);
		for (int slot = 0; slot <= 1; slot++) {
			ItemStack s = furnace.getItem(slot);
			if (s.isEmpty()) {
				continue;
			}
			ItemStack rest = ctx.inventory().insert(s);
			furnace.setItem(slot, rest);
		}
		furnace.setChanged();
	}

	@Override
	public void stop(TaskContext ctx) {
		if (furnacePos != null && ctx.level().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity f && ctx.brain().navigator().distanceTo(furnacePos) < 6) {
			collectOutput(ctx, f);
		}
	}

	@Override
	public void workingItems(java.util.Map<Item, Integer> out) {
		// A matéria-prima que vai para a fornalha (ex.: carne crua) fica protegida enquanto assa.
		for (Item i : inputItems()) {
			out.merge(i, count, Math::max);
		}
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.smelt", count, new ItemStack(option.output()).getHoverName());
	}

	@Override
	public CompanionState state() {
		return CompanionState.SMELTING;
	}
}
