package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.OrderService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Escavação só com plano válido: pedra nunca é cavada com a mão. */
public class NavigationSafetyGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";

	/**
	 * Alvo (terra grossa) dentro de uma caixa de pedra, sem árvores na arena (ela não tem como fazer picareta): a pedra
	 * fica intacta — antes o túnel até o alvo quebrava pedra com a mão.
	 */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3000)
	public void neverDigsStoneByHand(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 10), "Rosa");
		c.settings().debug = true;
		int stones = 0;
		for (int x = 15; x <= 19; x++) {
			for (int z = 15; z <= 19; z++) {
				for (int y = FLOOR; y <= FLOOR + 2; y++) {
					h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
					stones++;
				}
			}
		}
		h.setBlock(new BlockPos(17, FLOOR + 1, 17), Blocks.COARSE_DIRT);
		int total = stones - 1;
		OrderService.createGather(h.getLevel(), c, "minecraft:coarse_dirt", GatherMode.EXACT_BLOCK, 1, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		h.startSequence()
			.thenIdle(2400)
			.thenExecute(() -> {
				int left = 0;
				for (int x = 15; x <= 19; x++) {
					for (int z = 15; z <= 19; z++) {
						for (int y = FLOOR; y <= FLOOR + 2; y++) {
							if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.STONE)) {
								left++;
							}
						}
					}
				}
				h.assertValueEqual(left, total, "blocos de pedra intactos (estado da ordem: " + order.status() + ")");
				h.assertTrue(h.getBlockState(new BlockPos(17, FLOOR + 1, 17)).is(Blocks.COARSE_DIRT), "o alvo foi tirado sem cavar a pedra?");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/**
	 * Dentro de um poço de neve fofa de 2 blocos (como os bolsões das encostas nevadas): ela quebra a neve que a prende e
	 * não congela. Antes a navegação não saía da neve fofa (bloco proibido no caminho) e ela ficava parada até morrer —
	 * três mortes "congelou até a morte" numa sessão de jogo da jogadora (18/10/2026).
	 */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 1600)
	public void getsOutOfPowderSnowBeforeFreezing(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		for (int x = 8; x <= 14; x++) {
			for (int z = 8; z <= 14; z++) {
				for (int y = FLOOR; y <= FLOOR + 1; y++) {
					boolean pit = x >= 10 && x <= 12 && z >= 10 && z <= 12;
					h.setBlock(new BlockPos(x, y, z), pit ? Blocks.POWDER_SNOW : Blocks.DIRT);
				}
			}
		}
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(11, FLOOR, 11), "Rosa");
		c.settings().debug = true;
		c.settings().idleExplore = false;
		float[] lowest = {c.getHealth()};
		h.onEachTick(() -> lowest[0] = Math.min(lowest[0], c.getHealth()));
		h.startSequence()
			.thenIdle(400)
			.thenWaitUntil(() -> {
				h.assertTrue(c.isAlive(), "morreu na neve fofa");
				h.assertTrue(!c.isInPowderSnow, "continua dentro da neve fofa (congelamento " + c.getTicksFrozen() + ", vida " + c.getHealth() + ")");
				h.assertValueEqual(c.getTicksFrozen(), 0, "congelamento");
			})
			.thenExecute(() -> {
				h.assertTrue(lowest[0] >= c.getMaxHealth() - 2, "perdeu vida congelando: menor vida " + lowest[0]);
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/**
	 * Entrega sem caminho a pé até a dona, sem ficar parada: túnel de pedra fechado de 16 blocos, perpendicular à direção
	 * da dona. Os desvios do navegador a fazem andar de um lado para o outro do túnel (o detector de "presa" exige ficar
	 * parada), e antes ela recomeçava o mesmo caminho impossível para sempre — como na falha intermitente da fornalha em
	 * terreno real (ordem parada em RETURNING depois de andar 1713 blocos). Agora ela cava a saída rumo à dona e entrega.
	 */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 4000)
	public void deliversFromASealedTunnel(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(12, FLOOR, 4), GameType.SURVIVAL);
		for (int x = 3; x <= 20; x++) {
			for (int z = 15; z <= 17; z++) {
				for (int y = FLOOR; y <= FLOOR + 2; y++) {
					boolean inside = x > 3 && x < 20 && z == 16 && y < FLOOR + 2;
					h.setBlock(new BlockPos(x, y, z), inside ? Blocks.AIR : Blocks.STONE);
				}
			}
		}
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 16), "Rosa");
		c.settings().debug = true;
		c.settings().idleExplore = false;
		TestSupport.give(c, net.minecraft.world.item.Items.STONE_PICKAXE, 1);
		TestSupport.give(c, net.minecraft.world.item.Items.FURNACE, 1);
		OrderService.createItemOrder(h.getLevel(), c, "minecraft:furnace", 1);
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == dev.autonomouscompanions.task.OrderEnums.OrderStatus.COMPLETED,
				"ordem " + order.status() + ", andou " + (int) order.progress().distance);
			h.assertValueEqual(TestSupport.count(owner, net.minecraft.world.item.Items.FURNACE), 1, "fornalha entregue");
			int opened = 0;
			for (int x = 4; x < 20; x++) {
				for (int y = FLOOR; y <= FLOOR + 2; y++) {
					if (!h.getBlockState(new BlockPos(x, y, 15)).is(Blocks.STONE)) {
						opened++;
					}
				}
			}
			h.assertTrue(opened > 0, "saiu sem abrir a parede do túnel (foi levada?)");
			TestSupport.cleanup(h, owner);
		});
	}
}
