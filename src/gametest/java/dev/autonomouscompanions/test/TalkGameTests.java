package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.mind.knowledge.KnowledgeBase;
import dev.autonomouscompanions.mind.memory.WorldMemory;
import dev.autonomouscompanions.mind.talk.Talk;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.world.KnownResources;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Conversa e conhecimento: a fala vira pedido de verdade, usa habilidades, memória, percepção e conhecimento. (O servidor
 * de testes usa os nomes de itens em inglês; no jogo, os do idioma escolhido.)
 */
public class TalkGameTests {
	private static final String ARENA = "autonomouscompanions-test:arena";

	private static CompanionEntity sustained(CompanionEntity c) {
		c.settings().classicSurvival = false;
		return c;
	}

	/** "Rosa, traga 2 coal" no chat: o pedido é criado para ela (só para ela) e ela entrega. */
	@GameTest(environment = "autonomouscompanions-test:ac_talk1", structure = ARENA, maxTicks = 2400)
	public void chatOrderBecomesARealDelivery(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity rosa = sustained(TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa"));
		CompanionEntity vera = sustained(TestSupport.companion(h, owner, new BlockPos(8, FLOOR, 4), "Vera"));
		rosa.settings().debug = true;
		TestSupport.give(rosa, Items.STONE_PICKAXE, 1);
		for (int i = 0; i < 3; i++) {
			h.setBlock(new BlockPos(12 + i, FLOOR, 12), Blocks.COAL_ORE);
		}
		Talk.onChat(owner, "Rosa, traga 2 coal");
		h.assertTrue(rosa.orders().all().size() == 1 && vera.orders().all().isEmpty(), "o pedido foi para a companheira errada");
		Talk.onChat(owner, "Fulana, traga 1 coal"); // ninguém com esse nome: ignorado
		h.assertTrue(rosa.orders().all().size() == 1 && vera.orders().all().isEmpty(), "mensagem para outro nome criou pedido");
		Order order = rosa.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() == null ? "" : ": " + order.reason().getString()));
			h.assertValueEqual(TestSupport.count(owner, Items.COAL), 2, "carvão entregue");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Habilidade, base, percepção, memória, conhecimento e "pare", tudo pela fala. */
	@GameTest(environment = "autonomouscompanions-test:ac_talk2", structure = ARENA, maxTicks = 200)
	public void talkUsesSkillsMemoryPerceptionAndKnowledge(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa"));
		var level = h.getLevel();
		List<String> r = Talk.respond(c, owner, "minere carvão");
		h.assertTrue(!c.orders().all().isEmpty() && "minerar_carvao".equals(c.orders().all().getFirst().skill()), "não pediu a habilidade: " + r);
		r = Talk.respond(c, owner, "pare");
		h.assertTrue(r.getFirst().startsWith("Parei"), "pare: " + r);
		r = Talk.respond(c, owner, "aqui é a base");
		h.assertTrue(WorldMemory.get(level.getServer()).base(c.getUUID()).map(p -> p.pos().equals(owner.blockPosition())).orElse(false), "base: " + r);
		r = Talk.respond(c, owner, "o que você vê?");
		h.assertTrue(r.getFirst().startsWith("Estou em"), "percepção: " + r);
		BlockPos ore = h.absolutePos(new BlockPos(14, FLOOR, 14));
		h.setBlock(new BlockPos(14, FLOOR, 14), Blocks.COAL_ORE);
		KnownResources.record(level, ore, Blocks.COAL_ORE);
		r = Talk.respond(c, owner, "onde tem " + Blocks.COAL_ORE.getName().getString() + "?");
		h.assertTrue(r.stream().anyMatch(l -> l.startsWith("Lembro de") && l.contains(ore.toShortString())), "onde: " + r);
		r = Talk.respond(c, owner, "como faço tochas?");
		h.assertTrue(r.stream().anyMatch(l -> l.startsWith("[")) && r.stream().anyMatch(l -> l.startsWith("Habilidade relacionada: Fazer tochas")), "como: " + r);
		r = Talk.respond(c, owner, "o que você lembra?");
		h.assertTrue(r.stream().anyMatch(l -> l.startsWith("Base (fato)")), "memória: " + r);
		WorldMemory.get(level.getServer()).forget(c.getUUID());
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/**
	 * Conhecimento: carregado dos arquivos, todo id citado existe nesta versão, a busca acha a seção certa e a resposta junta
	 * o catálogo (como obter) e a geração do minério desta versão.
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_talk3", structure = ARENA, maxTicks = 100)
	public void knowledgeIsValidatedAndAnswersWithRealData(GameTestHelper h) {
		var sections = KnowledgeBase.all();
		h.assertTrue(sections.size() >= 25 && sections.stream().map(KnowledgeBase.Section::file).distinct().count() >= 8, "conhecimento: " + sections.size()
			+ " seções");
		for (String id : KnowledgeBase.mentionedIds()) {
			h.assertTrue(KnowledgeBase.exists(id), "o conhecimento cita " + id + ", que não existe nesta versão");
		}
		var s = KnowledgeBase.search("como cavar com segurança");
		h.assertTrue(s != null && s.title().equals("Como cavar com segurança"), "busca: " + (s == null ? "nada" : s.title()));
		s = KnowledgeBase.search("o que fazer à noite com os monstros");
		h.assertTrue(s != null && s.title().equals("Noite e monstros"), "busca noturna: " + (s == null ? "nada" : s.title()));
		List<String> a = KnowledgeBase.answer(h.getLevel().getServer(), "onde acho " + Blocks.DIAMOND_ORE.getName().getString());
		h.assertTrue(a.stream().anyMatch(l -> l.startsWith("Geração nesta versão") && l.contains("ore_diamond")), "geração do diamante na resposta: " + a);
		h.succeed();
	}
}
