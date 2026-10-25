package dev.autonomouscompanions.mind.skills;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.mind.knowledge.Lookup;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * Biblioteca de habilidades: carregada dos dados do servidor ({@code data/*}/{@code companion_skills/*.json}) ao abrir o
 * mundo e ao recarregar datapacks — as do mod e as que um datapack acrescentar. Valida cada uma contra os registros desta
 * versão (itens, blocos, ações conhecidas) e guarda os problemas encontrados.
 */
public final class SkillLibrary {
	public static final String FOLDER = "companion_skills";
	private static final Gson GSON = new Gson();
	private static final Map<String, Skill> SKILLS = new LinkedHashMap<>();

	private SkillLibrary() {
	}

	public static synchronized void load(MinecraftServer server) {
		SKILLS.clear();
		Map<Identifier, Resource> found = server.getResourceManager().listResources(FOLDER, id -> id.getPath().endsWith(".json"));
		List<Identifier> ids = new ArrayList<>(found.keySet());
		ids.sort(Identifier::compareTo);
		for (Identifier file : ids) {
			String name = file.getPath().substring(FOLDER.length() + 1, file.getPath().length() - ".json".length());
			String id = SKILLS.containsKey(name) ? file.getNamespace() + ":" + name : name;
			try (Reader reader = found.get(file).openAsReader()) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				Skill skill = Skill.parse(id, json);
				SKILLS.put(id, skill);
				if (!skill.valid()) {
					AutonomousCompanions.LOGGER.warn("Habilidade {} inválida: {}", id, skill.problems());
				}
			} catch (Exception e) {
				AutonomousCompanions.LOGGER.warn("Habilidade {} ilegível: {}", file, e.toString());
				SKILLS.put(id, new Skill(id, name, "", List.of(), 1, List.of(), List.of(), List.of(), null, false, List.of("arquivo ilegível: " + e)));
			}
		}
		AutonomousCompanions.LOGGER.info("Habilidades carregadas: {} ({} válidas)", SKILLS.size(), SKILLS.values().stream().filter(Skill::valid).count());
	}

	public static synchronized @Nullable Skill get(String id) {
		return SKILLS.get(id);
	}

	public static synchronized List<Skill> all() {
		return List.copyOf(SKILLS.values());
	}

	/** Habilidade que melhor responde a um texto (id, nome ou palavras-chave); nula se nenhuma combinar. */
	public static synchronized @Nullable Skill find(String text) {
		String q = Lookup.normalize(text);
		if (q.isEmpty()) {
			return null;
		}
		Skill best = null;
		int bestScore = 0;
		for (Skill s : SKILLS.values()) {
			int score = 0;
			if (q.equals(Lookup.normalize(s.id())) || q.equals(Lookup.normalize(s.name()))) {
				score = 1000;
			}
			List<String> qWords = Arrays.asList(q.split(" "));
			for (String k : s.keywords()) {
				String nk = Lookup.normalize(k);
				if (q.equals(nk)) {
					score = Math.max(score, 900);
				} else if (q.contains(nk)) {
					score = Math.max(score, 400 + nk.length());
				} else if (Arrays.asList(nk.split(" ")).containsAll(qWords)) {
					score = Math.max(score, 300 - nk.length());
				}
			}
			if (score > bestScore) {
				best = s;
				bestScore = score;
			}
		}
		return best;
	}
}
