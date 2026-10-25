package dev.autonomouscompanions.task;

import dev.autonomouscompanions.crafting.CostModel;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.mining.DropIndex;
import dev.autonomouscompanions.mining.HarvestAnalyzer;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.steps.Explainer;
import dev.autonomouscompanions.world.ResourceKnowledge;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Converte pedidos do catálogo (bloco + modo + quantidade) em ordens, depois de analisar a forma real de obtenção:
 * bloco indestrutível, bloco sem item, exigência de Toque Suave, dimensão errada, drops diferentes do bloco etc.
 */
public final class OrderService {
	public static final int MAX_COUNT = 2304;

	private OrderService() {
	}

	public record Analysis(boolean feasible, Item target, SearchProfile profile, int range, List<Component> lines) {
	}

	public static @Nullable Block block(String id) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) {
			return null;
		}
		return BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
	}

	public static @Nullable Item item(String id) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
		return item == Items.AIR ? null : item;
	}

	public static Analysis analyzeBlock(ServerLevel level, CompanionEntity c, Block block, GatherMode mode, int count) {
		List<Component> lines = new ArrayList<>();
		DropIndex.ensureReady(level.getServer());
		BlockState state = block.defaultBlockState();
		HarvestAnalyzer.Analysis a = DropIndex.analysis(state);
		if (a == null) {
			a = HarvestAnalyzer.analyze(level, state, c.blockPosition());
		}
		if (state.isAir() || block == Blocks.VOID_AIR || block == Blocks.CAVE_AIR) {
			lines.add(Component.translatable("analysis.autonomouscompanions.air"));
			return new Analysis(false, Items.AIR, SearchProfile.AUTO, 0, lines);
		}
		if (a.unbreakable()) {
			lines.add(Component.translatable("analysis.autonomouscompanions.unbreakable", block.getName()));
			return new Analysis(false, Items.AIR, SearchProfile.AUTO, 0, lines);
		}
		Item target;
		if (mode == GatherMode.EXACT_BLOCK) {
			target = block.asItem();
			if (target == Items.AIR) {
				lines.add(Component.translatable("analysis.autonomouscompanions.no_item_form", block.getName()));
				return new Analysis(false, Items.AIR, SearchProfile.AUTO, 0, lines);
			}
			if (!a.dropsItself()) {
				if (a.dropsItselfWithSilk()) {
					lines.add(Component.translatable("analysis.autonomouscompanions.exact_needs_silk", block.getName()));
				} else if (a.dropsNothing()) {
					lines.add(Component.translatable("analysis.autonomouscompanions.drops_nothing", block.getName()));
				}
			}
		} else {
			if (a.normalDrops().isEmpty()) {
				lines.add(Component.translatable("analysis.autonomouscompanions.drops_nothing", block.getName()));
				return new Analysis(false, Items.AIR, SearchProfile.AUTO, 0, lines);
			}
			target = a.normalDrops().iterator().next();
			if (target != block.asItem()) {
				lines.add(Component.translatable("analysis.autonomouscompanions.drops_other", block.getName(), new ItemStack(target).getHoverName()));
			}
		}
		Analysis result = analyzeItem(level, c, target, count, lines);
		if (state.requiresCorrectToolForDrops() && a.bestReferenceTool() != null) {
			lines.add(Component.translatable("analysis.autonomouscompanions.requires_tool", block.getName()));
		}
		ResourceKnowledge.Info info = ResourceKnowledge.of(state);
		if (!info.dim().matches(level)) {
			lines.add(Component.translatable("reason.autonomouscompanions.wrong_dimension", block.getName(), Component.translatable(info.dim().translationKey())));
		}
		return result;
	}

	public static Analysis analyzeItem(ServerLevel level, CompanionEntity c, Item target, int count, List<Component> lines) {
		DropIndex.ensureReady(level.getServer());
		CostModel costs = new CostModel(level, c);
		ItemTarget t = ItemTarget.of(target);
		List<CostModel.Method> methods = costs.methods(t, count, Set.of(), true);
		lines.add(Component.translatable("analysis.autonomouscompanions.output", count, new ItemStack(target).getHoverName()));
		int cost = 4;
		var sources = DropIndex.blockSources(target);
		if (!sources.isEmpty()) {
			cost = ResourceKnowledge.of(sources.getFirst().state()).cost();
		}
		SearchProfile profile = ResourceKnowledge.autoProfile(cost);
		int range = ResourceKnowledge.range(SearchProfile.AUTO, cost, c.role(), 0, c.settings().maxSearchDistance);
		if (methods.isEmpty()) {
			lines.add(Explainer.whyImpossible(level, t, costs.hasSilkTool()));
			return new Analysis(false, target, profile, range, lines);
		}
		CostModel.Method best = methods.getFirst();
		lines.add(Component.translatable("analysis.autonomouscompanions.method", best.describe()));
		if (best instanceof CostModel.Mine mine) {
			BlockState st = mine.sources().getFirst().state();
			double tool = costs.requiredToolCost(st);
			if (st.requiresCorrectToolForDrops()) {
				lines.add(tool <= 0 ? Component.translatable("analysis.autonomouscompanions.has_tool")
					: Component.translatable("analysis.autonomouscompanions.will_craft_tool"));
			}
			if (mine.silk()) {
				lines.add(Component.translatable("analysis.autonomouscompanions.uses_silk"));
			}
		}
		if (methods.size() > 1) {
			lines.add(Component.translatable("analysis.autonomouscompanions.alternatives", methods.size() - 1));
		}
		lines.add(Component.translatable("analysis.autonomouscompanions.profile", Component.translatable(profile.translationKey()), range));
		int have = c.inventory().count(target);
		if (have > 0) {
			lines.add(Component.translatable("analysis.autonomouscompanions.already_has", have));
		}
		return new Analysis(true, target, profile, range, lines);
	}

	/** Cria a ordem; retorna motivo de recusa ou nulo se aceita. */
	public static @Nullable Component createGather(ServerLevel level, CompanionEntity c, String blockId, GatherMode mode, int count, SearchProfile profile,
		int maxDistance, boolean urgent) {
		Block block = block(blockId);
		if (block == null) {
			return Component.translatable("order.autonomouscompanions.invalid_block");
		}
		count = Mth.clamp(count, 1, MAX_COUNT);
		if (!c.orders().canAdd()) {
			return Component.translatable("order.autonomouscompanions.queue_full");
		}
		Analysis a = analyzeBlock(level, c, block, mode, count);
		if (!a.feasible()) {
			return a.lines().getLast();
		}
		Order order = Order.gather(a.target(), blockId, mode, count, profile, Math.max(0, maxDistance), level.getGameTime());
		order.setUrgent(urgent);
		c.orders().add(order);
		c.brain().requestDecision();
		return null;
	}

	/**
	 * Pedido "qualquer carne" (crua ou assada, conforme {@code cooked}): qualquer combinação de carnes conta e ela caça
	 * sempre o animal de carne mais perto (ovelha, porco, vaca, coelho...), em vez de se prender a uma espécie só.
	 */
	public static @Nullable Component createMeatOrder(ServerLevel level, CompanionEntity c, boolean cooked, int count) {
		count = Mth.clamp(count, 1, MAX_COUNT);
		if (!c.orders().canAdd()) {
			return Component.translatable("order.autonomouscompanions.queue_full");
		}
		c.orders().add(Order.gatherGroup(cooked ? Order.ANY_COOKED_MEAT : Order.ANY_MEAT, count, level.getGameTime()));
		c.brain().requestDecision();
		return null;
	}

	public static @Nullable Component createItemOrder(ServerLevel level, CompanionEntity c, String itemId, int count) {
		Item item = item(itemId);
		if (item == null) {
			return Component.translatable("order.autonomouscompanions.invalid_item");
		}
		count = Mth.clamp(count, 1, MAX_COUNT);
		if (!c.orders().canAdd()) {
			return Component.translatable("order.autonomouscompanions.queue_full");
		}
		List<Component> lines = new ArrayList<>();
		Analysis a = analyzeItem(level, c, item, count, lines);
		if (!a.feasible()) {
			return lines.getLast();
		}
		c.orders().add(Order.gather(item, "", GatherMode.ITEM, count, SearchProfile.AUTO, 0, level.getGameTime()));
		c.brain().requestDecision();
		return null;
	}

	/** Pedido de habilidade da biblioteca (ex.: "minerar_ferro"): recusa habilidades inválidas, reativas ou desconhecidas. */
	public static @Nullable Component createSkillOrder(ServerLevel level, CompanionEntity c, String skillId, int count) {
		var skill = dev.autonomouscompanions.mind.skills.SkillLibrary.get(skillId);
		if (skill == null) {
			skill = dev.autonomouscompanions.mind.skills.SkillLibrary.find(skillId);
		}
		if (skill == null) {
			return Component.literal("Não conheço a habilidade \"" + skillId + "\".");
		}
		if (!skill.orderable()) {
			return Component.literal("A habilidade \"" + skill.name() + "\" não pode ser pedida" + (skill.valid() ? " (é reativa)." : ": " + skill.problems()));
		}
		if (!c.orders().canAdd()) {
			return Component.translatable("order.autonomouscompanions.queue_full");
		}
		count = Mth.clamp(count <= 0 ? skill.defaultQuantity() : count, 1, MAX_COUNT);
		c.orders().add(Order.skill(skill.id(), skill.success().item(), count, level.getGameTime()));
		c.brain().requestDecision();
		return null;
	}

	/** Pedido "carne mais próxima": resolvido no servidor na hora do pedido. */
	public static final String NEAREST_MEAT = "autonomouscompanions:nearest_meat";
	public static final String NEAREST_MEAT_COOKED = "autonomouscompanions:nearest_meat_cooked";

	/**
	 * Carne do animal caçável mais próximo dela (até 64 blocos), ignorando filhotes, animais nomeados, presos ou em
	 * cercados. Com {@code cooked}, a versão assada dessa carne (se houver receita de fornalha). Nula se não houver
	 * nenhum animal de carne por perto.
	 */
	public static @Nullable Item nearestMeat(ServerLevel level, CompanionEntity c, boolean cooked) {
		Item best = null;
		double bestDist = Double.MAX_VALUE;
		for (Item item : dev.autonomouscompanions.mining.DropIndex.huntableItems()) {
			if (!dev.autonomouscompanions.survival.FoodHelper.isEdible(new net.minecraft.world.item.ItemStack(item))) {
				continue;
			}
			for (var src : dev.autonomouscompanions.mining.DropIndex.entitySources(item)) {
				for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, c.getBoundingBox().inflate(64, 24, 64),
					e -> e.getType() == src.type() && e.isAlive() && !e.isBaby())) {
					if (dev.autonomouscompanions.task.exec.steps.HuntStep.isProtectedLivestock(level, e)) {
						continue;
					}
					double d = e.distanceToSqr(c);
					if (d < bestDist) {
						bestDist = d;
						best = item;
					}
				}
			}
		}
		if (best != null && cooked) {
			var smelt = dev.autonomouscompanions.crafting.RecipeIndex.smeltingFrom(best);
			if (!smelt.isEmpty()) {
				best = smelt.getFirst().output();
			}
		}
		return best;
	}
}
