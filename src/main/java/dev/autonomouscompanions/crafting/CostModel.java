package dev.autonomouscompanions.crafting;

import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.mining.DropIndex;
import dev.autonomouscompanions.mining.HarvestAnalyzer;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.world.ResourceKnowledge;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Planejador de dependências. Estima recursivamente o custo de obter cada item por quatro caminhos — minerar um bloco
 * natural, fabricar (bancada), fundir (fornalha) ou caçar — usando as receitas e tabelas de loot reais. A recursão
 * respeita profundidade máxima, detecta ciclos (ex.: lingote ↔ bloco) e tem orçamento de avaliações.
 */
public final class CostModel {
	public static final double INF = 1.0e9;
	private static final double CRAFT_OVERHEAD = 0.3;
	private static final double SMELT_OVERHEAD = 1.2;

	private final ServerLevel level;
	private final CompanionEntity companion;
	private final Map<Item, Double> memo = new HashMap<>();
	private final Set<Item> computing = new HashSet<>();
	private int evaluations;
	/** Quantas vezes o cálculo foi cortado pelo limite de profundidade/orçamento (resultados assim não vão para o cache). */
	private int cuts;
	private final boolean hasSilkTool;

	public CostModel(ServerLevel level, CompanionEntity companion) {
		this.level = level;
		this.companion = companion;
		boolean silk = HarvestAnalyzer.hasSilkTouch(level, companion.mainHandItem());
		for (int i = 0; i < companion.inventory().getContainerSize() && !silk; i++) {
			silk = HarvestAnalyzer.hasSilkTouch(level, companion.inventory().getItem(i));
		}
		this.hasSilkTool = silk;
	}

	private java.util.@org.jspecify.annotations.Nullable Set<net.minecraft.world.level.block.Block> nearby;

	/** Blocos visíveis bem perto dela (raio 6), lidos uma vez por cálculo de custos. */
	private java.util.Set<net.minecraft.world.level.block.Block> nearbyBlocks() {
		if (nearby == null) {
			nearby = new java.util.HashSet<>();
			BlockPos c = companion.blockPosition();
			for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -3, -6), c.offset(6, 8, 6))) {
				if (level.hasChunkAt(p)) {
					nearby.add(level.getBlockState(p).getBlock());
				}
			}
		}
		return nearby;
	}

	/** Diagnóstico: avaliações feitas e cortes por profundidade/orçamento neste cálculo. */
	public String budget() {
		return "avaliações " + evaluations + ", cortes " + cuts;
	}

	public boolean hasSilkTool() {
		return hasSilkTool;
	}

	/** Custo estimado de uma unidade do item (0 se já houver unidades livres). */
	public double unitCost(Item item) {
		if (item == Items.AIR) {
			return INF;
		}
		if (companion.inventory().countFree(item) > 0) {
			return 0;
		}
		Double cached = memo.get(item);
		if (cached != null) {
			return cached;
		}
		if (computing.contains(item)) {
			return INF; // ciclo: usar o próprio item para obtê-lo nunca é mais barato
		}
		if (computing.size() >= ConfigManager.get().maxProfundidadePlanejamento || ++evaluations > 6000) {
			cuts++;
			return INF;
		}
		int cutsBefore = cuts;
		computing.add(item);
		double best = INF;
		try {
			best = Math.min(best, mineUnit(item));
			best = Math.min(best, craftUnit(item));
			best = Math.min(best, smeltUnit(item));
			best = Math.min(best, huntUnit(item));
		} finally {
			computing.remove(item);
		}
		// Um valor calculado com a árvore cortada pela profundidade é só parcial: não pode ser reaproveitado em outro ramo.
		if (cuts == cutsBefore) {
			memo.put(item, best);
		}
		return best;
	}

	public double targetUnitCost(ItemTarget target) {
		double best = INF;
		for (Item item : target.items()) {
			best = Math.min(best, unitCost(item));
		}
		return best;
	}

	private double mineUnit(Item item) {
		double best = INF;
		for (DropIndex.BlockSource src : DropIndex.blockSources(item)) {
			double c = mineSourceCost(src, 1);
			best = Math.min(best, c);
		}
		return best;
	}

	public double mineSourceCost(DropIndex.BlockSource src, int count) {
		if (!src.natural() || (src.silkOnly() && !hasSilkTool)) {
			return INF;
		}
		ResourceKnowledge.Info info = ResourceKnowledge.of(src.state());
		if (!info.dim().matches(level)) {
			return INF;
		}
		double tool = requiredToolCost(src.state());
		if (tool >= INF) {
			return INF;
		}
		double work = info.cost() * count / Math.max(0.3, src.averageCount());
		// Prefere o que existe por perto: recurso já visto a até 48 blocos, ou bioma atual compatível (ex.: num bosque de
		// bétulas, madeira de bétula em vez de carvalho distante).
		BlockPos here = companion.blockPosition();
		net.minecraft.world.level.block.Block block = src.state().getBlock();
		// Recursos de superfície (troncos, plantas...): vale o que ela VÊ ao redor, não o "bioma típico". Numa floresta de
		// bétulas, carvalho "de floresta" não pode sair mais barato que as bétulas ao lado.
		boolean surface = info.hasBiomeHint() && !info.underground();
		boolean seen = nearbyBlocks().contains(block)
			|| dev.autonomouscompanions.world.KnownResources.nearest(level, s -> s.is(block), here, 48, java.util.Set.of()) != null
			|| surface && dev.autonomouscompanions.world.SurfaceSurvey.around(level, companion).contains(block);
		if (seen) {
			work *= 0.4;
		} else if (surface) {
			// Não há nenhum por perto: exige viagem (mais ainda se nem o bioma ajuda).
			work *= info.biomeMatches(level.getBiome(here)) ? 1.5 : 3.0;
		} else if (info.hasBiomeHint() && info.biomeMatches(level.getBiome(here))) {
			work *= 0.7;
		}
		return work + tool;
	}

	/** Custo da ferramenta exigida para obter drops (0 se não exige ou se já possui uma adequada). */
	public double requiredToolCost(BlockState state) {
		if (!state.requiresCorrectToolForDrops()) {
			return 0;
		}
		if (Gear.bestOwnedFor(companion, state, true, false, level) != null) {
			return 0;
		}
		double best = INF;
		for (Item tool : Gear.allToolItems()) {
			if (!plannable(tool) || !new ItemStack(tool).isCorrectToolForDrops(state)) {
				continue;
			}
			best = Math.min(best, unitCost(tool));
		}
		return best;
	}

	/** Custo de uma ferramenta que acelera o trabalho (opcional), ou INF se não houver alternativa barata. */
	public double efficientToolCost(BlockState state) {
		if (Gear.bestOwnedFor(companion, state, false, false, level) != null) {
			return 0;
		}
		double best = INF;
		for (Item tool : Gear.allToolItems()) {
			ItemStack s = new ItemStack(tool);
			if (!plannable(tool) || s.getDestroySpeed(state) <= 1.0f) {
				continue;
			}
			best = Math.min(best, unitCost(tool));
		}
		return best;
	}

	private double craftUnit(Item item) {
		double best = INF;
		for (RecipeIndex.CraftingOption option : RecipeIndex.crafting(item)) {
			best = Math.min(best, craftCost(option, 1));
		}
		return best;
	}

	/** Custo para obter {@code count} unidades do produto desta receita. */
	public double craftCost(RecipeIndex.CraftingOption option, int count) {
		int crafts = (count + option.outputCount() - 1) / option.outputCount();
		if (isRecolor(option) && !ownsRecolorBase(option, crafts)) {
			return INF;
		}
		double sum = 0;
		for (RecipeIndex.IngredientCount ic : option.grouped()) {
			double unit = INF;
			int have = 0;
			for (Item i : ic.items()) {
				if (i == option.output()) {
					unit = INF;
					break;
				}
				have += companion.inventory().countFree(i);
				unit = Math.min(unit, unitCost(i));
			}
			int needed = Math.max(0, ic.count() * crafts - have);
			if (needed > 0 && unit >= INF) {
				return INF;
			}
			sum += unit * needed;
		}
		if (option.needsTable() && !hasStationAccess(Blocks.CRAFTING_TABLE.asItem())) {
			double table = Items.CRAFTING_TABLE == option.output() ? INF : unitCost(Items.CRAFTING_TABLE);
			if (table >= INF) {
				return INF;
			}
			sum += table;
		}
		return sum + CRAFT_OVERHEAD * crafts;
	}

	/**
	 * Ferramentas que valem a pena fabricar para conseguir algo: madeira, pedra, ferro e diamante. Ouro (fraca) e netherite
	 * (exige o Nether) nunca são o caminho — e incluí-las explodia o planejamento em cadeias absurdas, estourando o
	 * orçamento de avaliações (aí até uma cama ficava "impossível").
	 */
	private static boolean plannable(Item tool) {
		String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tool).getPath();
		return !path.startsWith("golden_") && !path.startsWith("netherite_");
	}

	/** Receitas de "tingir de novo" (ex.: qualquer cama + corante branco = cama branca). */
	private static boolean isRecolor(RecipeIndex.CraftingOption option) {
		return option.holder().id().identifier().getPath().startsWith("dye_");
	}

	/**
	 * Tingir só vale se ela já tem o item a tingir (a cama, a lã...). Antes, para fazer uma cama branca, o planejador
	 * escolhia "tingir uma cama" e ia procurar lírio-do-vale (corante branco) pelo mapa, com ovelhas ao lado.
	 */
	private boolean ownsRecolorBase(RecipeIndex.CraftingOption option, int crafts) {
		for (RecipeIndex.IngredientCount ic : option.grouped()) {
			boolean dye = false;
			int have = 0;
			for (Item i : ic.items()) {
				if (i instanceof net.minecraft.world.item.DyeItem) {
					dye = true;
				}
				have += companion.inventory().countFree(i);
			}
			if (!dye && have < ic.count() * crafts) {
				return false;
			}
		}
		return true;
	}

	private double smeltUnit(Item item) {
		double best = INF;
		for (RecipeIndex.CookingOption option : RecipeIndex.smelting(item)) {
			best = Math.min(best, smeltCost(option, 1));
		}
		return best;
	}

	public double smeltCost(RecipeIndex.CookingOption option, int count) {
		int inputs = (count + option.outputCount() - 1) / option.outputCount();
		double unit = INF;
		int have = 0;
		for (Item i : RecipeIndex.items(option.input())) {
			if (i == option.output()) {
				continue;
			}
			have += companion.inventory().countFree(i);
			unit = Math.min(unit, unitCost(i));
		}
		int needed = Math.max(0, inputs - have);
		if (needed > 0 && unit >= INF) {
			return INF;
		}
		double furnace = hasStationAccess(Items.FURNACE) ? 0 : (option.output() == Items.FURNACE ? INF : unitCost(Items.FURNACE));
		if (furnace >= INF) {
			return INF;
		}
		double fuel = 0.4 * inputs;
		return unit * needed + fuel + furnace + SMELT_OVERHEAD;
	}

	private double huntUnit(Item item) {
		double best = INF;
		for (DropIndex.EntitySource src : DropIndex.entitySources(item)) {
			best = Math.min(best, huntCost(src, 1));
		}
		return best;
	}

	public double huntCost(DropIndex.EntitySource src, int count) {
		// Animais que só existem no Nether (strider, hoglin) não são caminho fora de lá: antes, sem ovelhas brancas, ela
		// planejava caçar strider para fazer lã de barbante.
		if ((src.type() == net.minecraft.world.entity.EntityTypes.STRIDER || src.type() == net.minecraft.world.entity.EntityTypes.HOGLIN)
			&& level.dimension() != net.minecraft.world.level.Level.NETHER) {
			return INF;
		}
		double base = 5.0 * count / Math.max(0.25, src.averageCount());
		// Animais vistos por perto barateiam muito a caça; um tipo ausente exige procurar (mais caro).
		return base * (animalsNearby(src.type()) > 0 ? 0.5 : 1.6);
	}

	private final java.util.Map<net.minecraft.world.entity.EntityType<?>, Integer> animalsNear = new java.util.HashMap<>();

	/** Quantos animais vivos deste tipo há a até 48 blocos (contagem guardada durante este planejamento). */
	private int animalsNearby(net.minecraft.world.entity.EntityType<?> type) {
		return animalsNear.computeIfAbsent(type, t -> level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
			companion.getBoundingBox().inflate(48, 16, 48), e -> e.getType() == t && e.isAlive() && !e.isBaby()).size());
	}

	private boolean hasStationAccess(Item station) {
		if (companion.inventory().countFree(station) > 0) {
			return true;
		}
		BlockPos center = companion.blockPosition();
		var block = station == Items.FURNACE ? Blocks.FURNACE : Blocks.CRAFTING_TABLE;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-6, -2, -6), center.offset(6, 2, 6))) {
			if (level.getBlockState(p).is(block)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ métodos concretos

	/** Um caminho concreto para obter itens. */
	public sealed interface Method permits Craft, Smelt, Mine, Hunt {
		double cost();

		Component describe();
	}

	public record Craft(RecipeIndex.CraftingOption option, double cost) implements Method {
		@Override
		public Component describe() {
			return Component.translatable("method.autonomouscompanions.craft", new ItemStack(option.output()).getHoverName());
		}
	}

	public record Smelt(RecipeIndex.CookingOption option, double cost) implements Method {
		@Override
		public Component describe() {
			return Component.translatable("method.autonomouscompanions.smelt", new ItemStack(option.output()).getHoverName());
		}
	}

	public record Mine(List<DropIndex.BlockSource> sources, boolean silk, double cost) implements Method {
		@Override
		public Component describe() {
			return Component.translatable("method.autonomouscompanions.mine", sources.getFirst().state().getBlock().getName());
		}
	}

	public record Hunt(List<DropIndex.EntitySource> sources, double cost) implements Method {
		@Override
		public Component describe() {
			return Component.translatable("method.autonomouscompanions.hunt", sources.getFirst().type().getDescription());
		}
	}

	/** Métodos viáveis para obter {@code count} itens do alvo, do mais barato ao mais caro. */
	public List<Method> methods(ItemTarget target, int count, Set<Item> chain, boolean allowHunt) {
		List<Method> list = new ArrayList<>();
		// Mineração: agrupa todas as fontes naturais viáveis (o escaneador procura qualquer uma delas).
		List<DropIndex.BlockSource> normal = new ArrayList<>();
		List<DropIndex.BlockSource> silk = new ArrayList<>();
		double normalCost = INF;
		double silkCost = INF;
		for (Item item : target.items()) {
			for (DropIndex.BlockSource src : DropIndex.blockSources(item)) {
				double c = mineSourceCost(src, count);
				if (c >= INF) {
					continue;
				}
				if (src.silkOnly()) {
					silk.add(src);
					silkCost = Math.min(silkCost, c);
				} else {
					normal.add(src);
					normalCost = Math.min(normalCost, c);
				}
			}
		}
		if (!normal.isEmpty()) {
			normal.sort(Comparator.comparingDouble(s -> mineSourceCost(s, 1)));
			list.add(new Mine(normal, false, normalCost));
		}
		if (!silk.isEmpty()) {
			list.add(new Mine(silk, true, silkCost + 2));
		}
		Set<Item> withTarget = new HashSet<>(chain);
		withTarget.addAll(target.items());
		List<DropIndex.EntitySource> huntSources = new ArrayList<>();
		double huntCost = INF;
		for (Item item : target.items()) {
			if (chain.contains(item)) {
				continue; // ciclo: este item já está sendo obtido mais acima na cadeia
			}
			for (RecipeIndex.CraftingOption option : RecipeIndex.crafting(item)) {
				if (usesChainItem(option, withTarget, (count + option.outputCount() - 1) / option.outputCount())) {
					continue;
				}
				double c = craftCost(option, count);
				if (c < INF) {
					list.add(new Craft(option, c));
				}
			}
			for (RecipeIndex.CookingOption option : RecipeIndex.smelting(item)) {
				boolean loops = false;
				for (Item in : RecipeIndex.items(option.input())) {
					if (withTarget.contains(in)) {
						loops = true;
						break;
					}
				}
				if (loops) {
					continue;
				}
				double c = smeltCost(option, count);
				if (c < INF) {
					list.add(new Smelt(option, c));
				}
			}
			if (allowHunt) {
				for (DropIndex.EntitySource s : DropIndex.entitySources(item)) {
					double c = huntCost(s, count);
					if (c < INF) {
						huntSources.add(s);
						huntCost = Math.min(huntCost, c);
					}
				}
			}
		}
		// Uma caçada só para o alvo inteiro: com "qualquer carne", ela vai no animal mais perto de qualquer espécie, em vez de
		// se prender à espécie de um item (e atravessar o mapa atrás de vacas com ovelhas ao lado).
		if (!huntSources.isEmpty()) {
			list.add(new Hunt(huntSources, huntCost));
		}
		list.sort(Comparator.comparingDouble(Method::cost));
		// Mantém só os métodos razoáveis (evita tentar dezenas de receitas exóticas).
		if (list.size() > 6) {
			return new ArrayList<>(list.subList(0, 6));
		}
		return list;
	}

	/**
	 * Verdadeiro se a receita depende de um item que já está sendo obtido mais acima na cadeia (ciclo). Ingredientes que
	 * ela já tem livres no inventário em quantidade suficiente não contam: usá-los não exige obter nada.
	 */
	private boolean usesChainItem(RecipeIndex.CraftingOption option, Set<Item> chain, int crafts) {
		for (RecipeIndex.IngredientCount ic : option.grouped()) {
			boolean allInChain = true;
			int haveFree = 0;
			for (Item i : ic.items()) {
				if (!chain.contains(i)) {
					allInChain = false;
					break;
				}
				haveFree += companion.inventory().countFree(i);
			}
			if (allInChain && haveFree < ic.count() * Math.max(1, crafts)) {
				return true;
			}
		}
		return false;
	}
}
