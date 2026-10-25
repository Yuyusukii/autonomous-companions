package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.registry.ModItems;
import dev.autonomouscompanions.spawn.StarterSpawner;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.OrderService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Apito, divisão do trabalho, ferramenta antes da coleta, retorno automático, dar itens e fuga de buracos. */
public class AssistGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";
	private static final String CORRIDOR = "autonomouscompanions-test:corridor";

	/** O apito é entregue uma única vez por jogadora, mesmo que a entrega seja chamada de novo (ex.: ao reentrar). */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void whistleIsGivenOnlyOnce(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(10, FLOOR, 10), GameType.SURVIVAL);
		// Ao entrar no mundo ela já recebe o apito; novas tentativas (ex.: ao reentrar) não duplicam.
		h.assertValueEqual(TestSupport.count(owner, ModItems.WHISTLE), 1, "apito ao entrar no mundo");
		StarterSpawner.giveWhistleOnce(owner);
		StarterSpawner.onPlayerJoin(owner);
		h.assertValueEqual(TestSupport.count(owner, ModItems.WHISTLE), 1, "apitos no inventário");
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** O apito cancela a ordem em andamento e ela corre até a dona. */
	@GameTest(environment = DAY, structure = CORRIDOR, maxTicks = 900)
	public void whistleSuspendsOrderAndBringsHer(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(3, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(40, FLOOR, 4), "Vera");
		c.settings().debug = true;
		TestSupport.give(c, Items.WOODEN_PICKAXE, 1);
		for (int x = 62; x <= 66; x++) {
			h.setBlock(new BlockPos(x, FLOOR, 4), Blocks.STONE);
		}
		OrderService.createGather(h.getLevel(), c, "minecraft:stone", GatherMode.DROPS, 30, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.WHISTLE));
		long[] whistledAt = {-1};
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(order.status() == OrderStatus.ACTIVE && c.getX() > h.absolutePos(new BlockPos(44, 0, 0)).getX(),
				"ainda não começou a ordem: " + order.status()))
			.thenExecute(() -> {
				// Shift + apito: chama todas direto (sem abrir o menu).
				owner.setShiftKeyDown(true);
				InteractionResult r = ModItems.WHISTLE.use(h.getLevel(), owner, InteractionHand.MAIN_HAND);
				owner.setShiftKeyDown(false);
				h.assertTrue(r.consumesAction(), "o apito não foi usado: " + r);
				// Suspensa (não cancelada): o pedido e o que ela já juntou continuam reservados para retomar depois.
				h.assertTrue(order.status() == OrderStatus.SUSPENDED, "a ordem não foi suspensa: " + order.status());
				h.assertTrue(c.brain().isRecalled(h.getLevel()), "não ficou em modo de chamada");
				whistledAt[0] = h.getTick();
			})
			.thenWaitUntil(() -> h.assertTrue(c.distanceTo(owner) <= 4.0, "ainda longe: " + c.distanceTo(owner)))
			.thenExecute(() -> {
				h.assertTrue(h.getTick() - whistledAt[0] < 400, "demorou demais para chegar: " + (h.getTick() - whistledAt[0]) + " ticks");
				h.assertValueEqual(TestSupport.count(owner, Items.COBBLESTONE), 0, "entregou itens de uma ordem cancelada");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/** Mesma ordem de troncos para duas companheiras: cada uma vai a uma árvore diferente. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3000)
	public void sameOrderUsesDifferentTrees(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(12, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity a = TestSupport.companion(h, owner, new BlockPos(11, FLOOR, 13), "Ana");
		CompanionEntity b = TestSupport.companion(h, owner, new BlockPos(13, FLOOR, 13), "Bia");
		a.settings().debug = true;
		b.settings().debug = true;
		TestSupport.give(a, Items.WOODEN_AXE, 1);
		TestSupport.give(b, Items.WOODEN_AXE, 1);
		List<List<BlockPos>> trees = List.of(new ArrayList<>(), new ArrayList<>());
		BlockPos[] bases = {new BlockPos(5, FLOOR, 18), new BlockPos(19, FLOOR, 18)};
		for (int t = 0; t < 2; t++) {
			for (int y = 0; y < 4; y++) {
				BlockPos p = bases[t].above(y);
				h.setBlock(p, Blocks.OAK_LOG);
				trees.get(t).add(p);
			}
		}
		OrderService.createGather(h.getLevel(), a, "minecraft:oak_log", GatherMode.DROPS, 4, SearchProfile.COMMON, 0, false);
		OrderService.createGather(h.getLevel(), b, "minecraft:oak_log", GatherMode.DROPS, 4, SearchProfile.COMMON, 0, false);
		Order oa = a.orders().all().getFirst();
		Order ob = b.orders().all().getFirst();
		// Quem estava mais perto de cada tronco no momento em que ele sumiu.
		Map<BlockPos, UUID> cutBy = new HashMap<>();
		h.onEachTick(() -> {
			for (List<BlockPos> tree : trees) {
				for (BlockPos p : tree) {
					if (!cutBy.containsKey(p) && h.getBlockState(p).isAir()) {
						BlockPos abs = h.absolutePos(p);
						CompanionEntity near = a.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(abs)) <= b.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(abs)) ? a : b;
						cutBy.put(p, near.getUUID());
					}
				}
			}
		});
		h.succeedWhen(() -> {
			h.assertTrue(oa.status() == OrderStatus.COMPLETED && ob.status() == OrderStatus.COMPLETED, "ordens: " + oa.status() + " / " + ob.status());
			h.assertValueEqual(TestSupport.count(owner, Items.OAK_LOG), 8, "troncos entregues");
			for (int t = 0; t < 2; t++) {
				Set<UUID> who = new HashSet<>();
				for (BlockPos p : trees.get(t)) {
					who.add(cutBy.get(p));
				}
				h.assertTrue(who.size() == 1 && !who.contains(null), "a árvore " + t + " foi cortada por mais de uma companheira: " + who.size());
			}
			h.assertTrue(!cutBy.get(trees.get(0).getFirst()).equals(cutBy.get(trees.get(1).getFirst())), "uma só cortou as duas árvores");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Pedido de troncos sem nenhuma ferramenta: ela primeiro faz um machado e depois coleta. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 4000)
	public void craftsAxeBeforeCollectingLogs(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(8, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 12), "Iris");
		c.settings().debug = true;
		for (BlockPos base : new BlockPos[]{new BlockPos(16, FLOOR, 8), new BlockPos(16, FLOOR, 16), new BlockPos(19, FLOOR, 12)}) {
			for (int y = 0; y < 4; y++) {
				h.setBlock(base.above(y), Blocks.OAK_LOG);
			}
		}
		OrderService.createGather(h.getLevel(), c, "minecraft:oak_log", GatherMode.DROPS, 4, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem: " + order.status());
			h.assertValueEqual(TestSupport.count(owner, Items.OAK_LOG), 4, "troncos entregues");
			h.assertTrue(Gear.countOwned(c, s -> s.is(Items.WOODEN_AXE)) == 1, "ela não fez o machado");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Depois de uma ordem, volta para perto da dona antes de retomar o trabalho da função. */
	@GameTest(environment = DAY, structure = CORRIDOR, maxTicks = 900)
	public void returnsToOwnerAfterOrder(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(3, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(30, FLOOR, 4), "Rita");
		c.settings().debug = true;
		c.settings().roleWork = true;
		ServerNetworking.handleRole(owner, new Payloads.SetRole(c.getId(), dev.autonomouscompanions.entity.CompanionRole.LUMBERJACK.ordinal()));
		TestSupport.give(c, Items.WOODEN_AXE, 1);
		for (int y = 0; y < 4; y++) {
			h.setBlock(new BlockPos(33, FLOOR + y, 4), Blocks.OAK_LOG);
		}
		// O mesmo caminho usado ao concluir ou falhar uma ordem (OrderBehavior.complete/fail).
		c.brain().markReturnAfterOrder();
		h.succeedWhen(() -> {
			h.assertTrue(c.distanceTo(owner) <= c.settings().followMax + 0.5, "ainda longe: " + c.distanceTo(owner));
			h.assertValueEqual(c.inventory().count(Items.OAK_LOG), 0, "foi cortar a árvore da função antes de voltar");
			h.assertTrue(!c.brain().isReturningAfterOrder(), "o retorno não terminou ao chegar");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Dar itens: quantidade exata, qualquer espaço (inclusive a mão secundária), sem duplicar nem perder. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void givesExactAmountsWithoutDuplicating(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(10, FLOOR, 10), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(11, FLOOR, 10), "Dora");
		Inventory inv = owner.getInventory();
		inv.setItem(3, new ItemStack(Items.COBBLESTONE, 40));
		inv.setItem(20, new ItemStack(Items.DIAMOND, 7));
		inv.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.BREAD, 10));
		inv.setItem(21, new ItemStack(Items.EMERALD, 5));
		h.assertValueEqual(ServerNetworking.giveFromPlayer(owner, c, 3, 25), 25, "pedregulho movido");
		h.assertValueEqual(TestSupport.count(owner, Items.COBBLESTONE), 15, "pedregulho restante com a dona");
		h.assertValueEqual(c.inventory().count(Items.COBBLESTONE), 25, "pedregulho com a companheira");
		h.assertValueEqual(ServerNetworking.giveFromPlayer(owner, c, 3, 999), 15, "resto da pilha");
		h.assertTrue(inv.getItem(3).isEmpty(), "o espaço deveria ficar vazio");
		// Pelo pacote da interface (validado no servidor).
		ServerNetworking.handleGiveItems(owner, new Payloads.GiveItems(c.getId(), 20, 3));
		h.assertValueEqual(c.inventory().count(Items.DIAMOND), 3, "diamantes recebidos pelo pacote");
		h.assertValueEqual(inv.getItem(20).getCount(), 4, "diamantes restantes");
		h.assertValueEqual(ServerNetworking.giveFromPlayer(owner, c, Inventory.SLOT_OFFHAND, 4), 4, "mão secundária");
		h.assertValueEqual(c.inventory().count(Items.BREAD), 4, "pão recebido");
		h.assertValueEqual(ServerNetworking.giveFromPlayer(owner, c, 99, 5), 0, "espaço inválido aceito");
		h.assertValueEqual(ServerNetworking.giveFromPlayer(owner, c, 20, 0), 0, "quantidade zero aceita");
		// Companheira cheia (sem pilha de esmeralda para completar): nada sai da dona.
		while (c.inventory().spaceFor(new ItemStack(Items.DIRT)) > 0) {
			c.inventory().insert(new ItemStack(Items.DIRT, 64));
		}
		h.assertTrue(c.inventory().spaceFor(new ItemStack(Items.EMERALD)) == 0, "ainda há espaço para esmeralda");
		h.assertValueEqual(ServerNetworking.giveFromPlayer(owner, c, 21, 4), 0, "deu itens para companheira cheia");
		h.assertValueEqual(inv.getItem(21).getCount(), 5, "esmeraldas perdidas");
		h.assertValueEqual(c.inventory().count(Items.DIAMOND) + inv.getItem(20).getCount(), 7, "total de diamantes");
		h.assertValueEqual(TestSupport.itemEntities(h, Items.EMERALD), 0, "itens largados no chão");
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** Buraco de 3 blocos sem nada no inventário: cava degraus e sai sozinha (sem teletransporte). */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 900)
	public void digsOutOfAHole(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(3, FLOOR, 3), GameType.SURVIVAL);
		BlockPos pit = new BlockPos(14, FLOOR, 14);
		buildPit(h, pit);
		CompanionEntity c = TestSupport.companion(h, owner, pit, "Lara");
		c.settings().debug = true;
		h.assertValueEqual(c.inventory().count(s -> !s.isEmpty()), 0, "inventário inicial deveria estar vazio");
		h.succeedWhen(() -> {
			h.assertTrue(c.distanceTo(owner) <= c.settings().followMax + 1, "ainda presa ou longe: " + c.distanceTo(owner));
			h.assertTrue(brokenWalls(h, pit) > 0, "nenhum bloco do buraco foi cavado (saiu como?)");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Buraco de 3 blocos com terra no inventário: sobe um pilar e sai sozinha. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 900)
	public void pillarsOutOfAHole(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(3, FLOOR, 3), GameType.SURVIVAL);
		BlockPos pit = new BlockPos(14, FLOOR, 14);
		buildPit(h, pit);
		CompanionEntity c = TestSupport.companion(h, owner, pit, "Nara");
		c.settings().debug = true;
		TestSupport.give(c, Items.DIRT, 8);
		h.succeedWhen(() -> {
			h.assertTrue(c.distanceTo(owner) <= c.settings().followMax + 1, "ainda presa ou longe: " + c.distanceTo(owner));
			h.assertTrue(c.inventory().count(Items.DIRT) < 8 || brokenWalls(h, pit) > 0, "não usou pilar nem cavou (saiu como?)");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Poço de 1×1 com paredes de terra de 3 blocos de altura ao redor de {@code center}. */
	private static void buildPit(GameTestHelper h, BlockPos center) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				for (int y = 0; y < 3; y++) {
					h.setBlock(center.offset(dx, y, dz), Blocks.DIRT);
				}
			}
		}
	}

	private static int brokenWalls(GameTestHelper h, BlockPos center) {
		int n = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				for (int y = 0; y < 3; y++) {
					if (!h.getBlockState(center.offset(dx, y, dz)).is(Blocks.DIRT)) {
						n++;
					}
				}
			}
		}
		return n;
	}
}
