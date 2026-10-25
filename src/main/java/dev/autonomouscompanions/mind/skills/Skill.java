package dev.autonomouscompanions.mind.skills;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.autonomouscompanions.task.exec.StepResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import dev.autonomouscompanions.task.exec.ItemTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Habilidade executável, descrita em dados ({@code data/<namespace>/companion_skills/<id>.json}; datapacks podem
 * acrescentar outras): objetivo, pré-requisitos, procedimento, recuperação de falhas e condição de sucesso. Cada ação do
 * procedimento corresponde a uma etapa real do mod (que verifica e executa no mundo); o arquivo não executa nada por
 * conta própria. Habilidades com problemas (ação desconhecida, item inexistente nesta versão...) ficam marcadas como
 * inválidas e não podem ser pedidas.
 */
public record Skill(String id, String name, String goal, List<String> keywords, int defaultQuantity, List<Requirement> requirements, List<Action> procedure,
	List<Recovery> recoveries, @Nullable Success success, boolean reactive, List<String> problems) {

	/** Pré-requisito verificado antes de cada ação de obtenção. */
	public record Requirement(String type, @Nullable Identifier block, int minimum, String description) {
	}

	/**
	 * Ação do procedimento. {@code item} é um id ("minecraft:raw_iron") ou um grupo do jogo ("#minecraft:logs": qualquer
	 * item da tag); {@code quantity} pode ser um número ou "$quantidade".
	 */
	public record Action(String type, @Nullable String item, String quantity, boolean optional, String description) {
	}

	/** O que fazer quando uma etapa falha com um certo motivo. */
	public record Recovery(String failure, String action, String description) {
	}

	public record Success(String item, String minimum, String description) {
	}

	public static final Set<String> REQUIREMENTS = Set.of("ferramenta_para", "espaco_livre", "seguranca", "vida_minima");
	public static final Set<String> ACTIONS = Set.of("avaliar", "obter", "fabricar", "fundir", "verificar", "ir_para_base");
	public static final Set<String> RECOVERY_ACTIONS = Set.of("tentar_de_novo", "ampliar_busca", "liberar_espaco", "aguardar_seguranca", "desistir");
	/** Motivos de falha das etapas do mod, com o nome usado nos arquivos. */
	public static final Map<String, StepResult.FailureCode> FAILURES = Map.of("INVENTARIO_CHEIO", StepResult.FailureCode.INVENTORY_FULL,
		"NAO_ENCONTRADO", StepResult.FailureCode.NOT_FOUND, "SEM_ROTA", StepResult.FailureCode.NO_ROUTE, "PERIGO", StepResult.FailureCode.DANGER,
		"IMPOSSIVEL", StepResult.FailureCode.IMPOSSIBLE, "ITENS_SUMIRAM", StepResult.FailureCode.ITEMS_MISSING);

	public boolean valid() {
		return problems.isEmpty();
	}

	/** Pode ser pedida como ordem (válida, não reativa e com condição de sucesso sobre um item). */
	public boolean orderable() {
		return valid() && !reactive && success != null;
	}

	/** Alvo da condição de sucesso (item ou grupo); nulo se não houver. */
	public @Nullable ItemTarget successTarget() {
		return success == null ? null : target(success.item());
	}

	/** Item ou grupo ("#tag") como alvo de obtenção; nulo se não existir nesta versão (ou o grupo estiver vazio). */
	public static @Nullable ItemTarget target(@Nullable String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		if (raw.startsWith("#")) {
			Identifier tag = Identifier.tryParse(raw.substring(1));
			if (tag == null) {
				return null;
			}
			java.util.Set<Item> items = new java.util.LinkedHashSet<>();
			BuiltInRegistries.ITEM.getTagOrEmpty(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, tag)).forEach(h -> items.add(h.value()));
			return items.isEmpty() ? null : ItemTarget.anyOf(items);
		}
		Identifier id = Identifier.tryParse(raw);
		return id == null || !BuiltInRegistries.ITEM.containsKey(id) ? null : ItemTarget.of(BuiltInRegistries.ITEM.getValue(id));
	}

	/** Quantidade de uma ação: número fixo ou "$quantidade" (a pedida). */
	public static int resolve(String quantity, int requested) {
		if (quantity == null || quantity.isBlank() || quantity.equals("$quantidade")) {
			return requested;
		}
		try {
			return Math.max(1, Integer.parseInt(quantity.trim()));
		} catch (NumberFormatException e) {
			return requested;
		}
	}

	public @Nullable Recovery recoveryFor(StepResult.@Nullable FailureCode code) {
		for (Recovery r : recoveries) {
			if (FAILURES.get(r.failure()) == code) {
				return r;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ leitura e validação

	private static String str(JsonObject o, String key, String def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
	}

	/** Item ou grupo ("#tag") de um campo, validado contra esta versão. */
	private static @Nullable String itemOrTag(JsonObject o, String key, String where, List<String> problems) {
		if (!o.has(key)) {
			return null;
		}
		String raw = str(o, key, "");
		if (target(raw) == null) {
			problems.add(where + ": " + (raw.startsWith("#") ? "grupo " + raw + " vazio ou inexistente" : "item " + raw + " não existe") + " nesta versão");
		}
		return raw;
	}

	private static @Nullable Identifier ident(JsonObject o, String key, String where, List<String> problems, boolean block) {
		if (!o.has(key)) {
			return null;
		}
		Identifier id = Identifier.tryParse(str(o, key, ""));
		if (id == null) {
			problems.add(where + ": id inválido \"" + str(o, key, "") + "\"");
			return null;
		}
		boolean exists = block ? BuiltInRegistries.BLOCK.containsKey(id) : BuiltInRegistries.ITEM.containsKey(id);
		if (!exists) {
			problems.add(where + ": " + (block ? "bloco" : "item") + " " + id + " não existe nesta versão");
		}
		return id;
	}

	private static JsonArray array(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	public static Skill parse(String id, JsonObject json) {
		List<String> problems = new ArrayList<>();
		String name = str(json, "nome", id);
		List<String> keywords = new ArrayList<>();
		for (JsonElement e : array(json, "palavras_chave")) {
			keywords.add(e.getAsString());
		}
		int defaultQuantity = json.has("quantidade_padrao") ? Math.max(1, json.get("quantidade_padrao").getAsInt()) : 1;
		List<Requirement> requirements = new ArrayList<>();
		for (JsonElement e : array(json, "pre_requisitos")) {
			JsonObject o = e.getAsJsonObject();
			String type = str(o, "tipo", "");
			if (!REQUIREMENTS.contains(type)) {
				problems.add("pré-requisito desconhecido \"" + type + "\"");
			}
			requirements.add(new Requirement(type, ident(o, "bloco", "pré-requisito " + type, problems, true), o.has("minimo") ? o.get("minimo").getAsInt() : 0,
				str(o, "descricao", type)));
		}
		List<Action> procedure = new ArrayList<>();
		for (JsonElement e : array(json, "procedimento")) {
			JsonObject o = e.getAsJsonObject();
			String type = str(o, "acao", "");
			if (!ACTIONS.contains(type)) {
				problems.add("ação desconhecida \"" + type + "\"");
			}
			String item = itemOrTag(o, "item", "ação " + type, problems);
			if (Set.of("obter", "fabricar", "fundir", "verificar").contains(type) && item == null) {
				problems.add("ação " + type + " sem item");
			}
			procedure.add(new Action(type, item, str(o, "quantidade", "$quantidade"), o.has("opcional") && o.get("opcional").getAsBoolean(),
				str(o, "descricao", type)));
		}
		List<Recovery> recoveries = new ArrayList<>();
		for (JsonElement e : array(json, "recuperacao")) {
			JsonObject o = e.getAsJsonObject();
			String failure = str(o, "falha", "");
			String action = str(o, "acao", "");
			if (!FAILURES.containsKey(failure)) {
				problems.add("motivo de falha desconhecido \"" + failure + "\"");
			}
			if (!RECOVERY_ACTIONS.contains(action)) {
				problems.add("recuperação desconhecida \"" + action + "\"");
			}
			recoveries.add(new Recovery(failure, action, str(o, "descricao", action)));
		}
		Success success = null;
		if (json.has("sucesso") && json.get("sucesso").isJsonObject()) {
			JsonObject o = json.getAsJsonObject("sucesso");
			String item = itemOrTag(o, "item", "sucesso", problems);
			if (item != null) {
				success = new Success(item, str(o, "minimo", "$quantidade"), str(o, "descricao", ""));
			}
		}
		boolean reactive = json.has("reativa") && json.get("reativa").getAsBoolean();
		if (!reactive && procedure.isEmpty()) {
			problems.add("procedimento vazio");
		}
		return new Skill(id, name, str(json, "objetivo", ""), List.copyOf(keywords), defaultQuantity, List.copyOf(requirements), List.copyOf(procedure),
			List.copyOf(recoveries), success, reactive, List.copyOf(problems));
	}

	private static String itemName(@Nullable String raw) {
		if (raw == null) {
			return "";
		}
		if (raw.startsWith("#")) {
			ItemTarget t = target(raw);
			return t == null ? raw : "qualquer de " + raw + " (ex.: " + t.describe().getString() + ")";
		}
		Identifier id = Identifier.tryParse(raw);
		return id == null || !BuiltInRegistries.ITEM.containsKey(id) ? raw : new ItemStack(BuiltInRegistries.ITEM.getValue(id)).getHoverName().getString();
	}

	/** A habilidade por escrito, no formato de "ficha" (objetivo, pré-requisitos, procedimento, recuperação, sucesso). */
	public List<String> describe() {
		List<String> out = new ArrayList<>();
		out.add("HABILIDADE: " + name.toUpperCase(java.util.Locale.ROOT) + (valid() ? "" : " (inválida)"));
		if (!goal.isEmpty()) {
			out.add("Objetivo: " + goal);
		}
		if (reactive) {
			out.add("(Reativa: o mod a executa sozinho quando a situação acontece; não é pedida como ordem.)");
		}
		if (!requirements.isEmpty()) {
			out.add("Pré-requisitos:");
			requirements.forEach(r -> out.add("- " + r.description()));
		}
		if (!procedure.isEmpty()) {
			out.add("Procedimento:");
			for (int i = 0; i < procedure.size(); i++) {
				Action a = procedure.get(i);
				out.add((i + 1) + ". " + a.description() + (a.item() == null ? "" : " [" + itemName(a.item()) + "]") + (a.optional() ? " (opcional)" : ""));
			}
		}
		if (!recoveries.isEmpty()) {
			out.add("Recuperação de erros:");
			recoveries.forEach(r -> out.add("- " + r.description()));
		}
		if (success != null) {
			out.add("Condição de sucesso: " + (success.description().isEmpty() ? itemName(success.item()) + " no inventário" : success.description()));
		}
		problems.forEach(p -> out.add("Problema: " + p));
		return out;
	}

	/** Bloco de um pré-requisito "ferramenta_para" (nulo nos outros). */
	public static @Nullable Block block(Requirement r) {
		return r.block() == null ? null : BuiltInRegistries.BLOCK.getValue(r.block());
	}
}
