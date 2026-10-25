package dev.autonomouscompanions.mining;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.world.BlockPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * Índice reverso "item → fontes", construído a partir das tabelas de loot reais (blocos e animais) dos registros
 * instalados, incluindo blocos e animais de outros mods. A construção é fatiada em vários ticks (orçamento de tempo)
 * para não travar o servidor ao iniciar.
 */
public final class DropIndex {
	public record BlockSource(BlockState state, boolean silkOnly, boolean natural, float averageCount) {
	}

	public record EntitySource(EntityType<?> type, float averageCount) {
	}

	private static final Map<Item, List<BlockSource>> BLOCKS = new HashMap<>();
	private static final Map<Item, List<EntitySource>> ENTITIES = new HashMap<>();
	private static final Map<BlockState, HarvestAnalyzer.Analysis> ANALYSES = new HashMap<>();
	private static @Nullable Iterator<Block> blockIterator;
	private static @Nullable Iterator<EntityType<?>> entityIterator;
	private static boolean ready;

	private DropIndex() {
	}

	public static synchronized void reset() {
		BLOCKS.clear();
		ENTITIES.clear();
		ANALYSES.clear();
		blockIterator = BuiltInRegistries.BLOCK.iterator();
		entityIterator = BuiltInRegistries.ENTITY_TYPE.iterator();
		ready = false;
	}

	public static boolean isReady() {
		return ready;
	}

	/** Processa parte do índice usando até {@code nanosBudget} nanossegundos. */
	public static synchronized void tick(MinecraftServer server, long nanosBudget) {
		if (ready || blockIterator == null) {
			return;
		}
		ServerLevel level = server.overworld();
		long start = System.nanoTime();
		BlockPos probe = level.getRespawnData().globalPos().pos();
		while (blockIterator.hasNext() && System.nanoTime() - start < nanosBudget) {
			indexBlock(level, probe, blockIterator.next());
		}
		while (!blockIterator.hasNext() && entityIterator != null && entityIterator.hasNext() && System.nanoTime() - start < nanosBudget) {
			indexEntity(level, entityIterator.next());
		}
		if (!blockIterator.hasNext() && (entityIterator == null || !entityIterator.hasNext())) {
			ready = true;
			AutonomousCompanions.LOGGER.info("Índice de obtenção pronto: {} itens de blocos, {} itens de animais.", BLOCKS.size(), ENTITIES.size());
		}
	}

	/** Garante o índice completo imediatamente (usado em testes e na primeira análise solicitada). */
	public static synchronized void ensureReady(MinecraftServer server) {
		if (blockIterator == null) {
			reset();
		}
		while (!ready) {
			tick(server, Long.MAX_VALUE / 4);
		}
	}

	private static void indexBlock(ServerLevel level, BlockPos probe, Block block) {
		List<BlockState> states = new ArrayList<>();
		states.add(block.defaultBlockState());
		for (Property<?> property : block.getStateDefinition().getProperties()) {
			if (property instanceof IntegerProperty ip && property.getName().equals("age")) {
				int max = ip.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
				states.add(block.defaultBlockState().setValue(ip, max));
			}
		}
		for (BlockState state : states) {
			HarvestAnalyzer.Analysis a;
			try {
				a = HarvestAnalyzer.analyze(level, state, probe);
			} catch (RuntimeException e) {
				continue;
			}
			ANALYSES.put(state, a);
			if (a.unbreakable()) {
				continue;
			}
			boolean natural = BlockPolicy.isNatural(state);
			for (Item item : a.normalDrops()) {
				BLOCKS.computeIfAbsent(item, k -> new ArrayList<>()).add(new BlockSource(state, false, natural, 1.0f));
			}
			for (Item item : a.silkDrops()) {
				if (!a.normalDrops().contains(item)) {
					BLOCKS.computeIfAbsent(item, k -> new ArrayList<>()).add(new BlockSource(state, true, natural, 1.0f));
				}
			}
		}
	}

	private static void indexEntity(ServerLevel level, EntityType<?> type) {
		if (type.getCategory() != MobCategory.CREATURE) {
			return;
		}
		Entity entity;
		try {
			entity = type.create(level, EntitySpawnReason.LOAD);
		} catch (RuntimeException e) {
			return;
		}
		if (!(entity instanceof Animal animal) || entity instanceof TamableAnimal || entity instanceof AbstractHorse) {
			return;
		}
		var lootTable = animal.getLootTable();
		if (lootTable.isEmpty()) {
			return;
		}
		Map<Item, Integer> totals = new HashMap<>();
		int samples = 8;
		try {
			for (int i = 0; i < samples; i++) {
				animal.dropFromLootTable(level, level.damageSources().generic(), false, lootTable.get(), (ItemStack stack) -> totals.merge(stack.getItem(),
					stack.getCount(), Integer::sum));
			}
		} catch (RuntimeException e) {
			return;
		}
		for (var e : totals.entrySet()) {
			ENTITIES.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new EntitySource(type, e.getValue() / (float) samples));
		}
	}

	public static synchronized List<BlockSource> blockSources(Item item) {
		return BLOCKS.getOrDefault(item, List.of());
	}

	public static synchronized List<EntitySource> entitySources(Item item) {
		return ENTITIES.getOrDefault(item, List.of());
	}

	public static synchronized HarvestAnalyzer.@Nullable Analysis analysis(BlockState state) {
		return ANALYSES.get(state);
	}

	/** Itens obtidos de animais (para a seleção de carnes na interface). */
	public static synchronized List<Item> huntableItems() {
		return new ArrayList<>(ENTITIES.keySet());
	}

	public static boolean isHuntable(LivingEntity e) {
		return e instanceof Animal && !(e instanceof TamableAnimal) && !(e instanceof AbstractHorse) && e.getType().getCategory() == MobCategory.CREATURE;
	}
}
