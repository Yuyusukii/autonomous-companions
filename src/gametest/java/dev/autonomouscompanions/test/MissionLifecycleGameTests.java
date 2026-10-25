package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.OrderService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Ciclo de vida da missão: só termina com a entrega verificada; itens que somem antes da entrega são obtidos de novo; a
 * ordem nunca vira "concluída" sem entregar; materiais em uso pela missão não são consumidos por outras rotinas.
 */
public class MissionLifecycleGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";

	private static void logs(GameTestHelper h, int x, int z, int height) {
		for (int y = 0; y < height; y++) {
			h.setBlock(new BlockPos(x, FLOOR + y, z), Blocks.OAK_LOG);
		}
	}

	/** Os troncos somem no caminho de volta (comidos, gastos, perdidos): ela obtém de novo e entrega a quantidade toda. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 4000)
	public void itemsLostBeforeDeliveryAreFetchedAgain(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(16, FLOOR, 16), "Rosa");
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_AXE, 1);
		logs(h, 18, 18, 4);
		logs(h, 20, 16, 4);
		logs(h, 16, 20, 4);
		OrderService.createGather(h.getLevel(), c, "minecraft:oak_log", GatherMode.DROPS, 4, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		boolean[] removed = {false};
		h.onEachTick(() -> {
			if (!removed[0] && order.status() == OrderStatus.RETURNING && order.progress().delivered == 0) {
				removed[0] = true;
				c.inventory().removeRaw(Items.OAK_LOG, 64);
			}
		});
		h.succeedWhen(() -> {
			h.assertTrue(removed[0], "o teste não chegou a tirar os troncos no caminho de volta");
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() == null ? "" : ": " + order.reason().getString()));
			h.assertValueEqual(TestSupport.count(owner, Items.OAK_LOG), 4, "troncos entregues");
			h.assertValueEqual(order.progress().delivered, 4, "entregues no registro da ordem");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Os itens somem toda vez que ela volta: depois de 2 novas tentativas a ordem termina como falha, nunca como concluída. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 6000)
	public void neverCompletesWithoutDelivering(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(16, FLOOR, 16), "Rosa");
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_AXE, 1);
		logs(h, 18, 18, 4);
		logs(h, 20, 16, 4);
		logs(h, 16, 20, 4);
		OrderService.createGather(h.getLevel(), c, "minecraft:oak_log", GatherMode.DROPS, 2, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		int[] removals = {0};
		boolean[] wasReturning = {false};
		h.onEachTick(() -> {
			boolean returning = order.status() == OrderStatus.RETURNING;
			if (returning && !wasReturning[0] && c.inventory().count(Items.OAK_LOG) > 0) {
				removals[0]++;
				c.inventory().removeRaw(Items.OAK_LOG, 64);
			}
			wasReturning[0] = returning;
		});
		h.succeedWhen(() -> {
			h.assertTrue(order.status().isFinished(), "ordem ainda em " + order.status());
			h.assertTrue(order.status() == OrderStatus.FAILED, "a ordem terminou como " + order.status() + " sem ter entregado nada");
			h.assertValueEqual(TestSupport.count(owner, Items.OAK_LOG), 0, "troncos entregues");
			h.assertTrue(removals[0] == 3, "tentativas de entrega: " + removals[0] + " (esperado: a primeira e mais 2)");
			h.assertTrue(order.reason() != null, "falhou sem motivo registrado");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Com fome (não crítica), ela não come a carne crua que trouxe para assar: assa e entrega. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3000)
	public void rawMeatForCookingIsNotEaten(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(4, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa");
		c.settings().debug = true;
		TestSupport.give(c, Items.MUTTON, 2);
		TestSupport.give(c, Items.FURNACE, 1);
		TestSupport.give(c, Items.COAL, 2);
		c.needs().setHunger(10);
		var refusal = OrderService.createMeatOrder(h.getLevel(), c, true, 2);
		h.assertTrue(refusal == null, "pedido recusado");
		Order order = c.orders().all().getFirst();
		boolean[] ate = {false};
		h.onEachTick(() -> {
			if (!order.status().isFinished() && c.needs().hunger() > 10) {
				ate[0] = true;
			}
		});
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() == null ? "" : ": " + order.reason().getString()));
			h.assertValueEqual(TestSupport.count(owner, Items.COOKED_MUTTON), 2, "carne assada entregue");
			h.assertTrue(!ate[0], "comeu a carne crua do pedido durante a missão");
			TestSupport.cleanup(h, owner);
		});
	}
	/** Um hostil aparece no meio da coleta: ela luta e retoma a mesma missão, sem recomeçar nem perder o que já tinha. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 4000)
	public void resumesTheSameMissionAfterAFight(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(14, FLOOR, 14), "Rosa");
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_AXE, 1);
		TestSupport.give(c, Items.IRON_SWORD, 1);
		logs(h, 18, 18, 4);
		logs(h, 20, 14, 4);
		OrderService.createGather(h.getLevel(), c, "minecraft:oak_log", GatherMode.DROPS, 6, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		java.util.UUID id = order.id();
		net.minecraft.world.entity.monster.zombie.Husk[] husk = {null};
		int[] minedBefore = {0};
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(order.progress().blocksMined >= 2, "ainda não começou a cortar"))
			.thenExecute(() -> {
				minedBefore[0] = order.progress().blocksMined;
				husk[0] = h.spawn(net.minecraft.world.entity.EntityTypes.HUSK, new BlockPos(10, FLOOR, 18), net.minecraft.world.entity.EntitySpawnReason.EVENT);
				husk[0].setTarget(c);
			})
			.thenWaitUntil(() -> h.assertTrue(!husk[0].isAlive(), "o hostil continua vivo"))
			.thenWaitUntil(() -> h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status()))
			.thenExecute(() -> {
				h.assertTrue(c.orders().all().getFirst().id().equals(id), "a ordem foi trocada por outra");
				h.assertValueEqual(TestSupport.count(owner, Items.OAK_LOG), 6, "troncos entregues");
				h.assertTrue(c.brain().metrics().orderInterruptions() >= 1, "a luta não interrompeu a ordem (o teste não exercitou a retomada)");
				// Retomou de onde parou: não cortou a árvore toda de novo (8 troncos no total disponíveis, 6 pedidos).
				h.assertTrue(order.progress().blocksMined <= 8, "recomeçou do zero: " + order.progress().blocksMined + " cortes");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/** Nova ordem com outra em andamento: a primeira não é substituída; ela termina a primeira e depois faz a segunda. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 4000)
	public void newOrderDoesNotReplaceTheCurrentOne(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(14, FLOOR, 14), "Rosa");
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_AXE, 1);
		TestSupport.give(c, Items.STONE_SHOVEL, 1);
		logs(h, 18, 18, 4);
		logs(h, 20, 14, 4);
		OrderService.createGather(h.getLevel(), c, "minecraft:oak_log", GatherMode.DROPS, 4, SearchProfile.COMMON, 0, false);
		Order first = c.orders().all().getFirst();
		Order[] second = {null};
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(first.progress().blocksMined >= 1, "ainda não começou"))
			.thenExecute(() -> {
				OrderService.createGather(h.getLevel(), c, "minecraft:dirt", GatherMode.DROPS, 3, SearchProfile.COMMON, 0, false);
				second[0] = c.orders().all().get(1);
				h.assertTrue(c.orders().active() == first, "a ordem nova tomou o lugar da que estava em andamento");
			})
			.thenWaitUntil(() -> h.assertTrue(first.status() == OrderStatus.COMPLETED && second[0].status() == OrderStatus.COMPLETED,
				"ordens " + first.status() + " / " + second[0].status()))
			.thenExecute(() -> {
				h.assertValueEqual(TestSupport.count(owner, Items.OAK_LOG), 4, "troncos entregues");
				h.assertValueEqual(TestSupport.count(owner, Items.DIRT), 3, "terra entregue");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}
}
