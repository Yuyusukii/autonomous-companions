package dev.autonomouscompanions.test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.autonomouscompanions.mind.knowledge.GameCatalog;
import dev.autonomouscompanions.mind.knowledge.Lookup;
import dev.autonomouscompanions.registry.ModEntities;
import dev.autonomouscompanions.registry.ModItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Catálogo gerado da versão carregada: confere as contagens contra os registros do próprio jogo, fatos conhecidos da
 * versão (ferramenta mínima, alimento, durabilidade, hostilidade), o conteúdo de outros mods (o próprio mod) e a consulta.
 */
public class CatalogGameTests {
	private static final String ARENA = "autonomouscompanions-test:arena";
	private static final Gson GSON = new Gson();

	private static Map<String, JsonObject> read(GameTestHelper h, Path file) {
		Map<String, JsonObject> out = new HashMap<>();
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				JsonObject o = GSON.fromJson(line, JsonObject.class);
				String key = o.has("id") ? o.get("id").getAsString() : o.get("fonte").getAsString();
				out.put(key, o);
			}
		} catch (IOException | RuntimeException e) {
			throw h.assertionException(net.minecraft.network.chat.Component.literal("arquivo do catálogo ilegível (" + file.getFileName() + "): " + e));
		}
		return out;
	}

	private static boolean has(JsonObject o, String array, String value) {
		return o.has(array) && o.getAsJsonArray(array).asList().stream().anyMatch(e -> e.getAsString().equals(value));
	}

	@GameTest(environment = "autonomouscompanions-test:ac_catalog", structure = ARENA, maxTicks = 1200)
	public void catalogMatchesTheLoadedVersion(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		CompletableFuture<GameCatalog.Result> future = GameCatalog.export(server, true);
		h.succeedWhen(() -> {
			// Espera a gravação (em outra thread) de forma determinística: no servidor de testes os ticks correm mais rápido que o
			// relógio, e esperar por um número de ticks virava corrida.
			GameCatalog.Result r = future.orTimeout(60, java.util.concurrent.TimeUnit.SECONDS).join();
			Path dir = r.folder();
			var access = server.registryAccess();
			h.assertValueEqual(r.counts().get("blocks"), BuiltInRegistries.BLOCK.size(), "blocos");
			h.assertValueEqual(r.counts().get("items"), BuiltInRegistries.ITEM.size(), "itens");
			h.assertValueEqual(r.counts().get("entities"), BuiltInRegistries.ENTITY_TYPE.size(), "criaturas");
			h.assertValueEqual(r.counts().get("recipes"), server.getRecipeManager().getRecipes().size(), "receitas");
			h.assertValueEqual(r.counts().get("enchantments"), access.lookupOrThrow(Registries.ENCHANTMENT).listElements().toList().size(), "encantamentos");
			h.assertValueEqual(r.counts().get("biomes"), access.lookupOrThrow(Registries.BIOME).listElements().toList().size(), "biomas");
			h.assertValueEqual(r.counts().get("structures"), access.lookupOrThrow(Registries.STRUCTURE).listElements().toList().size(), "estruturas");
			h.assertValueEqual(r.counts().get("potions"), BuiltInRegistries.POTION.size(), "poções");
			h.assertValueEqual(r.counts().get("effects"), BuiltInRegistries.MOB_EFFECT.size(), "efeitos");

			Map<String, JsonObject> blocks = read(h, dir.resolve("blocks.jsonl"));
			JsonObject iron = blocks.get("minecraft:iron_ore");
			h.assertValueEqual(iron.get("ferramenta").getAsString(), "picareta", "ferramenta do minério de ferro");
			h.assertValueEqual(iron.get("ferramenta_minima").getAsString(), "minecraft:stone_pickaxe", "ferramenta mínima do minério de ferro");
			h.assertTrue(has(iron, "drops", "minecraft:raw_iron"), "minério de ferro deveria dar ferro bruto");
			h.assertValueEqual(blocks.get("minecraft:diamond_ore").get("ferramenta_minima").getAsString(), "minecraft:iron_pickaxe", "mínima do diamante");
			h.assertValueEqual(blocks.get("minecraft:obsidian").get("ferramenta_minima").getAsString(), "minecraft:diamond_pickaxe", "mínima da obsidiana");
			JsonObject oak = blocks.get("minecraft:oak_log");
			h.assertTrue(oak.get("ferramenta").getAsString().equals("machado") && !oak.get("requer_ferramenta_correta").getAsBoolean(), "tronco de carvalho");
			h.assertTrue(oak.getAsJsonObject("propriedades").has("axis"), "propriedade axis do tronco");
			h.assertTrue(blocks.get("minecraft:bedrock").get("inquebravel").getAsBoolean(), "rocha-matriz deveria ser inquebrável");

			Map<String, JsonObject> items = read(h, dir.resolve("items.jsonl"));
			var ingot = items.get("minecraft:iron_ingot").getAsJsonObject("como_obter");
			h.assertTrue(ingot.getAsJsonArray("fornalha_de").asList().stream().anyMatch(a -> a.getAsJsonArray().asList().stream()
				.anyMatch(e -> e.getAsString().equals("minecraft:raw_iron"))), "barra de ferro deveria sair da fornalha com ferro bruto");
			h.assertValueEqual(items.get("minecraft:bread").getAsJsonObject("alimento").get("nutricao").getAsInt(), 5, "nutrição do pão");
			h.assertValueEqual(items.get("minecraft:diamond_sword").get("durabilidade").getAsInt(), 1561, "durabilidade da espada de diamante");
			String whistle = BuiltInRegistries.ITEM.getKey(ModItems.WHISTLE).toString();
			h.assertTrue(items.containsKey(whistle), "o catálogo não descobriu o item do mod (" + whistle + ")");

			Map<String, JsonObject> entities = read(h, dir.resolve("entities.jsonl"));
			JsonObject zombie = entities.get("minecraft:zombie");
			h.assertTrue(zombie.get("hostil").getAsBoolean() && zombie.get("categoria").getAsString().equals("monster"), "zumbi hostil");
			h.assertValueEqual(zombie.getAsJsonObject("atributos").get("vida").getAsDouble(), 20.0, "vida do zumbi");
			h.assertTrue(has(zombie, "drops", "minecraft:rotten_flesh"), "drops do zumbi: " + zombie.get("drops"));
			JsonObject cow = entities.get("minecraft:cow");
			h.assertTrue(!cow.get("hostil").getAsBoolean() && has(cow, "drops", "minecraft:beef") && !cow.getAsJsonArray("biomas").isEmpty(), "vaca: " + cow);
			h.assertTrue(entities.containsKey(BuiltInRegistries.ENTITY_TYPE.getKey(ModEntities.COMPANION).toString()), "a companheira (do mod) não está no catálogo");

			h.assertValueEqual(read(h, dir.resolve("enchantments.jsonl")).get("minecraft:sharpness").get("nivel_maximo").getAsInt(), 5, "afiação nível máximo");
			h.assertTrue(Files.exists(dir.resolve("manifest.json")) && Files.exists(dir.resolve("loot.jsonl")) && Files.exists(dir.resolve("recipes.jsonl")),
				"arquivos faltando");
			// Sem mudança de versão, mods ou dados, uma nova exportação (não forçada) é pulada.
			h.assertTrue(GameCatalog.export(server, false).join().skipped(), "a exportação sem mudanças deveria ser pulada");
		});
	}

	/**
	 * A tabela de alturas típicas dos minérios (escrita à mão no mod, usada para decidir onde cavar) confere com a geração de
	 * mundo desta versão: a altura preferida de cada minério da Superfície fica dentro de uma faixa onde o jogo o gera.
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_catalog", structure = ARENA, maxTicks = 100)
	public void oreHeightTableMatchesWorldGeneration(GameTestHelper h) {
		var placements = GameCatalog.orePlacements(h.getLevel().registryAccess(), h.getLevel().getMinY(), h.getLevel().getMaxY());
		h.assertTrue(!placements.isEmpty(), "nenhuma geração de minério encontrada nos dados do jogo");
		List<String> problems = new java.util.ArrayList<>();
		for (var ore : List.of(Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE,
			Blocks.EMERALD_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)) {
			String id = BuiltInRegistries.BLOCK.getKey(ore).toString();
			var info = dev.autonomouscompanions.world.ResourceKnowledge.of(ore.defaultBlockState());
			var ranges = placements.stream().filter(p -> p.blocks().contains(id) && p.minY() != null && p.maxY() != null).toList();
			if (ranges.isEmpty()) {
				problems.add(id + ": nenhuma geração com altura absoluta");
			} else if (ranges.stream().noneMatch(p -> p.covers(info.bestY()))) {
				problems.add(id + ": altura preferida " + info.bestY() + " fora de " + ranges.stream().map(p -> p.id() + "[" + p.minY() + ".." + p.maxY() + "]").toList());
			}
		}
		h.assertTrue(problems.isEmpty(), "tabela de alturas × geração do mundo: " + problems);
		h.succeed();
	}

	/** A consulta acha pelo nome (no idioma do servidor) ou pelo id, com plural, e explica com os dados reais. */
	@GameTest(environment = "autonomouscompanions-test:ac_catalog", structure = ARENA, maxTicks = 100)
	public void lookupAnswersFromRealData(GameTestHelper h) {
		String ingotName = Lookup.name(Items.IRON_INGOT);
		h.assertTrue(Lookup.findItem(ingotName) == Items.IRON_INGOT, "nome: " + ingotName + " → " + Lookup.findItem(ingotName));
		h.assertTrue(Lookup.findItem("minecraft:iron_ingot") == Items.IRON_INGOT, "id completo");
		h.assertTrue(Lookup.findItem(ingotName + "s") == Items.IRON_INGOT, "plural");
		h.assertTrue(Lookup.findBlock(Blocks.IRON_ORE.getName().getString()) == Blocks.IRON_ORE, "bloco pelo nome");
		List<String> ingot = Lookup.explainItem(Items.IRON_INGOT);
		String raw = Lookup.name(Items.RAW_IRON);
		h.assertTrue(ingot.stream().anyMatch(l -> l.startsWith("Fornalha") && l.contains(raw)), "explicação da barra de ferro: " + ingot);
		List<String> diamond = Lookup.explainBlock(Blocks.DIAMOND_ORE);
		h.assertTrue(diamond.stream().anyMatch(l -> l.contains(Lookup.name(Items.IRON_PICKAXE))), "ficha do minério de diamante: " + diamond);
		h.succeed();
	}
}
