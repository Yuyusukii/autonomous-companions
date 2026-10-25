package dev.autonomouscompanions.mind.talk;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.mind.knowledge.KnowledgeBase;
import dev.autonomouscompanions.mind.knowledge.Lookup;
import dev.autonomouscompanions.mind.memory.MemoryReport;
import dev.autonomouscompanions.mind.memory.WorldMemory;
import dev.autonomouscompanions.mind.perception.Perception;
import dev.autonomouscompanions.mind.skills.Skill;
import dev.autonomouscompanions.mind.skills.SkillLibrary;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Conversa pelo chat, entendida localmente (sem serviço de IA externo): a dona escreve o nome de uma companheira (ou
 * "todas") seguido do pedido ou da pergunta — "Makima, traga 10 barras de ferro", "Power, minere carvão", "Raze, o que
 * você vê?", "Makima, onde tem diamante?", "como faço uma fornalha?", "aqui é a base", "pare", "venha".
 * <p>
 * A interpretação ({@link #parse}) só classifica a frase; quem age é o código do mod, com as mesmas regras dos comandos e
 * da tela (pedidos recusados explicam o motivo). Itens são achados pelo nome no idioma do jogo, e as respostas usam a
 * percepção, a memória, o conhecimento e o catálogo reais.
 */
public final class Talk {
	public enum Kind {
		STATUS,
		PERCEPTION,
		MEMORY,
		WHERE,
		HOW,
		BASE,
		STOP,
		FOLLOW,
		REST,
		SKILL,
		OBTAIN,
		CRAFT,
		HELP,
		UNKNOWN
	}

	/** Frase classificada: tipo, assunto (o resto da frase) e quantidade (0 = não disse). */
	public record Intent(Kind kind, String subject, int quantity) {
	}

	private static final Map<String, Integer> NUMBERS = Map.ofEntries(Map.entry("um", 1), Map.entry("uma", 1), Map.entry("dois", 2), Map.entry("duas", 2),
		Map.entry("tres", 3), Map.entry("quatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("sete", 7), Map.entry("oito", 8),
		Map.entry("nove", 9), Map.entry("dez", 10), Map.entry("onze", 11), Map.entry("doze", 12), Map.entry("quinze", 15), Map.entry("vinte", 20),
		Map.entry("trinta", 30), Map.entry("quarenta", 40), Map.entry("cinquenta", 50));
	private static final Pattern OBTAIN = Pattern.compile("^(traga|traz|traze|trazer|pegue|pega|pegar|busque|busca|buscar|consiga|consegue|arrume|arranje|junte|"
		+ "junta|colete|coleta|me de|me da|quero|preciso de|preciso)\\b\\s*(.*)$");
	private static final Pattern CRAFT = Pattern.compile("^(faca|faz|fazer|fabrique|fabrica|fabricar|crie|cria|construa)\\b\\s*(.*)$");
	private static final Pattern SKILL_VERB = Pattern.compile("^(minere|minera|minerar|corte|corta|cortar|cave|cava|use a habilidade|habilidade|execute|"
		+ "faca a habilidade)\\b\\s*(.*)$");

	private Talk() {
	}

	/** Normaliza (minúsculas, sem acento nem pontuação), sem o plural simples de {@link Lookup#normalize}. */
	static String clean(String text) {
		String s = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
		return s.replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
	}

	/** Quantidade no começo do texto ("10", "dez", "uma pilha", "meia pilha") e o resto. */
	static int[] quantity(String s, StringBuilder rest) {
		String t = s;
		int q = 0;
		Matcher m = Pattern.compile("^(\\d{1,4})\\s*(.*)$").matcher(t);
		if (m.matches()) {
			q = Integer.parseInt(m.group(1));
			t = m.group(2);
		} else if (t.startsWith("meia pilha")) {
			q = 32;
			t = t.substring("meia pilha".length()).trim();
		} else if (t.startsWith("uma pilha") || t.startsWith("um pack")) {
			q = 64;
			t = t.substring(t.indexOf(' ', 4) + 1).trim();
		} else {
			String first = t.contains(" ") ? t.substring(0, t.indexOf(' ')) : t;
			if (NUMBERS.containsKey(first)) {
				q = NUMBERS.get(first);
				t = t.substring(first.length()).trim();
			}
		}
		t = t.replaceFirst("^(de|da|do|das|dos)\\s+", "").replaceFirst("^(unidades?|blocos?|itens?)\\s+(de\\s+)?", "");
		rest.append(t.trim());
		return new int[] {q};
	}

	/** Classifica a frase (sem o nome de quem foi chamada). */
	public static Intent parse(String text) {
		String s = clean(text);
		if (s.isEmpty() || s.equals("ajuda") || s.contains("o que voce sabe fazer") || s.contains("o que voce consegue")) {
			return new Intent(Kind.HELP, "", 0);
		}
		if (s.matches(".*\\b(o que|oq)\\b.*\\b(esta|ta|tava) fazendo\\b.*") || s.equals("status") || s.startsWith("tudo bem")) {
			return new Intent(Kind.STATUS, "", 0);
		}
		if (s.matches(".*\\b(o que|oq)\\b.*\\b(ve|vendo|enxerga)\\b.*") || s.startsWith("olhe em volta") || s.startsWith("olha em volta")
			|| s.matches(".*o que tem (por perto|aqui|em volta).*")) {
			return new Intent(Kind.PERCEPTION, "", 0);
		}
		if (s.matches(".*\\b(o que|oq)\\b.*\\blembra\\b.*") || s.equals("memoria") || s.startsWith("sua memoria")) {
			return new Intent(Kind.MEMORY, "", 0);
		}
		if (s.matches("^(aqui|este lugar|esse lugar) (e|eh|sera|vai ser) (a )?(nossa |minha )?base.*") || s.startsWith("marque a base")
			|| s.startsWith("marca a base")) {
			return new Intent(Kind.BASE, "", 0);
		}
		if (s.matches("^(pare|para|cancele|cancela|esquece|esqueca|chega)\\b.*")) {
			return new Intent(Kind.STOP, "", 0);
		}
		if (s.matches("^(venha|vem|me siga|siga me|segue|volte|volta)\\b.*")) {
			return new Intent(Kind.FOLLOW, "", 0);
		}
		if (s.matches("^(descanse|descansa|va descansar|vai descansar|durma|dorme)\\b.*")) {
			return new Intent(Kind.REST, "", 0);
		}
		Matcher where = Pattern.compile("^onde (tem|acho|encontro|fica|esta|estao|tinha|viu|vi|ha|da pra achar|posso achar)\\s+(.*)$").matcher(s);
		if (where.matches()) {
			return new Intent(Kind.WHERE, where.group(2).replaceFirst("^(um|uma|o|a|os|as)\\s+", ""), 0);
		}
		if (s.startsWith("como ") || s.startsWith("o que e ") || s.startsWith("oque e ") || s.startsWith("me explica") || s.startsWith("explica")
			|| s.startsWith("qual ") || s.startsWith("quais ")) {
			return new Intent(Kind.HOW, s, 0);
		}
		Matcher skill = SKILL_VERB.matcher(s);
		if (skill.matches()) {
			StringBuilder rest = new StringBuilder();
			int q = quantity(skill.group(2), rest)[0];
			return new Intent(Kind.SKILL, s, q);
		}
		Matcher craft = CRAFT.matcher(s);
		if (craft.matches()) {
			StringBuilder rest = new StringBuilder();
			int q = quantity(craft.group(2).replaceFirst("^(um|uma)\\s+(?=[a-z])", "1 "), rest)[0];
			return new Intent(Kind.CRAFT, rest.toString(), q);
		}
		Matcher obtain = OBTAIN.matcher(s);
		if (obtain.matches()) {
			StringBuilder rest = new StringBuilder();
			int q = quantity(obtain.group(2), rest)[0];
			return new Intent(Kind.OBTAIN, rest.toString(), q);
		}
		if (text.trim().endsWith("?")) {
			return new Intent(Kind.HOW, s, 0);
		}
		return new Intent(Kind.UNKNOWN, s, 0);
	}

	// ------------------------------------------------------------------ chat

	/** Mensagem de chat da jogadora: se começar com o nome de uma companheira dela (ou "todas"), ela responde/age. */
	public static void onChat(ServerPlayer player, String text) {
		text = text.trim();
		ServerLevel level = (ServerLevel) player.level();
		String s = clean(text);
		List<CompanionEntity> mine = level.getEntitiesOfClass(CompanionEntity.class, player.getBoundingBox().inflate(256),
			c -> c.isAlive() && c.isNamed() && c.isOwnedBy(player));
		List<CompanionEntity> addressed = new ArrayList<>();
		String rest = null;
		for (String all : List.of("todas", "meninas", "pessoal")) {
			if (s.startsWith(all + " ")) {
				mine.stream().filter(c -> !c.isHelper()).forEach(addressed::add);
				rest = text.substring(text.toLowerCase(java.util.Locale.ROOT).indexOf(all) + all.length());
				break;
			}
		}
		if (addressed.isEmpty()) {
			for (CompanionEntity c : mine) {
				String name = clean(c.companionName());
				if (!name.isEmpty() && (s.equals(name) || s.startsWith(name + " "))) {
					addressed.add(c);
					int cut = Math.min(text.length(), c.companionName().length());
					rest = text.substring(cut);
					break;
				}
			}
		}
		if (addressed.isEmpty() || rest == null) {
			return;
		}
		String message = rest.replaceFirst("^[\\s,:;!-]+", "");
		for (CompanionEntity c : addressed) {
			for (String line : respond(c, player, message)) {
				player.sendSystemMessage(Component.literal("<" + c.companionName() + "> ").withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(Component.literal(line).withStyle(ChatFormatting.WHITE)));
			}
		}
	}

	/** Entende e executa; devolve as falas dela (já enviadas pelo chat em {@link #onChat}). */
	public static List<String> respond(CompanionEntity c, ServerPlayer owner, String message) {
		ServerLevel level = (ServerLevel) c.level();
		Intent intent = parse(message);
		List<String> out = new ArrayList<>();
		switch (intent.kind()) {
			case HELP -> {
				out.add("Posso buscar e fabricar coisas (\"traga 10 carvão\", \"faça uma fornalha\"), usar minhas habilidades (\"minere ferro\", \"colete madeira\"),");
				out.add("dizer o que vejo e o que lembro, onde achar recursos, explicar como fazer algo, marcar a base (\"aqui é a base\"), parar, vir até você ou descansar.");
			}
			case STATUS -> {
				Perception.Snapshot s = Perception.of(c);
				out.add("Agora: " + s.activity() + (s.order() == null ? "" : " — pedido: " + s.order()) + ".");
				out.add(String.format("Vida %.0f/%.0f, fome %d, cansaço %.0f.", s.health(), s.maxHealth(), s.hunger(), s.fatigue()));
			}
			case PERCEPTION -> out.addAll(Perception.of(c).describe().stream().limit(8).toList());
			case MEMORY -> out.addAll(MemoryReport.lines(c).stream().skip(1).limit(8).toList());
			case BASE -> {
				WorldMemory.get(level.getServer()).setPlace(c.getUUID(), "base", level.dimension(), owner.blockPosition(), WorldMemory.Certainty.FACT,
					level.getGameTime());
				out.add("Combinado: a base é aqui (" + owner.blockPosition().toShortString() + "). Vou lembrar.");
			}
			case STOP -> {
				Order o = c.orders().active();
				if (o == null) {
					out.add("Não estou fazendo nenhum pedido agora.");
				} else {
					c.brain().orders().cancel(level, o);
					out.add("Parei o pedido de " + o.targetName().getString() + ".");
				}
			}
			case FOLLOW -> {
				c.brain().recall(owner);
				out.add("Indo até você.");
			}
			case REST -> {
				c.brain().requestRest(level);
				out.add("Vou descansar um pouco.");
			}
			case WHERE -> out.addAll(where(c, level, intent.subject()));
			case HOW -> out.addAll(KnowledgeBase.answer(level.getServer(), message).stream().limit(6).toList());
			case SKILL -> out.addAll(skill(c, level, intent));
			case CRAFT, OBTAIN -> out.addAll(obtain(c, level, intent, message));
			case UNKNOWN -> {
				Skill skill = SkillLibrary.find(message);
				if (skill != null && skill.orderable()) {
					out.addAll(skill(c, level, new Intent(Kind.SKILL, message, 0)));
				} else {
					out.add("Não entendi. Diga \"ajuda\" para ver o que posso fazer.");
				}
			}
		}
		return out;
	}

	private static List<String> where(CompanionEntity c, ServerLevel level, String subject) {
		List<String> out = new ArrayList<>();
		Block block = Lookup.findBlock(subject);
		Item item = Lookup.findItem(subject);
		if (block == null && item != null && Block.byItem(item) != Blocks.AIR) {
			block = Block.byItem(item);
		}
		if (block == null && item != null) {
			var src = dev.autonomouscompanions.mining.DropIndex.blockSources(item).stream().filter(x -> !x.silkOnly()).findFirst();
			if (src.isPresent()) {
				block = src.get().state().getBlock();
			}
		}
		if (block != null) {
			Block target = block;
			BlockPos remembered = WorldMemory.get(level.getServer()).nearest(level, st -> st.is(target), c.blockPosition(), 512, java.util.Set.of());
			if (remembered != null) {
				out.add("Lembro de " + target.getName().getString() + " em " + remembered.toShortString() + " (a " + (int) Math.sqrt(remembered.distSqr(c.blockPosition()))
					+ " blocos daqui).");
			} else {
				var region = WorldMemory.get(level.getServer()).bestRegion(level.dimension(), st -> st.is(target), c.blockPosition(), 512);
				if (region != null) {
					out.add(String.format("Não lembro de um bloco exato, mas acho que costuma ter perto de %s (hipótese, %d observações).",
						region.center().toShortString(), region.observations()));
				} else {
					out.add("Não vi " + target.getName().getString() + " por aqui ainda.");
				}
			}
		}
		out.addAll(KnowledgeBase.answer(level.getServer(), "onde acho " + subject).stream().filter(l -> l.startsWith("Geração") || l.startsWith("Minerar"))
			.limit(2).toList());
		if (out.isEmpty()) {
			out.add("Não sei o que é \"" + subject + "\".");
		}
		return out;
	}

	private static List<String> skill(CompanionEntity c, ServerLevel level, Intent intent) {
		Skill skill = SkillLibrary.find(intent.subject());
		if (skill == null || !skill.orderable()) {
			return List.of("Não conheço uma habilidade para isso. Veja a lista com /companheiras habilidades.");
		}
		Component refusal = OrderService.createSkillOrder(level, c, skill.id(), intent.quantity());
		if (refusal != null) {
			return List.of(refusal.getString());
		}
		int q = intent.quantity() > 0 ? intent.quantity() : skill.defaultQuantity();
		return List.of("Certo: " + skill.name().toLowerCase(java.util.Locale.ROOT) + " (" + q + "). " + skill.goal());
	}

	private static List<String> obtain(CompanionEntity c, ServerLevel level, Intent intent, String message) {
		String subject = intent.subject();
		int q = intent.quantity() > 0 ? intent.quantity() : 1;
		String norm = clean(subject);
		if (norm.matches("(de )?carnes?( assadas?| cozidas?)?")) {
			boolean cooked = norm.contains("assad") || norm.contains("cozid");
			Component refusal = OrderService.createMeatOrder(level, c, cooked, q);
			return List.of(refusal != null ? refusal.getString() : "Vou buscar " + q + " de carne" + (cooked ? " assada" : "") + ", do animal mais perto.");
		}
		// Uma habilidade que descreve bem o pedido (ex.: "colete madeira") tem prioridade sobre o item solto.
		Skill skill = SkillLibrary.find(message);
		Item item = Lookup.findItem(subject);
		boolean confident = item != null && Lookup.matchScore(subject, item) >= 300;
		if (skill != null && skill.orderable() && (!confident || Lookup.matchScore(subject, item) < 900)) {
			Skill.Success success = skill.success();
			if (success != null && (!confident || Skill.target(success.item()) != null && Skill.target(success.item()).test(new net.minecraft.world.item.ItemStack(item)))) {
				return skill(c, level, new Intent(Kind.SKILL, message, intent.quantity()));
			}
		}
		if (!confident) {
			return List.of("Não sei qual item é \"" + subject + "\". Tente o nome do jogo (ex.: \"barra de ferro\") ou o id (ex.: minecraft:iron_ingot).");
		}
		Component refusal = OrderService.createItemOrder(level, c, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(), q);
		if (refusal != null) {
			return List.of(refusal.getString());
		}
		return List.of((intent.kind() == Kind.CRAFT ? "Vou fazer " : "Vou buscar ") + q + "× " + Lookup.name(item) + ".");
	}
}
