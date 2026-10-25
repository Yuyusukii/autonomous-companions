package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.mind.memory.WorldMemory;
import dev.autonomouscompanions.mind.skills.Skill;
import dev.autonomouscompanions.mind.skills.SkillLibrary;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderService;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Habilidades executáveis (dados em JSON + etapas reais do mod): validação contra a versão, execução de verdade,
 * recuperação de falhas ("procurar mais longe") e aprendizado com uma falha recente. Cada teste num lote próprio.
 */
public class SkillGameTests {
	private static final String ARENA = "autonomouscompanions-test:arena";
	private static final String LONG = "autonomouscompanions-test:long_corridor";

	private static CompanionEntity sustained(CompanionEntity c) {
		c.settings().classicSurvival = false;
		return c;
	}

	/** As habilidades do mod são válidas; a inválida do pacote de teste é recusada com os problemas; a reativa não vira ordem. */
	@GameTest(environment = "autonomouscompanions-test:ac_skill1", structure = ARENA, maxTicks = 100)
	public void skillsAreValidatedAgainstTheVersion(GameTestHelper h) {
		for (String id : List.of("minerar_ferro", "minerar_carvao", "coletar_madeira", "fazer_tabuas", "fabricar_picareta_de_pedra", "fazer_fornalha", "fazer_tochas",
			"minerar_diamante", "fugir_do_perigo")) {
			Skill s = SkillLibrary.get(id);
			h.assertTrue(s != null, "habilidade " + id + " não carregada");
			h.assertTrue(s.valid(), "habilidade " + id + " inválida: " + s.problems());
		}
		Skill bad = SkillLibrary.get("habilidade_invalida");
		h.assertTrue(bad != null && !bad.valid(), "a habilidade inválida deveria ter sido marcada como inválida");
		String problems = String.join(" | ", bad.problems());
		h.assertTrue(problems.contains("voar") && problems.contains("item_que_nao_existe") && problems.contains("CHUVA"), "problemas: " + problems);
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa"));
		h.assertTrue(OrderService.createSkillOrder(h.getLevel(), c, "habilidade_invalida", 1) != null, "aceitou a habilidade inválida");
		h.assertTrue(OrderService.createSkillOrder(h.getLevel(), c, "fugir_do_perigo", 1) != null, "aceitou a habilidade reativa como ordem");
		h.assertTrue(c.orders().all().isEmpty(), "criou pedido recusado");
		List<String> sheet = SkillLibrary.get("minerar_ferro").describe();
		h.assertTrue(sheet.getFirst().equals("HABILIDADE: MINERAR FERRO") && sheet.contains("Pré-requisitos:") && sheet.contains("Procedimento:")
			&& sheet.contains("Recuperação de erros:") && sheet.stream().anyMatch(l -> l.startsWith("Condição de sucesso")), "ficha: " + sheet);
		h.assertTrue(SkillLibrary.find("quero minerar ferro") == SkillLibrary.get("minerar_ferro"), "busca por palavras-chave");
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** A habilidade de minerar carvão roda de verdade, entrega e fica registrada como experiência. */
	@GameTest(environment = "autonomouscompanions-test:ac_skill2", structure = ARENA, maxTicks = 2400)
	public void coalSkillRunsAndIsRemembered(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa"));
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_PICKAXE, 1);
		for (int i = 0; i < 4; i++) {
			h.setBlock(new BlockPos(12 + i, FLOOR, 12), Blocks.COAL_ORE);
		}
		h.assertTrue(OrderService.createSkillOrder(h.getLevel(), c, "minerar_carvao", 2) == null, "pedido recusado");
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() == null ? "" : ": " + order.reason().getString()));
			h.assertValueEqual(TestSupport.count(owner, Items.COAL), 2, "carvão entregue");
			var stat = WorldMemory.get(h.getLevel().getServer()).stat(c.getUUID(), "HABILIDADE|minerar_carvao");
			h.assertTrue(stat != null && stat.successes() == 1, "experiência da habilidade: " + stat);
			WorldMemory.get(h.getLevel().getServer()).forget(c.getUUID());
			TestSupport.cleanup(h, owner);
		});
	}

	/** "Coletar madeira" aceita qualquer tronco (grupo do jogo): numa área só de bétulas, entrega troncos de bétula. */
	@GameTest(environment = "autonomouscompanions-test:ac_skill3", structure = ARENA, maxTicks = 2400)
	public void woodSkillTakesAnyLog(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa"));
		c.settings().debug = true;
		for (int y = 0; y < 4; y++) {
			h.setBlock(new BlockPos(12, FLOOR + y, 12), Blocks.BIRCH_LOG);
			h.setBlock(new BlockPos(16, FLOOR + y, 10), Blocks.BIRCH_LOG);
		}
		h.assertTrue(OrderService.createSkillOrder(h.getLevel(), c, "coletar_madeira", 4) == null, "pedido recusado");
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() == null ? "" : ": " + order.reason().getString()));
			h.assertTrue(TestSupport.count(owner, Items.BIRCH_LOG) == 4 && owner.getInventory().countItem(Items.OAK_LOG) == 0, "troncos entregues: "
				+ TestSupport.count(owner, Items.BIRCH_LOG));
			h.assertTrue(new net.minecraft.world.item.ItemStack(Items.BIRCH_LOG).is(ItemTags.LOGS), "bétula deveria estar no grupo de troncos");
			WorldMemory.get(h.getLevel().getServer()).forget(c.getUUID());
			TestSupport.cleanup(h, owner);
		});
	}

	/**
	 * Recuperação: sem nenhum carvão no começo, a etapa de minerar falha ("não encontrado"); a habilidade aplica a
	 * recuperação prevista ("procurar mais longe") e repete a mesma etapa em vez de desistir — quando o carvão aparece por
	 * perto (o teste o coloca assim que a recuperação acontece), ela conclui. (A ampliação do alcance em si é verificada no
	 * teste de aprendizado: busca com raio 48 em vez de 24.)
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_skill4", structure = ARENA, maxTicks = 6000)
	public void skillRecoveryRetriesTheStep(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Vera"));
		c.settings().debug = true;
		c.settings().maxSearchDistance = 24;
		c.settings().allowTerrainChanges = false;
		TestSupport.give(c, Items.STONE_PICKAXE, 1);
		h.assertTrue(OrderService.createSkillOrder(h.getLevel(), c, "minerar_carvao", 1) == null, "pedido recusado");
		Order order = c.orders().all().getFirst();
		boolean[] placed = {false};
		h.onEachTick(() -> {
			if (!placed[0] && c.brain().metrics().skillRecoveries() >= 1) {
				placed[0] = true;
				h.setBlock(new BlockPos(12, FLOOR, 12), Blocks.COAL_ORE);
			}
		});
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() == null ? "" : ": " + order.reason().getString()));
			h.assertTrue(c.brain().metrics().skillRecoveries() >= 1, "não aplicou nenhuma recuperação");
			WorldMemory.get(h.getLevel().getServer()).forget(c.getUUID());
			TestSupport.cleanup(h, owner);
		});
	}

	/**
	 * Aprendizado: com uma falha recente "não encontrado" da mesma habilidade na memória, ela já começa procurando mais longe
	 * — acha o carvão a 40 blocos (além do alcance de 24) sem precisar de recuperação.
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_skill5", structure = LONG, maxTicks = 3000)
	public void skillLearnsFromARecentFailure(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(152, FLOOR, 4), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(150, FLOOR, 4), "Vera"));
		c.settings().debug = true;
		c.settings().maxSearchDistance = 24;
		// Sem escavar terreno: as galerias de exploração (24 blocos por etapa) dariam alcance extra e o teste não mediria a busca.
		c.settings().allowTerrainChanges = false;
		TestSupport.give(c, Items.STONE_PICKAXE, 1);
		h.setBlock(new BlockPos(110, FLOOR, 4), Blocks.COAL_ORE);
		ServerLevel level = h.getLevel();
		WorldMemory memory = WorldMemory.get(level.getServer());
		memory.recordExperience(c.getUUID(), new WorldMemory.Experience(level.getGameTime(), "HABILIDADE", "minerar_carvao", false,
			"NOT_FOUND: nada de carvão por perto", c.blockPosition(), 1200));
		h.assertTrue(OrderService.createSkillOrder(level, c, "minerar_carvao", 1) == null, "pedido recusado");
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() == null ? "" : ": " + order.reason().getString()));
			h.assertTrue(c.brain().metrics().learnedAdjustments() >= 1, "não usou a experiência recente");
			h.assertValueEqual(c.brain().metrics().skillRecoveries(), 0L, "recuperações (deveria ter começado procurando mais longe)");
			memory.forget(c.getUUID());
			TestSupport.cleanup(h, owner);
		});
	}
}
