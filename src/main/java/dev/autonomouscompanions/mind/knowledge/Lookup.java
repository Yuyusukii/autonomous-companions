package dev.autonomouscompanions.mind.knowledge;

import dev.autonomouscompanions.crafting.RecipeIndex;
import dev.autonomouscompanions.mining.DropIndex;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Consulta ao conhecimento confirmado da versão carregada: acha itens e blocos pelo nome (no idioma do jogo) ou pelo id,
 * tolerando acentos, maiúsculas e plural simples, e explica como obter cada coisa a partir dos índices reais (receitas,
 * fornalha, blocos com a ferramenta mínima, criaturas). Só busca o que é relevante para a pergunta: nada de colocar o
 * catálogo inteiro na memória de trabalho.
 */
public final class Lookup {
	private Lookup() {
	}

	/** Minúsculas, sem acento e sem pontuação; plural simples ("barras" → "barra") por palavra. */
	public static String normalize(String text) {
		String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('_', ' ');
		s = s.replaceAll("[^a-z0-9: ]", " ").trim().replaceAll("\\s+", " ");
		return String.join(" ", Arrays.stream(s.split(" ")).map(Lookup::stem).toList());
	}

	private static String stem(String w) {
		if (w.length() > 4 && w.endsWith("oes")) {
			return w.substring(0, w.length() - 3) + "ao";
		}
		if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
			return w.substring(0, w.length() - 1);
		}
		return w;
	}

	private static int score(String query, String id, String name) {
		String q = normalize(query);
		if (q.isEmpty()) {
			return 0;
		}
		String full = id.toLowerCase(Locale.ROOT);
		String path = full.contains(":") ? full.substring(full.indexOf(':') + 1) : full;
		if (q.equals(normalize(full)) || q.equals(normalize(path))) {
			return 1000;
		}
		String n = normalize(name);
		if (q.equals(n)) {
			return 900;
		}
		Set<String> qWords = new LinkedHashSet<>(Arrays.asList(q.split(" ")));
		Set<String> nWords = new LinkedHashSet<>(Arrays.asList(n.split(" ")));
		Set<String> pWords = new LinkedHashSet<>(Arrays.asList(normalize(path).split(" ")));
		if (nWords.containsAll(qWords) || pWords.containsAll(qWords)) {
			// Todas as palavras da pergunta estão no nome: quanto menos sobra, melhor ("ferro" → "barra de ferro" perde de "ferro").
			return 500 - 10 * (Math.min(nWords.size(), pWords.size()) - qWords.size());
		}
		if (q.contains(n) && n.length() >= 3) {
			return 300 + n.length();
		}
		return 0;
	}

	/** Item mais provável para o texto (id, caminho do id ou nome); nulo se nada combinar. */
	public static @Nullable Item findItem(String text) {
		Item best = null;
		int bestScore = 0;
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR) {
				continue;
			}
			int s = score(text, BuiltInRegistries.ITEM.getKey(item).toString(), new ItemStack(item).getHoverName().getString());
			if (s > bestScore || s == bestScore && s > 0 && best != null && nameLength(item) < nameLength(best)) {
				best = item;
				bestScore = s;
			}
		}
		return best;
	}

	/** Bloco mais provável para o texto; nulo se nada combinar. */
	public static @Nullable Block findBlock(String text) {
		Block best = null;
		int bestScore = 0;
		for (Block block : BuiltInRegistries.BLOCK) {
			if (block == Blocks.AIR) {
				continue;
			}
			int s = score(text, BuiltInRegistries.BLOCK.getKey(block).toString(), block.getName().getString());
			if (s > bestScore || s == bestScore && s > 0 && best != null && block.getName().getString().length() < best.getName().getString().length()) {
				best = block;
				bestScore = s;
			}
		}
		return best;
	}

	private static int nameLength(Item item) {
		return new ItemStack(item).getHoverName().getString().length();
	}

	/** Quão bem o texto descreve o item: 0 nada; 1000 id exato; 900 nome exato; ~500 todas as palavras; 300+ o texto contém o nome. */
	public static int matchScore(String text, Item item) {
		return score(text, BuiltInRegistries.ITEM.getKey(item).toString(), name(item));
	}

	public static String name(Item item) {
		return new ItemStack(item).getHoverName().getString();
	}

	private static String alternatives(Ingredient ing) {
		List<String> names = RecipeIndex.items(ing).stream().map(Lookup::name).sorted().toList();
		if (names.isEmpty()) {
			return "?";
		}
		return names.size() <= 3 ? String.join(" ou ", names) : names.get(0) + " (ou outro de " + names.size() + " tipos)";
	}

	/** Como obter um item, do conhecimento confirmado desta versão (receitas, fornalha, blocos, criaturas). */
	public static List<String> explainItem(Item item) {
		List<String> out = new ArrayList<>();
		out.add(name(item) + " (" + BuiltInRegistries.ITEM.getKey(item) + "): pilha de até " + item.getDefaultMaxStackSize() + ".");
		var crafting = RecipeIndex.crafting(item);
		for (int i = 0; i < Math.min(2, crafting.size()); i++) {
			var r = crafting.get(i);
			List<String> parts = new ArrayList<>();
			for (var g : r.grouped()) {
				parts.add(g.count() + "× " + alternatives(g.ingredient()));
			}
			out.add("Fabricar" + (r.needsTable() ? " (na bancada)" : " (no inventário)") + ": " + String.join(" + ", parts) + " → " + r.outputCount() + ".");
		}
		var smelting = RecipeIndex.smelting(item);
		if (!smelting.isEmpty()) {
			// Todas as entradas distintas (ex.: barra de ferro sai de ferro bruto e dos minérios de ferro).
			List<String> inputs = smelting.stream().flatMap(c -> RecipeIndex.items(c.input()).stream()).map(Lookup::name).distinct().sorted().limit(6).toList();
			out.add("Fornalha: " + String.join(", ", inputs) + " (" + smelting.getFirst().cookTime() / 20 + " s cada).");
		}
		var blocks = DropIndex.blockSources(item).stream().filter(s -> !s.silkOnly()).map(s -> s.state().getBlock()).distinct().limit(4).toList();
		if (!blocks.isEmpty()) {
			List<String> parts = new ArrayList<>();
			for (Block b : blocks) {
				Item min = GameCatalog.minimumTool(b.defaultBlockState());
				parts.add(b.getName().getString() + (min == null ? "" : " (ferramenta mínima: " + name(min) + ")"));
			}
			out.add("Minerar/quebrar: " + String.join("; ", parts) + ".");
		}
		var animals = DropIndex.entitySources(item);
		if (!animals.isEmpty()) {
			out.add("Caçar: " + String.join(", ", animals.stream().limit(4).map(s -> s.type().getDescription().getString()
				+ String.format(Locale.ROOT, " (média %.1f)", s.averageCount())).toList()) + ".");
		}
		if (out.size() == 1) {
			out.add("Nesta versão não há receita, bloco nem animal que dê este item: pode vir de baús, comércio, estruturas ou monstros.");
		}
		return out;
	}

	/** Ficha de um bloco: dureza, ferramenta adequada e mínima, drops. */
	public static List<String> explainBlock(Block block) {
		BlockState s = block.defaultBlockState();
		List<String> out = new ArrayList<>();
		String kind = GameCatalog.toolKind(s);
		Item min = GameCatalog.minimumTool(s);
		out.add(String.format(Locale.ROOT, "%s (%s): dureza %.1f, resistência a explosão %.1f.", block.getName().getString(), BuiltInRegistries.BLOCK.getKey(block),
			block.defaultDestroyTime(), block.getExplosionResistance()));
		out.add("Ferramenta: " + (kind == null ? "qualquer uma (ou a mão)" : kind) + (min == null ? "" : "; mínima para coletar: " + name(min)) + ".");
		var a = DropIndex.analysis(s);
		if (a != null) {
			if (a.unbreakable()) {
				out.add("Inquebrável.");
			} else {
				out.add("Drops: " + (a.normalDrops().isEmpty() ? "nada" : String.join(", ", a.normalDrops().stream().map(Lookup::name).sorted().toList()))
					+ (a.silkDrops().equals(a.normalDrops()) || a.silkDrops().isEmpty() ? "" : "; com Toque Suave: "
						+ String.join(", ", a.silkDrops().stream().map(Lookup::name).sorted().toList())) + ".");
			}
		}
		return out;
	}

	/** Responde "o que é / como obter X": bloco e item, quando os dois existem. */
	public static List<String> explain(String text) {
		Item item = findItem(text);
		Block block = findBlock(text);
		List<String> out = new ArrayList<>();
		if (item != null) {
			out.addAll(explainItem(item));
		}
		if (block != null && (item == null || Block.byItem(item) != block || block.defaultBlockState().requiresCorrectToolForDrops())) {
			out.addAll(explainBlock(block));
		}
		if (out.isEmpty()) {
			out.add("Não encontrei \"" + text + "\" no catálogo desta versão.");
		}
		return out;
	}
}
