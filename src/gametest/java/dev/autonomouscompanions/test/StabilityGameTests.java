package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import com.google.gson.Gson;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.config.CompanionConfig;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionRole;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.world.ResourceScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Testes 24 (persistência das tarefas), 25 (buscas sem travar) e 29 (configuração x comportamento). */
public class StabilityGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String CONFIG = "autonomouscompanions-test:ac_config";
	private static final String ARENA = "autonomouscompanions-test:arena";

	/** Teste 24: fila de ordens, função, ajustes, necessidades e estatísticas sobrevivem a salvar/recarregar. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void tasksAndStatePersist(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(8, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Elis");
		c.settings().stay = true;
		c.settings().stayPos = c.blockPosition();
		Order gather = Order.gather(Items.IRON_INGOT, "minecraft:iron_ore", GatherMode.DROPS, 20, SearchProfile.RARE, 900, 10);
		gather.progress().obtained = 7;
		gather.progress().delivered = 3;
		gather.setUrgent(true);
		Order meat = Order.craft(Items.COOKED_PORKCHOP, 6, 11);
		Order equip = Order.equip(12);
		c.orders().add(gather);
		c.orders().add(meat);
		c.orders().add(equip);
		c.orders().setStatus(meat, OrderStatus.SUSPENDED);
		c.setRole(CompanionRole.EXPLORER);
		c.settings().followMax = 12;
		c.settings().idleRadius = 64;
		c.needs().setHunger(9);
		c.needs().setFatigue(55);
		c.stats().blocksMined = 321;
		TestSupport.give(c, Items.IRON_INGOT, 7);
		CompanionEntity back = TestSupport.reload(h, c);
		List<Order> orders = back.orders().all();
		h.assertValueEqual(orders.size(), 3, "ordens após recarregar");
		h.assertValueEqual(orders.get(0).id(), gather.id(), "id da primeira ordem");
		h.assertValueEqual(orders.get(0).progress().obtained, 7, "progresso obtido");
		h.assertValueEqual(orders.get(0).remainingToDeliver(), 17, "falta entregar");
		h.assertTrue(orders.get(0).urgent(), "urgência perdida");
		h.assertTrue(orders.get(1).status() == OrderStatus.SUSPENDED, "ordem suspensa perdeu o estado");
		h.assertTrue(orders.get(2).type() == dev.autonomouscompanions.task.OrderEnums.OrderType.EQUIP, "tipo da terceira ordem");
		h.assertTrue(back.role() == CompanionRole.EXPLORER, "função perdida");
		h.assertValueEqual(back.settings().followMax, 12, "ajuste de acompanhamento");
		h.assertValueEqual(back.settings().idleRadius, 64, "raio de exploração");
		h.assertTrue(back.settings().stay && back.settings().stayPos != null, "ordem de ficar no local perdida");
		h.assertValueEqual(back.needs().hunger(), 9, "fome");
		h.assertTrue(Math.abs(back.needs().fatigue() - 55) < 0.01, "cansaço");
		h.assertValueEqual(back.stats().blocksMined, 321, "estatísticas");
		h.assertValueEqual(back.inventory().count(Items.IRON_INGOT), 7, "inventário");
		h.assertValueEqual(back.inventory().reserved(Items.IRON_INGOT), 17, "reserva dos itens da dona");
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** Teste 25: buscas de expedição (4096 blocos) respeitam o orçamento por tick, não carregam chunks e não travam. */
	@GameTest(environment = "autonomouscompanions-test:ac_scan", structure = ARENA, maxTicks = 300)
	public void resourceSearchesNeverStallTheServer(GameTestHelper h) {
		var level = h.getLevel();
		BlockPos center = h.absolutePos(new BlockPos(12, FLOOR, 12));
		List<ResourceScanner.Job> jobs = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			jobs.add(new ResourceScanner.Job(s -> s.is(Blocks.ANCIENT_DEBRIS), center, 4096, level.getMinY(), level.getMaxY(), false, Set.of(), 4));
		}
		int budget = ConfigManager.get().secoesAnalisadasPorTick;
		int loadedBefore = level.getChunkSource().getLoadedChunksCount();
		long[] worst = {0};
		int[] previous = {0};
		h.startSequence()
			.thenExecuteFor(200, () -> {
				long t0 = System.nanoTime();
				for (int k = 0; k < 20; k++) {
					for (ResourceScanner.Job job : jobs) {
						job.step(level);
					}
				}
				worst[0] = Math.max(worst[0], System.nanoTime() - t0);
				int total = jobs.stream().mapToInt(ResourceScanner.Job::sectionsScanned).sum();
				h.assertTrue(total - previous[0] <= budget, "analisou " + (total - previous[0]) + " seções num tick (orçamento " + budget + ")");
				previous[0] = total;
			})
			.thenExecute(() -> {
				h.assertTrue(worst[0] < 20_000_000L, "um tick de busca levou " + worst[0] / 1_000_000.0 + " ms");
				int loadedAfter = level.getChunkSource().getLoadedChunksCount();
				h.assertTrue(loadedAfter <= loadedBefore + 4, "a busca carregou chunks: " + loadedBefore + " -> " + loadedAfter);
				h.assertTrue(jobs.getFirst().unloadedChunks() > 0, "deveria ter pulado chunks não carregados");
				h.assertTrue(jobs.getFirst().sectionsScanned() > 0, "a busca não avançou");
				h.assertTrue(!jobs.getFirst().isComplete(), "uma busca de 4096 blocos não pode terminar em 200 ticks sem carregar nada");
			})
			.thenSucceed();
	}

	/** Teste 29: valores da configuração mudam de fato o comportamento observado. */
	@GameTest(environment = CONFIG, structure = ARENA, maxTicks = 1600)
	public void configurationChangesBehavior(GameTestHelper h) {
		Gson gson = new Gson();
		CompanionConfig backup = gson.fromJson(gson.toJson(ConfigManager.get()), CompanionConfig.class);
		CompanionConfig cfg = ConfigManager.get();
		cfg.vidaMaxima = 30.0;
		cfg.distanciaMinimaAcompanhamento = 4;
		cfg.distanciaMaximaAcompanhamento = 9;
		cfg.construcaoHabilitada = false;
		cfg.trabalhoAutonomoDasFuncoes = false;
		cfg.limiteExploracaoOciosa = 6;
		cfg.sonoHabilitado = false;
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(12, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 12), "Tess");
		CompanionEntity miner = TestSupport.companion(h, owner, new BlockPos(14, FLOOR, 12), "Moa");
		h.assertTrue(Math.abs(c.getAttributeValue(Attributes.MAX_HEALTH) - 30.0) < 1e-6, "vida máxima não aplicada");
		h.assertValueEqual(c.settings().followMin, 4, "distância mínima padrão");
		h.assertValueEqual(c.settings().followMax, 9, "distância máxima padrão");
		c.settings().idleExplore = true;
		c.settings().idleRadius = 50;
		c.needs().setFatigue(99);
		TestSupport.give(miner, Items.WOODEN_PICKAXE, 1);
		miner.settings().roleWork = true;
		miner.setRole(CompanionRole.MINER);
		for (int z = 3; z <= 6; z++) {
			h.setBlock(new BlockPos(20, FLOOR, z), Blocks.STONE);
		}
		ServerNetworking.handleBuildMark(owner, new Payloads.BuildMark(c.getId(), 0, 0, 1, true));
		h.assertTrue(!ServerNetworking.isMarking(owner), "construção desativada, mas o modo de marcação abriu");
		h.startSequence()
			.thenExecuteFor(1400, () -> {
				h.assertTrue(TestSupport.horizontal(c, owner) <= 6 + 3.0, "exploração passou do limite global configurado: " + TestSupport.horizontal(c, owner));
				h.assertTrue(!c.isSleeping() && c.companionState() != CompanionState.SLEEPING, "dormiu com o sono desativado");
			})
			.thenExecute(() -> {
				h.assertValueEqual(miner.inventory().count(Items.COBBLESTONE), 0, "trabalhou na função com o trabalho autônomo desativado");
				h.assertBlockPresent(Blocks.STONE, new BlockPos(20, FLOOR, 3));
				ConfigManager.set(backup);
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}
}
