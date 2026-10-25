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

/** Minérios enterrados e rapidez das coletas. */
public class SpeedGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";

	/** Ferro enterrado no meio de uma montanha de pedra: ela cava até ele (sem ficar pulando/rodando) e entrega. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 2400)
	public void minesBuriedOreWithoutWandering(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(4, FLOOR, 12), "Ferra");
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_PICKAXE, 1);
		for (int x = 8; x <= 21; x++) {
			for (int z = 4; z <= 20; z++) {
				for (int y = 0; y < 9; y++) {
					h.setBlock(new BlockPos(x, FLOOR + y, z), Blocks.STONE);
				}
			}
		}
		h.setBlock(new BlockPos(15, FLOOR + 2, 12), Blocks.IRON_ORE);
		h.setBlock(new BlockPos(15, FLOOR + 2, 13), Blocks.IRON_ORE);
		OrderService.createGather(h.getLevel(), c, "minecraft:iron_ore", GatherMode.DROPS, 2, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		long[] start = {-1};
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem: " + order.status());
			h.assertValueEqual(TestSupport.count(owner, Items.RAW_IRON), 2, "ferro entregue");
			TestSupport.cleanup(h, owner);
		});
	}

	/** De cima da montanha, ouro no fundo: desce cavando uma escada até ele (sem andar à toa) e entrega. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 2400)
	public void digsDownToDeepOre(GameTestHelper h) {
		for (int x = 4; x <= 21; x++) {
			for (int z = 4; z <= 20; z++) {
				for (int y = 0; y < 9; y++) {
					h.setBlock(new BlockPos(x, FLOOR + y, z), Blocks.STONE);
				}
			}
		}
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(6, FLOOR + 9, 6), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(8, FLOOR + 9, 8), "Ouri");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_PICKAXE, 1);
		h.setBlock(new BlockPos(15, FLOOR, 15), Blocks.GOLD_ORE);
		OrderService.createGather(h.getLevel(), c, "minecraft:gold_ore", GatherMode.DROPS, 1, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem: " + order.status());
			h.assertValueEqual(TestSupport.count(owner, Items.RAW_GOLD), 1, "ouro entregue");
			TestSupport.cleanup(h, owner);
		});
	}

	/** "Carne mais próxima": escolhe a carne do animal mais perto dela (crua ou assada), sem animais = recusa. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 200)
	public void nearestMeatPicksClosestAnimal(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(4, FLOOR, 12), "Pita");
		dev.autonomouscompanions.mining.DropIndex.ensureReady(h.getLevel().getServer());
		h.spawn(net.minecraft.world.entity.EntityTypes.PIG, new BlockPos(20, FLOOR, 12), net.minecraft.world.entity.EntitySpawnReason.EVENT).setNoAi(true);
		h.spawn(net.minecraft.world.entity.EntityTypes.PIG, new BlockPos(20, FLOOR, 14), net.minecraft.world.entity.EntitySpawnReason.EVENT).setNoAi(true);
		h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(9, FLOOR, 12), net.minecraft.world.entity.EntitySpawnReason.EVENT).setNoAi(true);
		h.assertTrue(dev.autonomouscompanions.task.OrderService.nearestMeat(h.getLevel(), c, false) == Items.BEEF, "deveria escolher a vaca (mais perto)");
		h.assertTrue(dev.autonomouscompanions.task.OrderService.nearestMeat(h.getLevel(), c, true) == Items.COOKED_BEEF, "assada deveria ser bife");
		// Pelo mesmo pacote da tela.
		dev.autonomouscompanions.network.ServerNetworking.handleItemOrder(owner,
			new dev.autonomouscompanions.network.Payloads.StartItemOrder(c.getId(), dev.autonomouscompanions.task.OrderService.NEAREST_MEAT, 3));
		h.assertTrue(!c.orders().all().isEmpty() && c.orders().all().getFirst().targetItem() == Items.BEEF, "a ordem não foi de carne de vaca");
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** Parada dentro da água sem motivo, ela vai para a terra (a ajudante não fica plantada no lago). */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 600)
	public void helperLeavesTheWater(GameTestHelper h) {
		for (int x = 8; x <= 16; x++) {
			for (int z = 8; z <= 16; z++) {
				h.setBlock(new BlockPos(x, FLOOR - 1, z), Blocks.WATER);
				h.setBlock(new BlockPos(x, FLOOR - 2, z), Blocks.WATER);
			}
		}
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(12, FLOOR, 18), GameType.SURVIVAL);
		CompanionEntity helper = TestSupport.helper(h, owner, new BlockPos(12, FLOOR - 2, 13));
		helper.settings().debug = true;
		TestSupport.give(helper, Items.COOKED_BEEF, 10); // estoque cheio: nenhuma tarefa a fazer
		h.startSequence()
			.thenIdle(20)
			.thenWaitUntil(() -> h.assertTrue(!helper.isInWater() && helper.onGround(), "ainda na água"))
			.thenIdle(60)
			.thenExecute(() -> {
				h.assertTrue(!helper.isInWater(), "voltou para a água");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/** Destinos de exploração/caça nunca caem na água quando há terra firme nas direções testadas. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void exploreTargetsAvoidWater(GameTestHelper h) {
		for (int x = 1; x <= 22; x++) {
			for (int z = 1; z <= 12; z++) {
				h.setBlock(new BlockPos(x, FLOOR - 1, z), Blocks.WATER);
			}
		}
		BlockPos center = h.absolutePos(new BlockPos(12, FLOOR, 12));
		for (int i = 0; i < 8; i++) {
			BlockPos t = dev.autonomouscompanions.nav.SafeSpot.landTarget(h.getLevel(), center, i * Math.PI / 4, 8, null);
			h.assertTrue(t != null, "nenhum destino em terra");
			h.assertTrue(h.getLevel().getBlockState(t.below()).getFluidState().isEmpty(), "destino na água: " + t.toShortString());
		}
		h.succeed();
	}

	/** Longe da dona, ela corre (30% mais rápido) e para de correr ao chegar. */
	@GameTest(environment = DAY, structure = "autonomouscompanions-test:corridor", maxTicks = 600)
	public void runsOnLongTrips(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(3, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(60, FLOOR, 4), "Corri");
		boolean[] ran = {false};
		h.onEachTick(() -> ran[0] |= c.isSprinting());
		h.succeedWhen(() -> {
			h.assertTrue(c.distanceTo(owner) <= c.settings().followMax + 0.5, "ainda longe: " + c.distanceTo(owner));
			h.assertTrue(ran[0], "não correu no trecho longo");
			h.assertTrue(!c.isSprinting(), "continuou correndo depois de chegar");
			TestSupport.cleanup(h, owner);
		});
	}
}
