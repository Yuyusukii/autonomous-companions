package dev.autonomouscompanions.test;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.item.WhistleItem;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.OrderService;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * Cada teste roda num lote próprio (ambientes ac_real1..8, cópias do diurno): sozinho no mundo, como no jogo — sem a
 * arena de outro teste ao lado para ela enxergar árvores, animais e minérios que não são do terreno dela.
 */
/**
 * Cenários no terreno real do mundo da jogadora (floresta de bétulas recortada do "Novo mundo": 64×64, com pedra,
 * minérios, água, 8 ovelhas e 4 porcos), onde os problemas relatados aconteceram.
 */
public class RealWorldGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String REAL = "autonomouscompanions-test:real_birch";
	static final Set<Item> RAW_MEAT = Set.of(Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT);
	static final Set<Item> COOKED_MEAT = Set.of(Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_CHICKEN, Items.COOKED_RABBIT);

	/** Posição em pé sobre o chão (relativa), na coluna x,z da estrutura. */
	static BlockPos surface(GameTestHelper h, int x, int z) {
		// De cima para baixo, abaixo do teto de barreira do teste: primeiro chão (não folha nem tronco) com 2 de espaço livre.
		for (int y = 24; y > 1; y--) {
			BlockPos p = new BlockPos(x, y, z);
			var below = h.getBlockState(p.below());
			if (h.getBlockState(p).getCollisionShape(h.getLevel(), h.absolutePos(p)).isEmpty() && h.getBlockState(p.above()).getCollisionShape(h.getLevel(),
				h.absolutePos(p.above())).isEmpty() && below.isSolidRender() && !below.is(net.minecraft.tags.BlockTags.LEAVES)
				&& !below.is(net.minecraft.tags.BlockTags.LOGS) && h.getBlockState(p).getFluidState().isEmpty()) {
				return p;
			}
		}
		throw new IllegalStateException("sem chão em " + x + "," + z);
	}

	/**
	 * Fim do teste: além da limpeza comum, apaga o terreno recortado (e os animais) para que os testes seguintes, que
	 * podem ser montados ao lado, não enxerguem as árvores e os 346 minérios de carvão que sobraram.
	 */
	static void cleanupReal(GameTestHelper h, ServerPlayer owner) {
		TestSupport.cleanup(h, owner);
		var level = h.getLevel();
		h.getEntities(EntityTypes.SHEEP).forEach(e -> e.discard());
		h.getEntities(EntityTypes.PIG).forEach(e -> e.discard());
		for (int x = 0; x < 64; x++) {
			for (int z = 0; z < 64; z++) {
				for (int y = 0; y < 26; y++) {
					level.setBlock(h.absolutePos(new BlockPos(x, y, z)), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}
	}

	static ServerPlayer owner(GameTestHelper h) {
		return TestSupport.owner(h, surface(h, 35, 35), GameType.SURVIVAL);
	}

	static CompanionEntity companion(GameTestHelper h, ServerPlayer owner, int x, int z, String name) {
		CompanionEntity c = TestSupport.companion(h, owner, surface(h, x, z), name);
		c.settings().debug = true;
		TestSupport.give(c, Items.BREAD, 6);
		return c;
	}

	static int count(ServerPlayer p, Set<Item> items) {
		int n = 0;
		for (Item i : items) {
			n += TestSupport.count(p, i);
		}
		return n;
	}

	/** Troncos de bétula no meio da floresta: ela corta perto e entrega, sem sair andando pelo mapa. */
	@GameTest(environment = "autonomouscompanions-test:ac_real1", structure = REAL, maxTicks = 12000)
	public void birchLogsFromTheForestAround(GameTestHelper h) {
		ServerPlayer owner = owner(h);
		CompanionEntity c = companion(h, owner, 32, 32, "Makima");
		OrderService.createGather(h.getLevel(), c, "minecraft:birch_log", GatherMode.DROPS, 16, SearchProfile.AUTO, 0, true);
		Order o = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(o.status() == OrderStatus.COMPLETED, "ordem " + o.status() + ": " + TestSupport.count(owner, Items.BIRCH_LOG) + " entregues, andou "
				+ (int) o.progress().distance + " [ticks " + c.tickCount + " pos " + c.blockPosition().toShortString() + " dona " + owner.blockPosition().toShortString() + " nomeada " + c.isNamed() + " dono " + (c.ownerInLevel() != null) + " estado " + c.companionState() + "]");
			h.assertTrue(TestSupport.count(owner, Items.BIRCH_LOG) >= 16, "troncos entregues: " + TestSupport.count(owner, Items.BIRCH_LOG));
			h.assertTrue(o.progress().distance < 400, "andou demais: " + (int) o.progress().distance);
			cleanupReal(h, owner);
		});
	}

	/** Duas pedindo troncos ao mesmo tempo (como Makima e Power): as duas entregam, dividindo as árvores. */
	@GameTest(environment = "autonomouscompanions-test:ac_real2", structure = REAL, maxTicks = 12000)
	public void twoCompanionsShareTheForest(GameTestHelper h) {
		ServerPlayer owner = owner(h);
		CompanionEntity a = companion(h, owner, 32, 32, "Makima");
		CompanionEntity b = companion(h, owner, 33, 31, "Power");
		OrderService.createGather(h.getLevel(), a, "minecraft:birch_log", GatherMode.DROPS, 12, SearchProfile.AUTO, 0, true);
		OrderService.createGather(h.getLevel(), b, "minecraft:birch_log", GatherMode.EXACT_BLOCK, 12, SearchProfile.AUTO, 0, true);
		Order oa = a.orders().all().getFirst();
		Order ob = b.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(oa.status() == OrderStatus.COMPLETED && ob.status() == OrderStatus.COMPLETED, "ordens " + oa.status() + "/" + ob.status()
				+ ", entregues " + TestSupport.count(owner, Items.BIRCH_LOG) + ", andaram " + (int) oa.progress().distance + "/" + (int) ob.progress().distance);
			h.assertTrue(TestSupport.count(owner, Items.BIRCH_LOG) >= 24, "troncos entregues: " + TestSupport.count(owner, Items.BIRCH_LOG));
			cleanupReal(h, owner);
		});
	}

	/** "Carne mais próxima (assada)" com ovelhas por perto: ela traz carne assada, sem sair atrás de vacas longe. */
	@GameTest(environment = "autonomouscompanions-test:ac_real3", structure = REAL, maxTicks = 20000)
	public void nearestCookedMeatWithSheepAround(GameTestHelper h) {
		ServerPlayer owner = owner(h);
		CompanionEntity c = companion(h, owner, 32, 32, "Raze");
		ServerNetworking.handleItemOrder(owner, new Payloads.StartItemOrder(c.getId(), OrderService.NEAREST_MEAT_COOKED, 4));
		Order o = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(o.status() == OrderStatus.COMPLETED, "ordem " + o.status() + ": " + count(owner, COOKED_MEAT) + " assadas entregues, andou "
				+ (int) o.progress().distance);
			h.assertTrue(count(owner, COOKED_MEAT) >= 4, "carne assada entregue: " + count(owner, COOKED_MEAT));
			h.assertTrue(o.progress().distance < 500, "andou demais: " + (int) o.progress().distance);
			cleanupReal(h, owner);
		});
	}

	/** Duas buscando carne crua ao mesmo tempo: as duas voltam com carne (não brigam pelo mesmo animal). */
	@GameTest(environment = "autonomouscompanions-test:ac_real4", structure = REAL, maxTicks = 12000)
	public void twoCompanionsBringMeat(GameTestHelper h) {
		ServerPlayer owner = owner(h);
		CompanionEntity a = companion(h, owner, 32, 32, "Raze");
		CompanionEntity b = companion(h, owner, 33, 31, "Makima");
		ServerNetworking.handleItemOrder(owner, new Payloads.StartItemOrder(a.getId(), OrderService.NEAREST_MEAT, 3));
		ServerNetworking.handleItemOrder(owner, new Payloads.StartItemOrder(b.getId(), OrderService.NEAREST_MEAT, 3));
		Order oa = a.orders().all().getFirst();
		Order ob = b.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(oa.status() == OrderStatus.COMPLETED && ob.status() == OrderStatus.COMPLETED, "ordens " + oa.status() + "/" + ob.status()
				+ ", carne entregue " + count(owner, RAW_MEAT) + ", andaram " + (int) oa.progress().distance + "/" + (int) ob.progress().distance);
			h.assertTrue(count(owner, RAW_MEAT) >= 6, "carne entregue: " + count(owner, RAW_MEAT));
			cleanupReal(h, owner);
		});
	}

	/** Fornalha sem nada: faz picareta, tira pedra (que está debaixo da terra) e fabrica a fornalha. */
	@GameTest(environment = "autonomouscompanions-test:ac_real5", structure = REAL, maxTicks = 12000)
	public void furnaceFromNothing(GameTestHelper h) {
		ServerPlayer owner = owner(h);
		CompanionEntity c = companion(h, owner, 32, 32, "Power");
		OrderService.createItemOrder(h.getLevel(), c, "minecraft:furnace", 1);
		Order o = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(o.status() == OrderStatus.COMPLETED, "ordem " + o.status() + ", andou " + (int) o.progress().distance);
			h.assertValueEqual(TestSupport.count(owner, Items.FURNACE), 1, "fornalha entregue");
			h.assertTrue(o.progress().distance < 300, "andou demais: " + (int) o.progress().distance);
			cleanupReal(h, owner);
		});
	}

	/** Fornalha com pedregulho no inventário: fabrica na hora, sem ir atrás de pedra nem de picareta. */
	@GameTest(environment = "autonomouscompanions-test:ac_real6", structure = REAL, maxTicks = 600)
	public void furnaceWithCobblestoneAtHand(GameTestHelper h) {
		ServerPlayer owner = owner(h);
		CompanionEntity c = companion(h, owner, 32, 32, "Power");
		TestSupport.give(c, Items.COBBLESTONE, 8);
		OrderService.createItemOrder(h.getLevel(), c, "minecraft:furnace", 1);
		Order o = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(o.status() == OrderStatus.COMPLETED, "ordem " + o.status() + ", andou " + (int) o.progress().distance);
			h.assertValueEqual(TestSupport.count(owner, Items.FURNACE), 1, "fornalha entregue");
			h.assertTrue(o.progress().distance < 12, "andou à toa: " + (int) o.progress().distance);
			h.assertTrue(c.inventory().count(s -> s.is(net.minecraft.tags.ItemTags.PICKAXES)) == 0, "fez picareta sem precisar");
			cleanupReal(h, owner);
		});
	}

	/** Apito: ela vem correndo e, ao chegar, fica perto (não sai correndo de novo). */
	@GameTest(environment = "autonomouscompanions-test:ac_real7", structure = REAL, maxTicks = 2400)
	public void whistleBringsHerAndSheStays(GameTestHelper h) {
		ServerPlayer owner = owner(h);
		CompanionEntity c = companion(h, owner, 12, 12, "Luna");
		// Como no jogo: exploração ociosa e trabalho da função ligados (padrão), sem comida e com um pouco de fome.
		c.settings().idleExplore = true;
		c.settings().roleWork = true;
		c.inventory().removeFree(Items.BREAD, 64);
		c.needs().setHunger(13);
		ServerNetworking.handleItemOrder(owner, new Payloads.StartItemOrder(c.getId(), OrderService.NEAREST_MEAT, 8));
		int[] nearTicks = {0};
		double[] maxAfter = {0};
		h.startSequence()
			.thenIdle(200)
			.thenExecute(() -> WhistleItem.call(owner, false, c.getUUID()))
			.thenWaitUntil(() -> h.assertTrue(TestSupport.horizontal(c, owner) <= 4, "ainda não chegou: " + (int) TestSupport.horizontal(c, owner)))
			.thenExecute(() -> h.onEachTick(() -> {
				nearTicks[0]++;
				maxAfter[0] = Math.max(maxAfter[0], TestSupport.horizontal(c, owner));
			}))
			.thenIdle(900)
			.thenExecute(() -> {
				h.assertTrue(maxAfter[0] < 10, "depois de chegar, ela se afastou até " + (int) maxAfter[0] + " blocos (estado " + c.companionState() + ")");
				cleanupReal(h, owner);
			})
			.thenSucceed();
	}

	/** Descansando, aparece um hostil: ela levanta, elimina e volta a descansar. */
	@GameTest(environment = "autonomouscompanions-test:ac_real8", structure = REAL, maxTicks = 2400)
	public void returnsToRestAfterKillingTheThreat(GameTestHelper h) {
		ServerPlayer owner = owner(h);
		CompanionEntity c = companion(h, owner, 33, 33, "Luna");
		TestSupport.give(c, Items.IRON_SWORD, 1);
		Husk[] husk = {null};
		h.startSequence()
			.thenExecute(() -> ServerNetworking.handleCommand(owner, new Payloads.SimpleCommand(c.getId(), Payloads.SimpleCommand.REST)))
			.thenWaitUntil(() -> h.assertTrue(c.companionState() == CompanionState.RESTING || c.isSleeping(), "não foi descansar: " + c.companionState()))
			.thenExecute(() -> husk[0] = h.spawn(EntityTypes.HUSK, surface(h, 38, 38), EntitySpawnReason.EVENT))
			.thenWaitUntil(() -> h.assertTrue(!husk[0].isAlive(), "o hostil ainda está vivo"))
			.thenWaitUntil(() -> h.assertTrue(c.companionState() == CompanionState.RESTING || c.isSleeping(),
				"não voltou a descansar depois do perigo: " + c.companionState()))
			.thenExecute(() -> cleanupReal(h, owner))
			.thenSucceed();
	}
	/**
	 * Fluidez (medição): 32 troncos de bétula. Registra a cada tick se ela está parada sem fazer nada útil (sem andar,
	 * sem quebrar bloco, sem fabricar), em que estado e etapa, e a maior parada seguida; imprime o resumo no log.
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_real9", structure = REAL, maxTicks = 12000)
	public void logGatheringFlowMeasured(GameTestHelper h) {
		ServerPlayer owner = owner(h);
		CompanionEntity c = companion(h, owner, 32, 32, "Makima");
		TestSupport.give(c, Items.STONE_AXE, 1);
		OrderService.createGather(h.getLevel(), c, "minecraft:birch_log", GatherMode.DROPS, 32, SearchProfile.AUTO, 0, false);
		Order o = c.orders().all().getFirst();
		c.brain().metrics().reset();
		java.util.Map<String, Integer> idleBy = new java.util.TreeMap<>();
		int[] idle = {0, 0, 0, 0}; // parada atual, maior parada, total parado, total
		double[] last = {c.getX(), c.getZ()};
		int[] mined = {0};
		h.onEachTick(() -> {
			if (o.status().isFinished()) {
				return;
			}
			idle[3]++;
			double moved = Math.abs(c.getX() - last[0]) + Math.abs(c.getZ() - last[1]);
			last[0] = c.getX();
			last[1] = c.getZ();
			boolean working = moved > 0.02 || o.progress().blocksMined != mined[0] || c.activityLevel() >= 2;
			mined[0] = o.progress().blocksMined;
			if (working) {
				idle[0] = 0;
			} else {
				idle[0]++;
				idle[1] = Math.max(idle[1], idle[0]);
				idle[2]++;
				var stack = c.brain().orders().describeStack();
				String key = c.companionState() + " / " + (stack.isEmpty() ? "-" : stack.getLast().getString().replaceAll("[0-9]+", "#"));
				idleBy.merge(key, 1, Integer::sum);
			}
		});
		h.succeedWhen(() -> {
			h.assertTrue(o.status() == OrderStatus.COMPLETED, "ordem " + o.status() + ": " + TestSupport.count(owner, Items.BIRCH_LOG) + " entregues");
			var m = c.brain().metrics();
			StringBuilder b = new StringBuilder(String.format("[fluidez] %d ticks, parada %d ticks (%.0f%%), maior parada seguida %d ticks, caminhos %d, trocas %d, IA média %.1f µs, pior %.1f µs",
				idle[3], idle[2], idle[3] == 0 ? 0.0 : 100.0 * idle[2] / idle[3], idle[1], m.pathComputations(), m.behaviorSwitches(), m.averageMicros(), m.maxMicros())).append(" | etapa mais cara: ").append(m.worstStep());
			idleBy.entrySet().stream().sorted((a, z) -> z.getValue() - a.getValue()).limit(8).forEach(e -> b.append(" | ").append(e.getKey()).append(": ").append(e.getValue()));
			dev.autonomouscompanions.AutonomousCompanions.LOGGER.info(b.toString());
			cleanupReal(h, owner);
		});
	}
	/**
	 * As três juntas, cada uma com uma ordem diferente (troncos, carne, fornalha): todas concluem, cada uma entrega só o
	 * que foi pedido a ela e os estados ficam independentes. Registra o custo da IA de cada uma no log.
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_real10", structure = REAL, maxTicks = 16000)
	public void threeCompanionsWithIndependentOrders(GameTestHelper h) {
		ServerPlayer owner = owner(h);
		CompanionEntity a = companion(h, owner, 32, 32, "Makima");
		CompanionEntity b = companion(h, owner, 33, 31, "Raze");
		CompanionEntity d = companion(h, owner, 31, 33, "Power");
		OrderService.createGather(h.getLevel(), a, "minecraft:birch_log", GatherMode.DROPS, 12, SearchProfile.AUTO, 0, false);
		ServerNetworking.handleItemOrder(owner, new Payloads.StartItemOrder(b.getId(), OrderService.NEAREST_MEAT, 3));
		OrderService.createItemOrder(h.getLevel(), d, "minecraft:furnace", 1);
		Order oa = a.orders().all().getFirst();
		Order ob = b.orders().all().getFirst();
		Order od = d.orders().all().getFirst();
		for (CompanionEntity c : new CompanionEntity[]{a, b, d}) {
			c.brain().metrics().reset();
		}
		h.succeedWhen(() -> {
			h.assertTrue(oa.status() == OrderStatus.COMPLETED && ob.status() == OrderStatus.COMPLETED && od.status() == OrderStatus.COMPLETED,
				"ordens " + oa.status() + " / " + ob.status() + " / " + od.status());
			h.assertValueEqual(oa.progress().delivered, 12, "troncos entregues pela Makima");
			h.assertValueEqual(ob.progress().delivered, 3, "carnes entregues pela Raze");
			h.assertValueEqual(od.progress().delivered, 1, "fornalha entregue pela Power");
			h.assertTrue(TestSupport.count(owner, Items.BIRCH_LOG) >= 12 && count(owner, RAW_MEAT) + count(owner, COOKED_MEAT) >= 3
				&& TestSupport.count(owner, Items.FURNACE) == 1, "itens na dona");
			h.assertTrue(!oa.id().equals(ob.id()) && !ob.id().equals(od.id()), "ordens com a mesma identidade");
			StringBuilder m = new StringBuilder("[tres]");
			for (CompanionEntity c : new CompanionEntity[]{a, b, d}) {
				var mm = c.brain().metrics();
				m.append(String.format(" %s: média %.1f µs, pior %.1f µs, caminhos %d, etapa mais cara %s |", c.companionName(), mm.averageMicros(), mm.maxMicros(),
					mm.pathComputations(), mm.worstStep()));
			}
			dev.autonomouscompanions.AutonomousCompanions.LOGGER.info(m.toString());
			cleanupReal(h, owner);
		});
	}
}
