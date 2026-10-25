package dev.autonomouscompanions.mind.knowledge;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.mind.skills.Skill;
import dev.autonomouscompanions.mind.skills.SkillLibrary;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Conhecimento prático em Markdown ({@code data/<namespace>/companion_knowledge/*.md}; datapacks podem acrescentar), dividido
 * em seções ("## Título"). Para responder, busca só a seção relevante (palavras do título valem mais que as do texto) e a
 * junta com o conhecimento confirmado da versão carregada: como obter o item citado, a ferramenta mínima, a faixa de altura
 * em que o minério é gerado e a habilidade que executa aquilo. O texto em Markdown é estratégia; os números vêm dos dados.
 */
public final class KnowledgeBase {
	public static final String FOLDER = "companion_knowledge";
	private static final Set<String> STOP = Set.copyOf(Arrays.asList("o", "a", "os", "as", "um", "uma", "de", "do", "da", "dos", "das", "que", "qual", "quais", "e", "eu", "pra",
		"para", "voce", "como", "onde", "quando", "acho", "acha", "achar", "encontro", "encontrar", "faco", "faz", "fazer", "consigo", "conseguir", "obter",
		"obtenho", "pegar", "pego", "minero", "minerar", "sobre", "me", "fala", "explica", "explique", "sabe", "saber", "no", "na", "nos", "nas", "em", "com",
		"por", "ser", "e", "tem", "ter", "isso", "esse", "essa", "is", "the", "how", "where", "what", "to", "do", "find", "get", "make"));

	public record Section(String file, String title, String body) {
	}

	private static final List<Section> SECTIONS = new ArrayList<>();

	private KnowledgeBase() {
	}

	public static synchronized void load(MinecraftServer server) {
		SECTIONS.clear();
		Map<Identifier, Resource> found = server.getResourceManager().listResources(FOLDER, id -> id.getPath().endsWith(".md"));
		List<Identifier> ids = new ArrayList<>(found.keySet());
		ids.sort(Identifier::compareTo);
		for (Identifier file : ids) {
			String name = file.getPath().substring(FOLDER.length() + 1, file.getPath().length() - ".md".length());
			try (BufferedReader reader = found.get(file).openAsReader()) {
				String title = null;
				StringBuilder body = new StringBuilder();
				for (String line : reader.lines().toList()) {
					if (line.startsWith("## ")) {
						if (title != null) {
							SECTIONS.add(new Section(name, title, body.toString().trim()));
						}
						title = line.substring(3).trim();
						body.setLength(0);
					} else if (title != null && !line.startsWith("# ")) {
						body.append(line).append('\n');
					}
				}
				if (title != null) {
					SECTIONS.add(new Section(name, title, body.toString().trim()));
				}
			} catch (Exception e) {
				AutonomousCompanions.LOGGER.warn("Conhecimento {} ilegível: {}", file, e.toString());
			}
		}
		AutonomousCompanions.LOGGER.info("Conhecimento carregado: {} seções de {} arquivos", SECTIONS.size(), ids.size());
	}

	public static synchronized List<Section> all() {
		return List.copyOf(SECTIONS);
	}

	private static Set<String> words(String text) {
		Set<String> out = new LinkedHashSet<>();
		for (String w : Lookup.normalize(text).split(" ")) {
			if (w.length() > 1 && !STOP.contains(w)) {
				out.add(w);
			}
		}
		return out;
	}

	/** A pergunta sem as palavras de pergunta ("como", "onde acho"...): o que sobra é o assunto. */
	public static String subject(String question) {
		return String.join(" ", words(question));
	}

	/** Seção mais relevante (título vale 3, nome do arquivo 2, texto 1 por palavra); nula se nenhuma palavra combinar. */
	public static synchronized @Nullable Section search(String question) {
		Set<String> q = words(question);
		if (q.isEmpty()) {
			return null;
		}
		Section best = null;
		int bestScore = 0;
		for (Section s : SECTIONS) {
			Set<String> title = words(s.title());
			Set<String> body = words(s.body());
			Set<String> file = words(s.file());
			int score = 0;
			for (String w : q) {
				score += (title.contains(w) ? 3 : 0) + (file.contains(w) ? 2 : 0) + (body.contains(w) ? 1 : 0);
			}
			if (score > bestScore) {
				best = s;
				bestScore = score;
			}
		}
		return best;
	}

	/** Resposta completa: conhecimento + fatos da versão + geração do minério + habilidade relacionada. */
	public static List<String> answer(MinecraftServer server, String question) {
		List<String> out = new ArrayList<>();
		Section s = search(question);
		if (s != null) {
			String body = s.body().replace('\n', ' ').replaceAll("\\s+", " ").trim();
			out.add("[" + s.file() + " › " + s.title() + "] " + (body.length() > 700 ? body.substring(0, 700) + "…" : body));
		}
		String subject = subject(question);
		Item item = subject.isEmpty() ? null : Lookup.findItem(subject);
		if (item != null && Lookup.matchScore(subject, item) >= 300) {
			out.addAll(Lookup.explainItem(item));
			Block block = Block.byItem(item);
			if (block != Blocks.AIR) {
				out.addAll(oreLines(server, block));
			} else {
				// Ex.: "diamante" → o item; a geração é do minério que o solta.
				var sources = dev.autonomouscompanions.mining.DropIndex.blockSources(item).stream().map(src -> src.state().getBlock()).distinct().limit(2).toList();
				for (Block b : sources) {
					out.addAll(oreLines(server, b));
				}
			}
		}
		Skill skill = SkillLibrary.find(question);
		if (skill != null && skill.valid()) {
			out.add("Habilidade relacionada: " + skill.name() + (skill.orderable() ? " (peça com /companheiras habilidade <nome> " + skill.id() + ")" : " (reativa)")
				+ ".");
		}
		if (out.isEmpty()) {
			out.add("Não sei sobre isso ainda: não achei nada no conhecimento nem no catálogo desta versão.");
		}
		return out;
	}

	/** Faixas de altura em que o bloco é gerado nesta versão (lidas da geração de mundo). */
	private static List<String> oreLines(MinecraftServer server, Block block) {
		String id = BuiltInRegistries.BLOCK.getKey(block).toString();
		var level = server.overworld();
		var placements = GameCatalog.orePlacements(server.registryAccess(), level.getMinY(), level.getMaxY()).stream()
			.filter(p -> p.blocks().contains(id) && p.minY() != null && p.maxY() != null).toList();
		if (placements.isEmpty()) {
			return List.of();
		}
		List<String> parts = new ArrayList<>();
		for (var p : placements) {
			String shape = p.height() != null && p.height().isJsonObject() && p.height().getAsJsonObject().has("type")
				? p.height().getAsJsonObject().get("type").getAsString().replace("minecraft:", "") : "?";
			parts.add(p.id().replace("minecraft:", "") + " de Y=" + Math.max(p.minY(), level.getMinY()) + " a Y=" + Math.min(p.maxY(), level.getMaxY()) + " ("
				+ (shape.equals("trapezoid") ? "mais comum no meio da faixa" : shape.equals("uniform") ? "distribuição igual" : shape) + ")");
		}
		return List.of("Geração nesta versão (" + block.getName().getString() + "): " + String.join("; ", parts) + ".");
	}

	/** Ids citados no texto ("minecraft:xxx"), para validar o conhecimento contra a versão. */
	public static List<String> mentionedIds() {
		List<String> out = new ArrayList<>();
		var m = java.util.regex.Pattern.compile("`([a-z0-9_.-]+:[a-z0-9_/.-]+)`");
		for (Section s : all()) {
			var matcher = m.matcher(s.body());
			while (matcher.find()) {
				out.add(matcher.group(1));
			}
		}
		return out;
	}

	public static boolean exists(String id) {
		Identifier i = Identifier.tryParse(id);
		return i != null && (BuiltInRegistries.BLOCK.containsKey(i) || BuiltInRegistries.ITEM.containsKey(i) || BuiltInRegistries.ENTITY_TYPE.containsKey(i));
	}

	static List<String> split(String s) {
		return Arrays.asList(s.split(" "));
	}
}
