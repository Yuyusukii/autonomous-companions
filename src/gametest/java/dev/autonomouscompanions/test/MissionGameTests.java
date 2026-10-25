package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.crafting.CostModel;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.DeliveryService;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderBook;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.OrderService;
import dev.autonomouscompanions.task.exec.ItemTarget;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Testes 7 a 16 e 28: catálogo, quantidades, planejamento, fabricação, mineração, entrega e recuperação. */
public class MissionGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";
	private static final String CORRIDOR = "autonomouscompanions-test:corridor";

	private static boolean hasKey(List<Component> lines, String key) {
		return lines.stream().anyMatch(l -> l.getContents() instanceof TranslatableContents tc && tc.getKey().equals(key));
	}

	private static Order onlyOrder(CompanionEntity c) {
		return c.orders().all().getFirst();
	}

	/** Teste 7: blocos diferentes do catálogo geram alvos e métodos corretos (drops, bloco exato, inquebráveis). */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void catalogSelectionsProduceCorrectTargets(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(10, FLOOR, 10), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Lia");
		var level = h.getLevel();
		h.assertTrue(OrderService.block("minecraft:deepslate_diamond_ore") == Blocks.DEEPSLATE_DIAMOND_ORE, "id do catálogo não resolvido");
		h.assertTrue(OrderService.block("minecraft:nao_existe") == null, "id inexistente aceito");
		var stone = OrderService.analyzeBlock(level, c, Blocks.STONE, GatherMode.DROPS, 10);
		h.assertTrue(stone.feasible() && stone.target() == Items.COBBLESTONE, "pedra (drops) deveria render pedregulho");
		var stoneExact = OrderService.analyzeBlock(level, c, Blocks.STONE, GatherMode.EXACT_BLOCK, 10);
		h.assertTrue(stoneExact.feasible() && stoneExact.target() == Items.STONE, "pedra exata deveria ser viável (fundindo pedregulho)");
		var log = OrderService.analyzeBlock(level, c, Blocks.OAK_LOG, GatherMode.DROPS, 5);
		h.assertTrue(log.feasible() && log.target() == Items.OAK_LOG, "tronco de carvalho");
		var diamond = OrderService.analyzeBlock(level, c, Blocks.DIAMOND_ORE, GatherMode.DROPS, 2);
		h.assertTrue(diamond.feasible() && diamond.target() == Items.DIAMOND, "minério de diamante deveria render diamante: " + diamond.target() + " " + diamond.lines().stream().map(Component::getString).toList());
		h.assertTrue(diamond.range() > stone.range(), "minério raro deveria usar alcance de busca maior");
		var bedrock = OrderService.analyzeBlock(level, c, Blocks.BEDROCK, GatherMode.DROPS, 1);
		h.assertTrue(!bedrock.feasible() && hasKey(bedrock.lines(), "analysis.autonomouscompanions.unbreakable"), "rocha-matriz deveria ser recusada");
		var glass = OrderService.analyzeBlock(level, c, Blocks.GLASS, GatherMode.DROPS, 1);
		h.assertTrue(!glass.feasible(), "vidro quebrado não deixa itens: deveria ser recusado no modo drops");
		var glassExact = OrderService.analyzeBlock(level, c, Blocks.GLASS, GatherMode.EXACT_BLOCK, 4);
		h.assertTrue(glassExact.feasible() && glassExact.target() == Items.GLASS, "vidro exato deveria ser viável (areia na fornalha)");
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** Teste 8: quantidades exatas, limites e fila. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void quantitiesAreExactAndBounded(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(10, FLOOR, 10), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Nina");
		var level = h.getLevel();
		h.assertTrue(OrderService.createGather(level, c, "minecraft:stone", GatherMode.DROPS, 37, SearchProfile.AUTO, 0, false) == null, "ordem de 37 recusada");
		h.assertValueEqual(c.orders().all().getLast().requested(), 37, "quantidade pedida");
		OrderService.createGather(level, c, "minecraft:stone", GatherMode.DROPS, 0, SearchProfile.AUTO, 0, false);
		h.assertValueEqual(c.orders().all().getLast().requested(), 1, "quantidade mínima");
		OrderService.createGather(level, c, "minecraft:stone", GatherMode.DROPS, 999_999, SearchProfile.AUTO, 0, false);
		h.assertValueEqual(c.orders().all().getLast().requested(), OrderService.MAX_COUNT, "quantidade máxima");
		while (c.orders().canAdd()) {
			OrderService.createGather(level, c, "minecraft:stone", GatherMode.DROPS, 3, SearchProfile.AUTO, 0, false);
		}
		h.assertValueEqual(c.orders().all().size(), OrderBook.MAX_QUEUED, "tamanho da fila");
		Component refusal = OrderService.createGather(level, c, "minecraft:stone", GatherMode.DROPS, 3, SearchProfile.AUTO, 0, false);
		h.assertTrue(refusal != null && refusal.getContents() instanceof TranslatableContents tc && tc.getKey().equals("order.autonomouscompanions.queue_full"),
			"fila cheia não recusou");
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** Teste 9: o planejador reconhece as ferramentas necessárias e o custo cresce com a dificuldade. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void plannerKnowsRequiredTools(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(10, FLOOR, 10), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Iara");
		var level = h.getLevel();
		var noTool = OrderService.analyzeBlock(level, c, Blocks.STONE, GatherMode.DROPS, 8);
		h.assertTrue(hasKey(noTool.lines(), "analysis.autonomouscompanions.will_craft_tool"), "sem picareta, deveria planejar fabricá-la");
		CostModel costs = new CostModel(level, c);
		double stoneTool = costs.requiredToolCost(Blocks.STONE.defaultBlockState());
		double ironTool = costs.requiredToolCost(Blocks.IRON_ORE.defaultBlockState());
		double diamondTool = costs.requiredToolCost(Blocks.DIAMOND_ORE.defaultBlockState());
		h.assertTrue(stoneTool > 0 && stoneTool < CostModel.INF, "custo da picareta de madeira: " + stoneTool);
		h.assertTrue(ironTool > stoneTool && diamondTool > ironTool, "custos das ferramentas fora de ordem: " + stoneTool + " / " + ironTool + " / " + diamondTool);
		var methods = costs.methods(ItemTarget.of(Items.COBBLESTONE), 8, Set.of(), false);
		h.assertTrue(!methods.isEmpty() && methods.getFirst() instanceof CostModel.Mine, "o melhor método para pedregulho deveria ser minerar");
		TestSupport.give(c, Items.WOODEN_PICKAXE, 1);
		var withTool = OrderService.analyzeBlock(level, c, Blocks.STONE, GatherMode.DROPS, 8);
		h.assertTrue(hasKey(withTool.lines(), "analysis.autonomouscompanions.has_tool"), "com picareta, não deveria planejar outra");
		h.assertTrue(new CostModel(level, c).requiredToolCost(Blocks.STONE.defaultBlockState()) == 0, "já tem a ferramenta: custo deveria ser zero");
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** Testes 10 e 11: só com troncos, fabrica tábuas, bancada, gravetos e picareta, minera pedra de verdade e entrega. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3600)
	public void craftsToolsFromLogsAndMinesRealStone(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(8, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 12), "Tainá");
		c.settings().debug = true;
		TestSupport.give(c, Items.OAK_LOG, 3);
		BlockPos[] stones = {new BlockPos(17, FLOOR, 11), new BlockPos(17, FLOOR, 12), new BlockPos(17, FLOOR, 13)};
		for (BlockPos p : stones) {
			h.setBlock(p, Blocks.STONE);
		}
		h.assertTrue(OrderService.createGather(h.getLevel(), c, "minecraft:stone", GatherMode.DROPS, 3, SearchProfile.COMMON, 0, false) == null,
			"ordem recusada");
		Order order = onlyOrder(c);
		h.succeedWhen(() -> {
			h.assertValueEqual(TestSupport.count(owner, Items.COBBLESTONE), 3, "pedregulho entregue");
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem não concluída: " + order.status());
			for (BlockPos p : stones) {
				h.assertBlockPresent(Blocks.AIR, p);
			}
			boolean pick = c.inventory().count(Items.WOODEN_PICKAXE) > 0 || c.mainHandItem().is(Items.WOODEN_PICKAXE);
			h.assertTrue(pick, "a picareta fabricada sumiu");
			h.assertValueEqual(c.inventory().count(Items.OAK_LOG), 0, "troncos usados nas receitas");
			h.assertTrue(order.progress().itemsCrafted >= 3, "itens fabricados registrados: " + order.progress().itemsCrafted);
			h.assertTrue(order.progress().blocksMined >= 3, "blocos minerados registrados: " + order.progress().blocksMined);
			h.assertValueEqual(TestSupport.itemEntities(h, Items.COBBLESTONE), 0, "pedregulho largado no chão");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Testes 12, 13 e 28: entrega exatamente o pedido, guarda o excedente e nunca entrega duas vezes. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 1200)
	public void deliversExactAmountWithoutExtrasOrDuplicates(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(8, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(14, FLOOR, 12), "Cora");
		c.settings().debug = true;
		TestSupport.give(c, Items.COBBLESTONE, 10);
		TestSupport.give(c, Items.STONE_PICKAXE, 1);
		OrderService.createGather(h.getLevel(), c, "minecraft:stone", GatherMode.DROPS, 4, SearchProfile.COMMON, 0, false);
		Order order = onlyOrder(c);
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem não concluída: " + order.status()))
			.thenExecute(() -> {
				h.assertValueEqual(TestSupport.count(owner, Items.COBBLESTONE), 4, "entregue à dona");
				h.assertValueEqual(c.inventory().count(Items.COBBLESTONE), 6, "excedente guardado pela companheira");
				h.assertValueEqual(order.progress().delivered, 4, "contador de entrega");
				// Repetições não entregam nada a mais (idempotência).
				var again = DeliveryService.deliverOrder(c, order, owner, true);
				h.assertValueEqual(again.delivered(), 0, "segunda entrega da mesma ordem");
				ServerNetworking.handleDeliver(owner, new Payloads.Deliver(c.getId(), Payloads.Deliver.MISSION, 0));
				h.assertValueEqual(TestSupport.count(owner, Items.COBBLESTONE), 4, "entrega duplicada pelo botão de missão");
				h.assertValueEqual(TestSupport.itemEntities(h, Items.COBBLESTONE), 0, "itens extras largados no chão");
			})
			.thenIdle(40)
			.thenExecute(() -> {
				h.assertValueEqual(TestSupport.count(owner, Items.COBBLESTONE), 4, "a dona recebeu itens depois de concluída");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/** Teste 14: inventário da dona cheio → espera sem perder nada e entrega quando há espaço. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 1600)
	public void waitsWhenOwnerInventoryIsFull(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(8, FLOOR, 12), GameType.SURVIVAL);
		Inventory inv = owner.getInventory();
		for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
			inv.setItem(i, new ItemStack(Items.DIRT, 64));
		}
		inv.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.DIRT, 64));
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Duda");
		c.settings().debug = true;
		TestSupport.give(c, Items.COBBLESTONE, 5);
		OrderService.createGather(h.getLevel(), c, "minecraft:stone", GatherMode.DROPS, 5, SearchProfile.COMMON, 0, false);
		Order order = onlyOrder(c);
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(order.status() == OrderStatus.WAITING_SPACE, "deveria esperar espaço: " + order.status()))
			.thenExecute(() -> {
				h.assertValueEqual(c.inventory().count(Items.COBBLESTONE), 5, "itens preservados com a companheira");
				h.assertValueEqual(TestSupport.count(owner, Items.COBBLESTONE), 0, "nada deveria entrar no inventário cheio");
				h.assertValueEqual(TestSupport.itemEntities(h, Items.COBBLESTONE), 0, "itens largados no chão");
				inv.setItem(5, ItemStack.EMPTY);
			})
			.thenWaitUntil(() -> {
				h.assertValueEqual(TestSupport.count(owner, Items.COBBLESTONE), 5, "entregue depois de liberar espaço");
				h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem não concluída: " + order.status());
			})
			.thenExecute(() -> {
				h.assertValueEqual(c.inventory().count(Items.COBBLESTONE), 0, "sobrou pedregulho que deveria ter sido entregue");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/** Teste 15: busca material a ~60 blocos (além do limite de exploração ociosa, que não vale para missões). */
	@GameTest(environment = DAY, structure = CORRIDOR, maxTicks = 3600)
	public void fetchesDistantMaterial(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(3, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(5, FLOOR, 4), "Vera");
		c.settings().debug = true;
		c.settings().idleRadius = 10;
		TestSupport.give(c, Items.WOODEN_PICKAXE, 1);
		BlockPos ore = new BlockPos(67, FLOOR, 4);
		h.setBlock(ore, Blocks.COAL_ORE);
		OrderService.createGather(h.getLevel(), c, "minecraft:coal_ore", GatherMode.DROPS, 1, SearchProfile.COMMON, 0, false);
		Order order = onlyOrder(c);
		double[] farthest = {0};
		h.succeedWhen(() -> {
			farthest[0] = Math.max(farthest[0], TestSupport.horizontal(c, owner));
			h.assertBlockPresent(Blocks.AIR, ore);
			h.assertTrue(TestSupport.count(owner, Items.COAL) >= 1, "carvão não entregue");
			h.assertValueEqual(TestSupport.count(owner, Items.COAL), 1, "carvão entregue");
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem não concluída");
			h.assertTrue(farthest[0] > 50, "não foi até o minério distante: " + farthest[0]);
			TestSupport.cleanup(h, owner);
		});
	}

	/** Teste 16: a tarefa interrompida (descarregar/recarregar no meio) continua de onde parou e termina. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3000)
	public void resumesInterruptedTaskAfterReload(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(8, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity first = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 12), "Rita");
		TestSupport.give(first, Items.STONE_PICKAXE, 1);
		for (int z = 10; z <= 14; z++) {
			h.setBlock(new BlockPos(18, FLOOR, z), Blocks.STONE);
		}
		OrderService.createGather(h.getLevel(), first, "minecraft:stone", GatherMode.DROPS, 5, SearchProfile.COMMON, 0, false);
		UUID orderId = onlyOrder(first).id();
		CompanionEntity[] current = {first};
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(onlyOrder(first).progress().blocksMined >= 2, "ainda não começou a minerar"))
			.thenExecute(() -> {
				int minedBefore = onlyOrder(first).progress().blocksMined;
				current[0] = TestSupport.reload(h, first);
				Order restored = onlyOrder(current[0]);
				h.assertValueEqual(restored.id(), orderId, "id da ordem após recarregar");
				h.assertValueEqual(restored.progress().blocksMined, minedBefore, "progresso após recarregar");
				h.assertTrue(!restored.status().isFinished(), "ordem deveria continuar ativa");
			})
			.thenWaitUntil(() -> {
				h.assertValueEqual(TestSupport.count(owner, Items.COBBLESTONE), 5, "entregue após retomar");
				h.assertTrue(onlyOrder(current[0]).status() == OrderStatus.COMPLETED, "ordem não concluída após retomar");
			})
			.thenExecute(() -> {
				h.assertValueEqual(h.getEntities(dev.autonomouscompanions.registry.ModEntities.COMPANION).size(), 1, "duplicou a companheira ao recarregar");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}
}
