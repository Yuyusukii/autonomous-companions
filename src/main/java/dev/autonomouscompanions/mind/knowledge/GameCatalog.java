package dev.autonomouscompanions.mind.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.crafting.RecipeIndex;
import dev.autonomouscompanions.mining.DropIndex;
import dev.autonomouscompanions.mining.HarvestAnalyzer;
import dev.autonomouscompanions.world.BlockPolicy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Catálogo completo gerado a partir da versão efetivamente carregada (jogo base, datapacks do mundo e outros mods): lê os
 * mesmos registros que o gerador de dados do jogo usa, então nada é escrito à mão. Grava em
 * {@code <mundo>/autonomouscompanions/catalogo/}:
 * <ul>
 *   <li>{@code blocks.jsonl}: id, nome, propriedades e estados, dureza, resistência, luz, ferramenta adequada e a ferramenta
 *       mínima (testada nível a nível com a regra do próprio jogo), drops com e sem Toque Suave, natural ou não;</li>
 *   <li>{@code items.jsonl}: pilha máxima, durabilidade, alimento, equipamento, combustível, tipo de ferramenta e como
 *       obter (receitas, fornalha, blocos e criaturas que o derrubam);</li>
 *   <li>{@code recipes.jsonl}: todas as receitas (bancada e fornalha com resultado e ingredientes; demais tipos com id,
 *       tipo e ingredientes);</li>
 *   <li>{@code loot.jsonl}: drops de blocos e de criaturas (criaturas: amostragem da tabela de saque, 8 rodadas);</li>
 *   <li>{@code entities.jsonl}: categoria, hostilidade, tamanho, atributos básicos, drops e biomas onde aparece;</li>
 *   <li>{@code enchantments.jsonl}, {@code potions.jsonl}, {@code effects.jsonl}, {@code biomes.jsonl},
 *       {@code structures.jsonl} e {@code manifest.json} (versão, mods, contagens).</li>
 * </ul>
 * Só é regravado quando a versão, a lista de mods ou os dados mudam. A amostragem de drops de criaturas e o índice de
 * drops de blocos rodam na thread do servidor; a montagem e a gravação dos arquivos, numa thread separada.
 */
public final class GameCatalog {
	public static final String FOLDER = "autonomouscompanions/catalogo";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final List<Item> PICKAXES = List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE,
		Items.NETHERITE_PICKAXE);
	private static final List<Item> AXES = List.of(Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE);
	private static final List<Item> SHOVELS = List.of(Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL,
		Items.NETHERITE_SHOVEL);
	private static final List<Item> HOES = List.of(Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE);

	private static volatile boolean started;
	/** Uma exportação por vez (a automática e a do comando nunca gravam os mesmos arquivos juntas). */
	private static final Object WRITE_LOCK = new Object();
	/** Amostragem dos drops de criaturas aos poucos (no máximo ~2 ms por tick), antes da exportação automática. */
	private static java.util.@org.jspecify.annotations.Nullable Iterator<EntityType<?>> sampling;
	private static Map<EntityType<?>, Map<Item, Float>> sampled = new HashMap<>();

	public record Result(Path folder, Map<String, Integer> counts, long millis, boolean skipped) {
	}

	private GameCatalog() {
	}

	public static Path folder(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FOLDER);
	}

	public static void reset() {
		started = false;
		sampling = null;
		sampled = new HashMap<>();
	}

	/** Uma vez por sessão, quando os índices ficam prontos: exporta se a versão, os mods ou os dados mudaram. */
	public static void tick(MinecraftServer server) {
		if (started || !DropIndex.isReady() || !RecipeIndex.isBuilt()) {
			return;
		}
		if (sampling == null) {
			sampling = BuiltInRegistries.ENTITY_TYPE.iterator();
			sampled = new HashMap<>();
		}
		long t0 = System.nanoTime();
		while (sampling.hasNext() && System.nanoTime() - t0 < 2_000_000L) {
			sampleOne(server.overworld(), sampling.next(), sampled);
		}
		if (sampling.hasNext()) {
			return;
		}
		started = true;
		sampling = null;
		export(server, false, sampled).exceptionally(e -> {
			AutonomousCompanions.LOGGER.warn("Catálogo: falha ao exportar", e);
			return null;
		});
	}

	/** Exporta o catálogo (na thread do servidor só a parte que precisa do mundo; o resto em segundo plano). */
	public static CompletableFuture<Result> export(MinecraftServer server, boolean force) {
		return export(server, force, null);
	}

	private static CompletableFuture<Result> export(MinecraftServer server, boolean force, @org.jspecify.annotations.Nullable Map<EntityType<?>, Map<Item, Float>> presampled) {
		long t0 = System.nanoTime();
		DropIndex.ensureReady(server);
		ServerLevel level = server.overworld();
		Map<EntityType<?>, Map<Item, Float>> entityDrops = presampled != null ? presampled : sampleEntityDrops(level);
		Map<BlockState, HarvestAnalyzer.Analysis> analyses = new HashMap<>();
		for (Block b : BuiltInRegistries.BLOCK) {
			var a = DropIndex.analysis(b.defaultBlockState());
			if (a != null) {
				analyses.put(b.defaultBlockState(), a);
			}
		}
		RegistryAccess access = server.registryAccess();
		Collection<RecipeHolder<?>> recipes = List.copyOf(server.getRecipeManager().getRecipes());
		List<RecipeIndex.CraftingOption> crafting = RecipeIndex.allCrafting();
		List<RecipeIndex.CookingOption> cooking = RecipeIndex.allCooking();
		String fingerprint = fingerprint(recipes.size());
		Path dir = folder(server);
		int worldMinY = level.getMinY();
		int worldMaxY = level.getMaxY();
		long mainThreadNanos = System.nanoTime() - t0;
		return CompletableFuture.supplyAsync(() -> write(dir, access, recipes, crafting, cooking, analyses, entityDrops, fingerprint, force, t0,
			mainThreadNanos, worldMinY, worldMaxY), r -> {
				Thread t = new Thread(r, "AC-Catalogo");
				t.setDaemon(true);
				t.start();
			});
	}

	private static String fingerprint(int recipeCount) {
		List<String> mods = new ArrayList<>();
		FabricLoader.getInstance().getAllMods().forEach(m -> mods.add(m.getMetadata().getId() + "@" + m.getMetadata().getVersion().getFriendlyString()));
		mods.sort(String::compareTo);
		return SharedConstants.getCurrentVersion().id() + "|" + String.join(",", mods) + "|" + BuiltInRegistries.BLOCK.size() + "|"
			+ BuiltInRegistries.ITEM.size() + "|" + BuiltInRegistries.ENTITY_TYPE.size() + "|" + recipeCount;
	}

	// ------------------------------------------------------------------ amostragem (thread do servidor)

	/** Drops de criaturas: instancia cada tipo (fora do mundo) e roda a tabela de saque 8 vezes. */
	private static Map<EntityType<?>, Map<Item, Float>> sampleEntityDrops(ServerLevel level) {
		Map<EntityType<?>, Map<Item, Float>> out = new HashMap<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			sampleOne(level, type, out);
		}
		return out;
	}

	private static void sampleOne(ServerLevel level, EntityType<?> type, Map<EntityType<?>, Map<Item, Float>> out) {
		if (type == net.minecraft.world.entity.EntityTypes.PLAYER || !DefaultAttributes.hasSupplier(type)) {
			return;
		}
		Entity entity;
		try {
			entity = type.create(level, EntitySpawnReason.LOAD);
		} catch (RuntimeException e) {
			return;
		}
		if (!(entity instanceof LivingEntity living)) {
			return;
		}
		var table = living.getLootTable();
		if (table.isEmpty()) {
			return;
		}
		Map<Item, Integer> totals = new HashMap<>();
		int samples = 8;
		try {
			for (int i = 0; i < samples; i++) {
				living.dropFromLootTable(level, level.damageSources().generic(), false, table.get(),
					(ItemStack stack) -> totals.merge(stack.getItem(), stack.getCount(), Integer::sum));
			}
		} catch (RuntimeException e) {
			return;
		}
		Map<Item, Float> avg = new LinkedHashMap<>();
		totals.forEach((item, n) -> avg.put(item, n / (float) samples));
		out.put(type, avg);
	}

	// ------------------------------------------------------------------ montagem e gravação (segundo plano)

	private static Result write(Path dir, RegistryAccess access, Collection<RecipeHolder<?>> recipes, List<RecipeIndex.CraftingOption> crafting,
		List<RecipeIndex.CookingOption> cooking, Map<BlockState, HarvestAnalyzer.Analysis> analyses, Map<EntityType<?>, Map<Item, Float>> entityDrops,
		String fingerprint, boolean force, long t0, long mainThreadNanos, int worldMinY, int worldMaxY) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		synchronized (WRITE_LOCK) {
		try {
			Path manifest = dir.resolve("manifest.json");
			if (!force && Files.exists(manifest)) {
				JsonObject old = GSON.fromJson(Files.readString(manifest, StandardCharsets.UTF_8), JsonObject.class);
				if (old != null && old.has("fingerprint") && old.get("fingerprint").getAsString().equals(fingerprint)) {
					return new Result(dir, Map.of(), (System.nanoTime() - t0) / 1_000_000, true);
				}
			}
			Files.createDirectories(dir);
			counts.put("blocks", writeLines(dir.resolve("blocks.jsonl"), blocks(analyses)));
			Map<EntityType<?>, List<String>> spawnBiomes = new HashMap<>();
			counts.put("biomes", writeLines(dir.resolve("biomes.jsonl"), biomes(access, spawnBiomes)));
			counts.put("items", writeLines(dir.resolve("items.jsonl"), items(crafting, cooking, entityDrops)));
			counts.put("recipes", writeLines(dir.resolve("recipes.jsonl"), recipes(recipes, crafting, cooking)));
			counts.put("loot", writeLines(dir.resolve("loot.jsonl"), loot(analyses, entityDrops)));
			counts.put("entities", writeLines(dir.resolve("entities.jsonl"), entities(entityDrops, spawnBiomes)));
			counts.put("enchantments", writeLines(dir.resolve("enchantments.jsonl"), enchantments(access)));
			counts.put("potions", writeLines(dir.resolve("potions.jsonl"), potions()));
			counts.put("effects", writeLines(dir.resolve("effects.jsonl"), effects()));
			counts.put("structures", writeLines(dir.resolve("structures.jsonl"), structures(access)));
			counts.put("ores", writeLines(dir.resolve("ores.jsonl"), ores(access, worldMinY, worldMaxY)));
			long millis = (System.nanoTime() - t0) / 1_000_000;
			JsonObject m = new JsonObject();
			m.addProperty("versao_do_jogo", SharedConstants.getCurrentVersion().name());
			m.addProperty("fingerprint", fingerprint);
			JsonArray mods = new JsonArray();
			FabricLoader.getInstance().getAllMods().forEach(mod -> mods.add(mod.getMetadata().getId() + " " + mod.getMetadata().getVersion().getFriendlyString()));
			m.add("mods", mods);
			JsonObject c = new JsonObject();
			counts.forEach(c::addProperty);
			m.add("contagens", c);
			m.addProperty("milissegundos", millis);
			m.addProperty("milissegundos_na_thread_do_servidor", mainThreadNanos / 1_000_000);
			Path tmp = dir.resolve("manifest.json.tmp");
			Files.writeString(tmp, GSON.toJson(m), StandardCharsets.UTF_8);
			replace(tmp, manifest);
			AutonomousCompanions.LOGGER.info("Catálogo exportado em {} ({} ms, {} ms na thread do servidor): {}", dir, millis, mainThreadNanos / 1_000_000,
				counts);
			return new Result(dir, counts, millis, false);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		}
	}

	/** Grava num arquivo temporário e troca de uma vez: quem lê nunca vê um arquivo pela metade. */
	private static int writeLines(Path file, List<JsonObject> rows) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			for (JsonObject o : rows) {
				w.write(GSON.toJson(o));
				w.newLine();
			}
		}
		replace(tmp, file);
		return rows.size();
	}

	private static void replace(Path tmp, Path file) throws IOException {
		try {
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	private static String id(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	private static JsonArray ids(Collection<Item> items) {
		JsonArray a = new JsonArray();
		items.stream().map(GameCatalog::id).sorted().forEach(a::add);
		return a;
	}

	private static JsonArray ingredient(Ingredient ing) {
		return ids(RecipeIndex.items(ing));
	}

	private static <T extends Comparable<T>> JsonArray values(Property<T> p) {
		JsonArray a = new JsonArray();
		for (T v : p.getPossibleValues()) {
			a.add(p.getName(v));
		}
		return a;
	}

	/** Tipo de ferramenta adequada (pela ferramenta de referência do analisador do mod), em português; nulo se nenhuma ajuda. */
	public static @org.jspecify.annotations.Nullable String toolKind(BlockState state) {
		ItemStack ref = HarvestAnalyzer.bestReferenceTool(state);
		if (ref == null) {
			return null;
		}
		return ref.is(ItemTags.PICKAXES) ? "picareta" : ref.is(ItemTags.AXES) ? "machado" : ref.is(ItemTags.SHOVELS) ? "pá" : ref.is(ItemTags.HOES) ? "enxada"
			: ref.is(Items.SHEARS) ? "tesoura" : ref.is(ItemTags.SWORDS) ? "espada" : null;
	}

	/**
	 * Ferramenta mínima que coleta os drops (testada nível a nível — madeira, pedra, ferro, diamante, netherite — com a
	 * regra do próprio jogo); nula se o bloco não exige ferramenta.
	 */
	public static @org.jspecify.annotations.Nullable Item minimumTool(BlockState state) {
		if (!state.requiresCorrectToolForDrops()) {
			return null;
		}
		String kind = toolKind(state);
		List<Item> tiers = kind == null ? List.of() : switch (kind) {
			case "picareta" -> PICKAXES;
			case "machado" -> AXES;
			case "pá" -> SHOVELS;
			case "enxada" -> HOES;
			default -> List.of();
		};
		for (Item t : tiers) {
			if (new ItemStack(t).isCorrectToolForDrops(state)) {
				return t;
			}
		}
		ItemStack ref = HarvestAnalyzer.bestReferenceTool(state);
		return ref == null ? null : ref.getItem();
	}

	private static void tools(JsonObject o, BlockState state) {
		o.addProperty("ferramenta", toolKind(state));
		if (state.requiresCorrectToolForDrops()) {
			Item min = minimumTool(state);
			o.addProperty("ferramenta_minima", min == null ? null : id(min));
		}
	}

	private static List<JsonObject> blocks(Map<BlockState, HarvestAnalyzer.Analysis> analyses) {
		List<JsonObject> rows = new ArrayList<>();
		for (Block b : BuiltInRegistries.BLOCK) {
			BlockState s = b.defaultBlockState();
			JsonObject o = new JsonObject();
			o.addProperty("id", id(b));
			o.addProperty("nome", b.getName().getString());
			JsonObject props = new JsonObject();
			for (Property<?> p : b.getStateDefinition().getProperties()) {
				props.add(p.getName(), values(p));
			}
			o.add("propriedades", props);
			o.addProperty("estados", b.getStateDefinition().getPossibleStates().size());
			o.addProperty("dureza", b.defaultDestroyTime());
			o.addProperty("resistencia", b.getExplosionResistance());
			o.addProperty("luz", s.getLightEmission());
			o.addProperty("requer_ferramenta_correta", s.requiresCorrectToolForDrops());
			tools(o, s);
			var a = analyses.get(s);
			if (a != null) {
				o.addProperty("inquebravel", a.unbreakable());
				o.add("drops", ids(a.normalDrops()));
				Set<Item> silkOnly = new java.util.HashSet<>(a.silkDrops());
				silkOnly.removeAll(a.normalDrops());
				o.add("drops_so_com_toque_suave", ids(silkOnly));
			}
			o.addProperty("natural", BlockPolicy.isNatural(s));
			o.addProperty("item", b.asItem() == Items.AIR ? null : id(b.asItem()));
			rows.add(o);
		}
		return rows;
	}

	private static List<JsonObject> items(List<RecipeIndex.CraftingOption> crafting, List<RecipeIndex.CookingOption> cooking,
		Map<EntityType<?>, Map<Item, Float>> entityDrops) {
		Map<Item, Integer> crafted = new HashMap<>();
		crafting.forEach(c -> crafted.merge(c.output(), 1, Integer::sum));
		Map<Item, List<RecipeIndex.CookingOption>> smelted = new HashMap<>();
		cooking.forEach(c -> smelted.computeIfAbsent(c.output(), k -> new ArrayList<>()).add(c));
		Map<Item, List<String>> droppedBy = new HashMap<>();
		entityDrops.forEach((type, drops) -> drops.keySet().forEach(i -> droppedBy.computeIfAbsent(i, k -> new ArrayList<>())
			.add(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())));
		List<JsonObject> rows = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			ItemStack st = new ItemStack(item);
			JsonObject o = new JsonObject();
			o.addProperty("id", id(item));
			o.addProperty("nome", st.getHoverName().getString());
			o.addProperty("pilha_maxima", item.getDefaultMaxStackSize());
			if (st.isDamageableItem()) {
				o.addProperty("durabilidade", st.getMaxDamage());
			}
			var food = st.get(DataComponents.FOOD);
			if (food != null) {
				JsonObject f = new JsonObject();
				f.addProperty("nutricao", food.nutrition());
				f.addProperty("saturacao", food.saturation());
				o.add("alimento", f);
			}
			var eq = st.get(DataComponents.EQUIPPABLE);
			if (eq != null) {
				o.addProperty("equipavel_em", eq.slot().getName());
			}
			o.addProperty("combustivel", st.has(DataComponents.COOKING_FUEL));
			String tool = st.is(ItemTags.PICKAXES) ? "picareta" : st.is(ItemTags.AXES) ? "machado" : st.is(ItemTags.SHOVELS) ? "pá"
				: st.is(ItemTags.HOES) ? "enxada" : st.is(ItemTags.SWORDS) ? "espada" : null;
			if (tool != null) {
				o.addProperty("ferramenta", tool);
			}
			Block block = Block.byItem(item);
			if (block != Blocks.AIR) {
				o.addProperty("bloco", id(block));
			}
			JsonObject how = new JsonObject();
			how.addProperty("receitas_de_bancada", crafted.getOrDefault(item, 0));
			JsonArray fornalha = new JsonArray();
			for (var c : smelted.getOrDefault(item, List.of())) {
				fornalha.add(ingredient(c.input()));
			}
			how.add("fornalha_de", fornalha);
			JsonArray fromBlocks = new JsonArray();
			DropIndex.blockSources(item).stream().map(src -> id(src.state().getBlock()) + (src.silkOnly() ? " (toque suave)" : "")).distinct().limit(12)
				.forEach(fromBlocks::add);
			how.add("blocos", fromBlocks);
			JsonArray fromEntities = new JsonArray();
			droppedBy.getOrDefault(item, List.of()).stream().sorted().forEach(fromEntities::add);
			how.add("criaturas", fromEntities);
			o.add("como_obter", how);
			rows.add(o);
		}
		return rows;
	}

	private static List<JsonObject> recipes(Collection<RecipeHolder<?>> all, List<RecipeIndex.CraftingOption> crafting,
		List<RecipeIndex.CookingOption> cooking) {
		Map<String, JsonObject> byId = new TreeMap<>();
		for (var c : crafting) {
			JsonObject o = new JsonObject();
			o.addProperty("id", c.holder().id().identifier().toString());
			o.addProperty("tipo", c.shaped() ? "bancada_com_forma" : "bancada_sem_forma");
			o.addProperty("resultado", id(c.output()));
			o.addProperty("quantidade", c.outputCount());
			o.addProperty("precisa_de_bancada", c.needsTable());
			JsonArray ing = new JsonArray();
			for (var g : c.grouped()) {
				JsonObject e = new JsonObject();
				e.add("itens", ingredient(g.ingredient()));
				e.addProperty("quantidade", g.count());
				ing.add(e);
			}
			o.add("ingredientes", ing);
			byId.put(o.get("id").getAsString(), o);
		}
		for (var c : cooking) {
			JsonObject o = new JsonObject();
			o.addProperty("id", c.holder().id().identifier().toString());
			o.addProperty("tipo", BuiltInRegistries.RECIPE_TYPE.getKey(c.holder().value().getType()).getPath());
			o.addProperty("resultado", id(c.output()));
			o.addProperty("quantidade", c.outputCount());
			o.addProperty("tempo_ticks", c.cookTime());
			JsonArray ing = new JsonArray();
			JsonObject e = new JsonObject();
			e.add("itens", ingredient(c.input()));
			e.addProperty("quantidade", 1);
			ing.add(e);
			o.add("ingredientes", ing);
			byId.put(o.get("id").getAsString(), o);
		}
		for (RecipeHolder<?> h : all) {
			String rid = h.id().identifier().toString();
			if (byId.containsKey(rid)) {
				continue;
			}
			// Outros tipos (cortador de pedras, ferraria, especiais): id, tipo e ingredientes do jogo.
			JsonObject o = new JsonObject();
			o.addProperty("id", rid);
			o.addProperty("tipo", BuiltInRegistries.RECIPE_TYPE.getKey(h.value().getType()).getPath());
			JsonArray ing = new JsonArray();
			try {
				for (Ingredient i : h.value().placementInfo().ingredients()) {
					JsonObject e = new JsonObject();
					e.add("itens", ingredient(i));
					e.addProperty("quantidade", 1);
					ing.add(e);
				}
			} catch (RuntimeException ignored) {
				// receitas especiais (ex.: tingir armadura) não têm ingredientes fixos
			}
			o.add("ingredientes", ing);
			byId.put(rid, o);
		}
		return new ArrayList<>(byId.values());
	}

	private static List<JsonObject> loot(Map<BlockState, HarvestAnalyzer.Analysis> analyses, Map<EntityType<?>, Map<Item, Float>> entityDrops) {
		List<JsonObject> rows = new ArrayList<>();
		analyses.entrySet().stream().sorted((a, b) -> id(a.getKey().getBlock()).compareTo(id(b.getKey().getBlock()))).forEach(e -> {
			if (e.getValue().unbreakable()) {
				return;
			}
			JsonObject o = new JsonObject();
			o.addProperty("fonte", id(e.getKey().getBlock()));
			o.addProperty("tipo", "bloco");
			o.add("itens", ids(e.getValue().normalDrops()));
			rows.add(o);
		});
		entityDrops.entrySet().stream().sorted((a, b) -> BuiltInRegistries.ENTITY_TYPE.getKey(a.getKey()).compareTo(BuiltInRegistries.ENTITY_TYPE.getKey(b.getKey())))
			.forEach(e -> {
				JsonObject o = new JsonObject();
				o.addProperty("fonte", BuiltInRegistries.ENTITY_TYPE.getKey(e.getKey()).toString());
				o.addProperty("tipo", "criatura");
				JsonArray items = new JsonArray();
				e.getValue().forEach((item, avg) -> {
					JsonObject x = new JsonObject();
					x.addProperty("item", id(item));
					x.addProperty("media", avg);
					items.add(x);
				});
				o.add("itens", items);
				rows.add(o);
			});
		return rows;
	}

	private static List<JsonObject> entities(Map<EntityType<?>, Map<Item, Float>> entityDrops, Map<EntityType<?>, List<String>> spawnBiomes) {
		List<JsonObject> rows = new ArrayList<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			JsonObject o = new JsonObject();
			o.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
			o.addProperty("nome", type.getDescription().getString());
			o.addProperty("categoria", type.getCategory().getName());
			o.addProperty("hostil", type.getCategory() == MobCategory.MONSTER);
			o.addProperty("largura", type.getWidth());
			o.addProperty("altura", type.getHeight());
			o.addProperty("imune_a_fogo", type.fireImmune());
			if (DefaultAttributes.hasSupplier(type)) {
				@SuppressWarnings("unchecked")
				AttributeSupplier sup = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
				JsonObject at = new JsonObject();
				if (sup.hasAttribute(Attributes.MAX_HEALTH)) {
					at.addProperty("vida", sup.getBaseValue(Attributes.MAX_HEALTH));
				}
				if (sup.hasAttribute(Attributes.ATTACK_DAMAGE)) {
					at.addProperty("dano", sup.getBaseValue(Attributes.ATTACK_DAMAGE));
				}
				if (sup.hasAttribute(Attributes.ARMOR)) {
					at.addProperty("armadura", sup.getBaseValue(Attributes.ARMOR));
				}
				if (sup.hasAttribute(Attributes.MOVEMENT_SPEED)) {
					at.addProperty("velocidade", sup.getBaseValue(Attributes.MOVEMENT_SPEED));
				}
				o.add("atributos", at);
			}
			Map<Item, Float> drops = entityDrops.get(type);
			if (drops != null) {
				o.add("drops", ids(drops.keySet()));
			}
			JsonArray biomes = new JsonArray();
			spawnBiomes.getOrDefault(type, List.of()).stream().sorted().forEach(biomes::add);
			o.add("biomas", biomes);
			rows.add(o);
		}
		return rows;
	}

	private static List<JsonObject> biomes(RegistryAccess access, Map<EntityType<?>, List<String>> spawnBiomes) {
		List<JsonObject> rows = new ArrayList<>();
		access.lookupOrThrow(Registries.BIOME).listElements().forEach(ref -> {
			String bid = ref.key().identifier().toString();
			var biome = ref.value();
			JsonObject o = new JsonObject();
			o.addProperty("id", bid);
			o.addProperty("temperatura", biome.getBaseTemperature());
			o.addProperty("precipitacao", biome.hasPrecipitation());
			MobSpawnSettings spawns = biome.getAttributes().applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY);
			JsonObject mobs = new JsonObject();
			for (MobCategory cat : MobCategory.values()) {
				JsonArray list = new JsonArray();
				for (var w : spawns.getMobsToSpawn(cat).unwrap()) {
					EntityType<?> t = w.value().type();
					list.add(BuiltInRegistries.ENTITY_TYPE.getKey(t) + " (peso " + w.weight() + ")");
					spawnBiomes.computeIfAbsent(t, k -> new ArrayList<>()).add(bid);
				}
				if (!list.isEmpty()) {
					mobs.add(cat.getName(), list);
				}
			}
			o.add("criaturas", mobs);
			rows.add(o);
		});
		return rows;
	}

	private static JsonElement holderSet(HolderSet<?> set) {
		var key = set.unwrapKey();
		if (key.isPresent()) {
			return new com.google.gson.JsonPrimitive("#" + key.get().location());
		}
		JsonArray a = new JsonArray();
		set.stream().forEach(h -> h.unwrapKey().ifPresent(k -> a.add(k.identifier().toString())));
		return a;
	}

	private static List<JsonObject> enchantments(RegistryAccess access) {
		List<JsonObject> rows = new ArrayList<>();
		access.lookupOrThrow(Registries.ENCHANTMENT).listElements().forEach(ref -> {
			var e = ref.value();
			JsonObject o = new JsonObject();
			o.addProperty("id", ref.key().identifier().toString());
			o.addProperty("nome", e.description().getString());
			o.addProperty("nivel_maximo", e.getMaxLevel());
			o.addProperty("peso", e.definition().weight());
			o.add("itens", holderSet(e.definition().supportedItems()));
			o.add("exclusivos", holderSet(e.exclusiveSet()));
			rows.add(o);
		});
		return rows;
	}

	private static List<JsonObject> potions() {
		List<JsonObject> rows = new ArrayList<>();
		for (var potion : BuiltInRegistries.POTION) {
			JsonObject o = new JsonObject();
			o.addProperty("id", BuiltInRegistries.POTION.getKey(potion).toString());
			JsonArray effs = new JsonArray();
			for (var inst : potion.getEffects()) {
				JsonObject x = new JsonObject();
				x.addProperty("efeito", inst.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
				x.addProperty("duracao_ticks", inst.getDuration());
				x.addProperty("nivel", inst.getAmplifier() + 1);
				effs.add(x);
			}
			o.add("efeitos", effs);
			rows.add(o);
		}
		return rows;
	}

	private static List<JsonObject> effects() {
		List<JsonObject> rows = new ArrayList<>();
		for (var effect : BuiltInRegistries.MOB_EFFECT) {
			JsonObject o = new JsonObject();
			o.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(effect).toString());
			o.addProperty("nome", effect.getDisplayName().getString());
			o.addProperty("benefico", effect.isBeneficial());
			o.addProperty("categoria", effect.getCategory().name());
			rows.add(o);
		}
		return rows;
	}

	/**
	 * Geração de um minério nesta versão (placed feature do tipo minério): blocos que ela coloca e a faixa de altura, lidos
	 * dos dados do jogo. As âncoras relativas ("acima do fundo", "abaixo do topo") são resolvidas com os limites de altura
	 * informados (os da Superfície, para os minérios dela); o JSON bruto fica em {@code height} e {@code feature}.
	 */
	public record OrePlacement(String id, List<String> blocks, @org.jspecify.annotations.Nullable Integer minY,
		@org.jspecify.annotations.Nullable Integer maxY, @org.jspecify.annotations.Nullable JsonElement height, JsonElement feature) {
		public boolean covers(int y) {
			return minY != null && maxY != null && y >= minY && y <= maxY;
		}
	}

	public static List<OrePlacement> orePlacements(RegistryAccess access, int worldMinY, int worldMaxY) {
		var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, access);
		List<OrePlacement> out = new ArrayList<>();
		access.lookupOrThrow(Registries.PLACED_FEATURE).listElements().forEach(ref -> {
			var placed = ref.value();
			JsonElement feature = net.minecraft.world.level.levelgen.feature.Feature.DIRECT_CODEC.encodeStart(ops, placed.feature().value()).result().orElse(null);
			if (feature == null || !feature.isJsonObject() || !feature.getAsJsonObject().has("type")
				|| !feature.getAsJsonObject().get("type").getAsString().contains("ore")) {
				return;
			}
			Set<String> blocks = new java.util.TreeSet<>();
			collectStateNames(feature, blocks);
			JsonElement height = null;
			JsonElement encoded = net.minecraft.world.level.levelgen.placement.PlacedFeature.DIRECT_CODEC.encodeStart(ops, placed).result().orElse(null);
			if (encoded != null && encoded.isJsonObject() && encoded.getAsJsonObject().get("placement") instanceof JsonArray mods) {
				for (JsonElement m : mods) {
					if (m.isJsonObject() && m.getAsJsonObject().has("type") && m.getAsJsonObject().get("type").getAsString().equals("minecraft:height_range")) {
						height = m.getAsJsonObject().get("height");
					}
				}
			}
			out.add(new OrePlacement(ref.key().identifier().toString(), List.copyOf(blocks), anchor(height, "min_inclusive", worldMinY, worldMaxY),
				anchor(height, "max_inclusive", worldMinY, worldMaxY), height, feature));
		});
		return out;
	}

	private static void collectStateNames(JsonElement e, Set<String> out) {
		if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (o.has("Name") && o.get("Name").isJsonPrimitive()) {
				out.add(o.get("Name").getAsString());
			}
			// No 26.3 o estado do bloco é gravado como texto ("minecraft:iron_ore" ou "minecraft:x[prop=valor]").
			if (o.has("state") && o.get("state").isJsonPrimitive()) {
				String st = o.get("state").getAsString();
				out.add(st.contains("[") ? st.substring(0, st.indexOf('[')) : st);
			}
			o.entrySet().forEach(x -> collectStateNames(x.getValue(), out));
		} else if (e.isJsonArray()) {
			e.getAsJsonArray().forEach(x -> collectStateNames(x, out));
		}
	}

	private static @org.jspecify.annotations.Nullable Integer anchor(@org.jspecify.annotations.Nullable JsonElement height, String field, int worldMinY,
		int worldMaxY) {
		if (height == null || !height.isJsonObject() || !(height.getAsJsonObject().get(field) instanceof JsonObject a)) {
			return null;
		}
		if (a.has("absolute")) {
			return a.get("absolute").getAsInt();
		}
		if (a.has("above_bottom")) {
			return worldMinY + a.get("above_bottom").getAsInt();
		}
		if (a.has("below_top")) {
			return worldMaxY - a.get("below_top").getAsInt();
		}
		return null;
	}

	private static List<JsonObject> ores(RegistryAccess access, int worldMinY, int worldMaxY) {
		List<JsonObject> rows = new ArrayList<>();
		for (OrePlacement p : orePlacements(access, worldMinY, worldMaxY)) {
			JsonObject o = new JsonObject();
			o.addProperty("id", p.id());
			JsonArray b = new JsonArray();
			p.blocks().forEach(b::add);
			o.add("blocos", b);
			o.addProperty("y_minimo", p.minY());
			o.addProperty("y_maximo", p.maxY());
			o.add("altura", p.height());
			o.add("feature", p.feature());
			rows.add(o);
		}
		return rows;
	}

	private static List<JsonObject> structures(RegistryAccess access) {
		List<JsonObject> rows = new ArrayList<>();
		access.lookupOrThrow(Registries.STRUCTURE).listElements().forEach(ref -> {
			var s = ref.value();
			JsonObject o = new JsonObject();
			o.addProperty("id", ref.key().identifier().toString());
			o.addProperty("tipo", String.valueOf(BuiltInRegistries.STRUCTURE_TYPE.getKey(s.type())));
			o.addProperty("etapa", s.step().name());
			o.add("biomas", holderSet(s.biomes()));
			rows.add(o);
		});
		return rows;
	}
}
