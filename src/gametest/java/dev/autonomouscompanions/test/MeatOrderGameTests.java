package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderService;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * Pedidos de carne (regressão prioritária): alvos válidos e próximos, quantidade = itens entregues (não animais mortos),
 * parar ao atingir a quantidade, voltar e entregar, nada de mineração no caminho, falha honesta quando faltam animais,
 * e continuidade com bolsa cheia, sem arma, com hostil e depois de salvar/recarregar.
 */
public class MeatOrderGameTests {
	// Cada teste de caça roda num lote próprio (ac_hunt1..7): a busca de presas vai até 96 blocos e, no mesmo lote, os
	// testes ficam a 30-40 blocos uns dos outros — uma caçadora abatia os animais das arenas vizinhas (artefato do teste).
	private static final String ARENA = "autonomouscompanions-test:arena";

	private static List<Mob> spawn(GameTestHelper h, EntityType<? extends Mob> type, int... xz) {
		List<Mob> list = new ArrayList<>();
		for (int i = 0; i < xz.length; i += 2) {
			list.add(h.spawn(type, new BlockPos(xz[i], FLOOR, xz[i + 1]), EntitySpawnReason.EVENT));
		}
		return list;
	}

	private static int delivered(ServerPlayer owner, java.util.Set<Item> items) {
		int n = 0;
		for (Item i : items) {
			n += TestSupport.count(owner, i);
		}
		return n;
	}

	private static long alive(List<Mob> mobs) {
		return mobs.stream().filter(Mob::isAlive).count();
	}

	/** Carne genérica: ovelhas perto, vacas e porcos longe → caça as de perto, entrega 3, sem matar à toa e sem minerar. */
	@GameTest(environment = "autonomouscompanions-test:ac_hunt1", structure = ARENA, maxTicks = 3000)
	public void genericMeatUsesTheNearestAnimalsAndStops(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_SWORD, 1);
		List<Mob> sheep = spawn(h, EntityTypes.SHEEP, 9, 9, 10, 9, 9, 10);
		List<Mob> cows = spawn(h, EntityTypes.COW, 21, 21, 20, 21);
		List<Mob> pigs = spawn(h, EntityTypes.PIG, 21, 3, 20, 3);
		// Os de longe ficam parados (sem IA, mas caçáveis). Mudança no teste em 18/10/2026: numa rodada da bateria um porco
		// andou até 6 blocos dela e ela o caçou — o mais perto naquele momento, que é o comportamento pedido; o teste
		// presumia que eles ficariam longe. Parados, o teste mede de fato a preferência pelos animais próximos.
		cows.forEach(m -> m.setNoAi(true));
		pigs.forEach(m -> m.setNoAi(true));
		ServerNetworking.handleItemOrder(owner, new Payloads.StartItemOrder(c.getId(), OrderService.NEAREST_MEAT, 3));
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() == null ? "" : ": " + order.reason().getString()));
			h.assertValueEqual(delivered(owner, Order.ANY_MEATS), 3, "carne entregue");
			h.assertValueEqual(order.progress().blocksMined, 0, "blocos minerados durante a caça");
			long killed = (3 - alive(sheep)) + (2 - alive(cows)) + (2 - alive(pigs));
			h.assertTrue(killed <= 3, "matou animais demais para 3 carnes: " + killed);
			h.assertValueEqual(alive(cows) + alive(pigs), 4L, "foi atrás dos animais longe com ovelhas ao lado");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Carne específica (carne de vaca): entrega só carne de vaca, mesmo com ovelhas mais perto. */
	@GameTest(environment = "autonomouscompanions-test:ac_hunt2", structure = ARENA, maxTicks = 3000)
	public void specificMeatDeliversOnlyThatMeat(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_SWORD, 1);
		List<Mob> sheep = spawn(h, EntityTypes.SHEEP, 9, 9, 10, 9);
		spawn(h, EntityTypes.COW, 18, 18, 19, 18, 18, 19);
		var refusal = OrderService.createItemOrder(h.getLevel(), c, "minecraft:beef", 3);
		h.assertTrue(refusal == null, "pedido recusado");
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status());
			h.assertValueEqual(TestSupport.count(owner, Items.BEEF), 3, "carne de vaca entregue");
			h.assertValueEqual(TestSupport.count(owner, Items.MUTTON), 0, "entregou carne de outro tipo");
			h.assertValueEqual(alive(sheep), 2L, "matou ovelhas num pedido de carne de vaca");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Mais carne do que os animais disponíveis: entrega o que conseguiu e a ordem termina como falha, com motivo. */
	@GameTest(environment = "autonomouscompanions-test:ac_hunt3", structure = ARENA, maxTicks = 12000)
	public void moreMeatThanAvailableFailsHonestly(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_SWORD, 1);
		spawn(h, EntityTypes.SHEEP, 10, 10, 11, 10);
		ServerNetworking.handleItemOrder(owner, new Payloads.StartItemOrder(c.getId(), OrderService.NEAREST_MEAT, 12));
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status().isFinished(), "ordem ainda em " + order.status());
			h.assertTrue(order.status() == OrderStatus.FAILED, "terminou como " + order.status() + " sem ter as 12 carnes");
			h.assertTrue(order.reason() != null, "falhou sem motivo");
			int got = delivered(owner, Order.ANY_MEATS);
			h.assertTrue(got >= 2 && got < 12, "entregou " + got + " (esperado: o que as 2 ovelhas deram)");
			h.assertValueEqual(order.progress().delivered, got, "registro de entregues");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Sem arma e com a bolsa quase cheia (33 de 36 espaços ocupados por itens que não são lixo): completa e entrega. */
	@GameTest(environment = "autonomouscompanions-test:ac_hunt4", structure = ARENA, maxTicks = 3000)
	public void meatWithoutWeaponAndCrowdedInventory(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa");
		c.settings().debug = true;
		for (int i = 0; i < 33; i++) {
			c.inventory().setItem(i, new ItemStack(Items.IRON_INGOT, 64));
		}
		spawn(h, EntityTypes.SHEEP, 9, 9, 10, 9, 9, 10);
		ServerNetworking.handleItemOrder(owner, new Payloads.StartItemOrder(c.getId(), OrderService.NEAREST_MEAT, 2));
		Order order = c.orders().all().getFirst();
		int[] iron = {33 * 64};
		h.onEachTick(() -> {
			int now = c.inventory().count(Items.IRON_INGOT);
			if (now != iron[0]) {
				c.debug("[teste] ferro mudou de " + iron[0] + " para " + now + " no estado " + c.companionState() + " (" + c.stateDetail().getString() + "), mão "
					+ c.mainHandItem() + ", etapas " + c.brain().orders().describeStack().stream().map(x -> x.getString()).toList());
				iron[0] = now;
			}
		});
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() == null ? "" : ": " + order.reason().getString()));
			h.assertValueEqual(delivered(owner, Order.ANY_MEATS), 2, "carne entregue");
			h.assertValueEqual(c.inventory().count(Items.IRON_INGOT), 33 * 64, "mexeu nos itens dela (descartou ou perdeu)");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Salvar e recarregar no meio da caça: a mesma ordem continua e termina com a entrega completa. */
	@GameTest(environment = "autonomouscompanions-test:ac_hunt5", structure = ARENA, maxTicks = 4000)
	public void meatOrderSurvivesReload(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity[] c = {TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa")};
		c[0].settings().debug = true;
		TestSupport.give(c[0], Items.IRON_SWORD, 1);
		List<Mob> sheep = spawn(h, EntityTypes.SHEEP, 10, 10, 11, 10, 10, 11, 12, 12);
		ServerNetworking.handleItemOrder(owner, new Payloads.StartItemOrder(c[0].getId(), OrderService.NEAREST_MEAT, 4));
		java.util.UUID id = c[0].orders().all().getFirst().id();
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(alive(sheep) <= 3, "ainda não caçou nenhuma"))
			.thenExecute(() -> c[0] = TestSupport.reload(h, c[0]))
			.thenWaitUntil(() -> {
				Order o = c[0].orders().all().getFirst();
				h.assertTrue(o.id().equals(id), "a ordem mudou de identidade ao recarregar");
				h.assertTrue(o.status() == OrderStatus.COMPLETED, "ordem " + o.status());
			})
			.thenExecute(() -> {
				h.assertValueEqual(delivered(owner, Order.ANY_MEATS), 4, "carne entregue (sem duplicar nem perder)");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/** Um hostil aparece no meio da caça: ela o enfrenta e termina a mesma ordem de carne. */
	@GameTest(environment = "autonomouscompanions-test:ac_hunt6", structure = ARENA, maxTicks = 4000)
	public void meatOrderContinuesAfterAThreat(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_SWORD, 1);
		spawn(h, EntityTypes.SHEEP, 16, 16, 17, 16, 16, 17);
		ServerNetworking.handleItemOrder(owner, new Payloads.StartItemOrder(c.getId(), OrderService.NEAREST_MEAT, 3));
		Order order = c.orders().all().getFirst();
		Mob husk = h.spawn(EntityTypes.HUSK, new BlockPos(12, FLOOR, 4), EntitySpawnReason.EVENT);
		husk.setTarget(c);
		h.succeedWhen(() -> {
			h.assertTrue(!husk.isAlive(), "o hostil continua vivo");
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status());
			h.assertValueEqual(delivered(owner, Order.ANY_MEATS), 3, "carne entregue");
			TestSupport.cleanup(h, owner);
		});
	}

	/**
	 * Item do pedido caído no bloco vizinho, mas a 1,8 bloco dela (ela fora do centro do próprio bloco, como depois de uma
	 * luta), sem nenhum animal por perto: ela dá o passo e pega. Antes o caminho do jogo (precisão de 1 bloco) já contava
	 * "chegou" no bloco vizinho e ela ficava parada fora do alcance de pegar — causa das falhas intermitentes da cama
	 * branca ("nenhum animal para lã branca") e da carne depois de recarregar, nas baterias de repetição.
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_hunt8", structure = ARENA, maxTicks = 1200)
	public void picksUpTheItemOnTheNextBlock(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(6, FLOOR, 1), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa");
		c.settings().debug = true;
		c.settings().idleExplore = false;
		net.minecraft.world.phys.Vec3 at = h.absoluteVec(new net.minecraft.world.phys.Vec3(6.5, FLOOR, 6.9));
		c.teleportTo(at.x, at.y, at.z);
		h.spawnItem(Items.MUTTON, new net.minecraft.world.phys.Vec3(6.5, FLOOR, 5.1));
		OrderService.createItemOrder(h.getLevel(), c, "minecraft:mutton", 1);
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() == null ? "" : ": " + order.reason().getString())
				+ ", andou " + (int) order.progress().distance);
			h.assertValueEqual(TestSupport.count(owner, Items.MUTTON), 1, "carne entregue");
			h.assertTrue(order.progress().distance < 30, "saiu procurando longe com o item ao lado: andou " + (int) order.progress().distance);
			TestSupport.cleanup(h, owner);
		});
	}
}
