package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.build.Blueprint;
import dev.autonomouscompanions.build.BlueprintGenerator;
import dev.autonomouscompanions.build.BuildPlanner;
import dev.autonomouscompanions.build.BuildProject;
import dev.autonomouscompanions.build.HouseSize;
import dev.autonomouscompanions.build.HouseStyle;
import dev.autonomouscompanions.build.TerrainPlanner;
import dev.autonomouscompanions.build.WoodPalette;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.persist.CompanionWorldData;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.OrderType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Limpeza de terreno (sozinha ou em equipe) e casa em equipe (as outras buscam materiais para a líder). */
public class TerrainGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";
	/** Chão das arenas: a grama fica em FLOOR - 1. */
	private static final int GROUND = FLOOR - 1;

	/** Árvore com tronco de {@code height} e copa de folhas; {@code branchTo} estende um galho para -x. */
	private static void tree(GameTestHelper h, int x, int z, int height, int branchTo) {
		for (int y = 0; y < height; y++) {
			h.setBlock(new BlockPos(x, FLOOR + y, z), Blocks.OAK_LOG);
		}
		for (int bx = x - 1; bx >= branchTo; bx--) {
			h.setBlock(new BlockPos(bx, FLOOR + height - 2, z), Blocks.OAK_LOG);
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int y = height - 2; y <= height; y++) {
					BlockPos p = new BlockPos(x + dx, FLOOR + y, z + dz);
					if (h.getBlockState(p).isAir()) {
						h.setBlock(p, Blocks.OAK_LEAVES);
					}
				}
			}
		}
	}

	private static BuildProject projectOf(GameTestHelper h, CompanionEntity c) {
		Order o = c.orders().firstOfType(OrderType.BUILD);
		h.assertTrue(o != null && o.projectId() != null, "sem ordem de obra");
		return CompanionWorldData.get(h.getLevel().getServer()).project(o.projectId());
	}

	/** A área ficou plana: chão firme no nível e nada natural acima (até 10 blocos), exceto o que é construção. */
	private static void assertFlat(GameTestHelper h, int x0, int z0, int n) {
		for (int x = x0; x < x0 + n; x++) {
			for (int z = z0; z < z0 + n; z++) {
				BlockState ground = h.getBlockState(new BlockPos(x, GROUND, z));
				h.assertTrue(!ground.isAir() && !ground.canBeReplaced(), "buraco em " + x + "," + z);
				for (int y = FLOOR; y <= FLOOR + 10; y++) {
					BlockState s = h.getBlockState(new BlockPos(x, y, z));
					h.assertTrue(s.isAir() || s.is(Blocks.OAK_PLANKS), "sobrou " + s.getBlock() + " em " + x + "," + y + "," + z);
				}
			}
		}
	}

	/** Nenhum bloco de andaime sobrou em volta da área. */
	private static void assertNoScaffolds(GameTestHelper h) {
		for (int x = 1; x < 23; x++) {
			for (int z = 1; z < 23; z++) {
				for (int y = FLOOR; y <= FLOOR + 10; y++) {
					BlockState s = h.getBlockState(new BlockPos(x, y, z));
					h.assertTrue(!s.is(Blocks.DIRT) && !s.is(Blocks.COBBLESTONE), "andaime esquecido em " + x + "," + y + "," + z);
				}
			}
		}
	}

	/** O plano pega a árvore inteira (com o galho fora da borda), o morro e o buraco; a construção fica. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void plannerTakesWholeTreesAndKeepsBuildings(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		tree(h, 8, 10, 6, 6);
		h.setBlock(new BlockPos(13, FLOOR, 13), Blocks.STONE);
		h.setBlock(new BlockPos(13, FLOOR + 1, 13), Blocks.STONE);
		h.setBlock(new BlockPos(14, GROUND, 9), Blocks.AIR);
		h.setBlock(new BlockPos(11, FLOOR, 14), Blocks.SHORT_GRASS);
		h.setBlock(new BlockPos(15, FLOOR, 15), Blocks.OAK_PLANKS);
		BuildProject p = BuildProject.terrain(UUID.randomUUID(), owner.getUUID(), List.of(owner.getUUID()), h.absolutePos(new BlockPos(12, GROUND, 12)), 8,
			h.getLevel().dimension());
		BuildPlanner.Plan plan = TerrainPlanner.plan(h.getLevel(), p);
		h.assertTrue(plan.clear().contains(h.absolutePos(new BlockPos(6, FLOOR + 4, 10))), "o galho fora da borda ficou fora do plano");
		h.assertTrue(plan.clear().contains(h.absolutePos(new BlockPos(7, FLOOR + 5, 10))), "folhas da árvore fora da borda ficaram");
		h.assertTrue(plan.clear().contains(h.absolutePos(new BlockPos(8, FLOOR, 10))), "tronco fora do plano");
		h.assertTrue(plan.clear().contains(h.absolutePos(new BlockPos(13, FLOOR + 1, 13))), "morro fora do plano");
		h.assertTrue(plan.clear().contains(h.absolutePos(new BlockPos(11, FLOOR, 14))), "mato fora do plano");
		h.assertTrue(!plan.clear().contains(h.absolutePos(new BlockPos(15, FLOOR, 15))), "a construção (tábuas) seria quebrada");
		h.assertTrue(plan.placements().stream().anyMatch(wp -> wp.pos().equals(h.absolutePos(new BlockPos(14, GROUND, 9)))), "o buraco não seria tapado");
		var pv = TerrainPlanner.preview(h.getLevel(), p);
		h.assertTrue(pv.valid() && pv.treeCount() == 1 && pv.skipped() == 1, "prévia: árvores " + pv.treeCount() + ", mantidos " + pv.skipped());
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** Sozinha: limpa 8×8 com uma árvore alta (sobe em andaime e depois o retira), morro, mato e buraco. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 14000)
	public void clearsAreaAlone(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(4, FLOOR, 4), "Terra");
		c.settings().debug = true;
		for (Item tool : new Item[]{Items.STONE_AXE, Items.STONE_PICKAXE, Items.STONE_SHOVEL}) {
			TestSupport.give(c, tool, 1);
		}
		TestSupport.give(c, Items.BREAD, 8);
		tree(h, 9, 10, 8, 7);
		h.setBlock(new BlockPos(13, FLOOR, 13), Blocks.STONE);
		h.setBlock(new BlockPos(13, FLOOR + 1, 13), Blocks.STONE);
		h.setBlock(new BlockPos(14, FLOOR, 13), Blocks.DIRT);
		h.setBlock(new BlockPos(14, GROUND, 9), Blocks.AIR);
		h.setBlock(new BlockPos(11, FLOOR, 14), Blocks.SHORT_GRASS);
		h.setBlock(new BlockPos(12, FLOOR, 9), Blocks.DANDELION);
		h.setBlock(new BlockPos(15, FLOOR, 15), Blocks.OAK_PLANKS);
		ServerNetworking.handleBuildDecision(owner, new Payloads.BuildDecision(c.getId(), h.absolutePos(new BlockPos(12, GROUND, 12)), 0, 0, 0, 0L, true, true,
			Payloads.BuildMark.TERRAIN, 8, 1));
		BuildProject project = projectOf(h, c);
		h.assertTrue(project.isTerrain() && project.area() == 8 && project.workers().size() == 1, "projeto de limpeza mal criado");
		h.succeedWhen(() -> {
			h.assertTrue(project.status() == BuildProject.Status.COMPLETED, "projeto em " + project.status() + " (" + project.placed + "/" + project.total + ")");
			assertFlat(h, 8, 8, 8);
			h.assertTrue(h.getBlockState(new BlockPos(7, FLOOR + 6, 10)).isAir(), "o galho fora da borda ficou");
			h.assertTrue(h.getBlockState(new BlockPos(15, FLOOR, 15)).is(Blocks.OAK_PLANKS), "a construção foi quebrada");
			assertNoScaffolds(h);
			TestSupport.cleanup(h, owner);
		});
	}

	/** Em três: 12×12 dividida em faixas; todas trabalham, ninguém pega o mesmo bloco, a área fica plana. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 14000)
	public void clearsAreaAsATeamOfThree(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity a = TestSupport.companion(h, owner, new BlockPos(3, FLOOR, 4), "Ana");
		CompanionEntity b = TestSupport.companion(h, owner, new BlockPos(4, FLOOR, 3), "Bia");
		CompanionEntity d = TestSupport.companion(h, owner, new BlockPos(5, FLOOR, 4), "Dani");
		for (CompanionEntity c : new CompanionEntity[]{a, b, d}) {
			c.settings().debug = true;
			for (Item tool : new Item[]{Items.STONE_AXE, Items.STONE_PICKAXE, Items.STONE_SHOVEL}) {
				TestSupport.give(c, tool, 1);
			}
			TestSupport.give(c, Items.BREAD, 8);
		}
		tree(h, 8, 9, 6, 8);
		tree(h, 15, 15, 6, 15);
		for (int z = 9; z <= 14; z++) {
			h.setBlock(new BlockPos(12, FLOOR, z), Blocks.STONE);
		}
		h.setBlock(new BlockPos(11, GROUND, 16), Blocks.AIR);
		h.setBlock(new BlockPos(16, GROUND, 7), Blocks.AIR);
		ServerNetworking.handleBuildDecision(owner, new Payloads.BuildDecision(a.getId(), h.absolutePos(new BlockPos(12, GROUND, 12)), 0, 0, 0, 0L, true, true,
			Payloads.BuildMark.TERRAIN, 12, 3));
		BuildProject project = projectOf(h, a);
		h.assertValueEqual(project.workers().size(), 3, "tamanho da equipe");
		List<Order> orders = new ArrayList<>();
		for (CompanionEntity c : new CompanionEntity[]{a, b, d}) {
			Order o = c.orders().firstOfType(OrderType.BUILD);
			h.assertTrue(o != null && project.id().equals(o.projectId()), c.companionName() + " não recebeu a limpeza");
			orders.add(o);
		}
		h.succeedWhen(() -> {
			h.assertTrue(project.status() == BuildProject.Status.COMPLETED, "projeto em " + project.status() + " (" + project.placed + "/" + project.total + ")");
			for (Order o : orders) {
				h.assertTrue(o.status() == OrderStatus.COMPLETED, "ordem de alguém em " + o.status());
				h.assertTrue(o.progress().blocksMined > 0, "uma das companheiras não trabalhou");
			}
			assertFlat(h, 6, 6, 12);
			assertNoScaffolds(h);
			TestSupport.cleanup(h, owner);
		});
	}

	/** Salvar e recarregar no meio da limpeza em dupla: as duas retomam a obra (mesmo projeto) e terminam. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 14000)
	public void teamClearingSurvivesReload(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity[] team = {TestSupport.companion(h, owner, new BlockPos(3, FLOOR, 4), "Rosa"), TestSupport.companion(h, owner, new BlockPos(4, FLOOR, 3),
			"Iris")};
		for (CompanionEntity c : team) {
			c.settings().debug = true;
			for (Item tool : new Item[]{Items.STONE_AXE, Items.STONE_PICKAXE, Items.STONE_SHOVEL}) {
				TestSupport.give(c, tool, 1);
			}
			TestSupport.give(c, Items.BREAD, 8);
		}
		tree(h, 9, 10, 6, 9);
		for (int z = 8; z <= 15; z++) {
			h.setBlock(new BlockPos(14, FLOOR, z), Blocks.STONE);
		}
		ServerNetworking.handleBuildDecision(owner, new Payloads.BuildDecision(team[0].getId(), h.absolutePos(new BlockPos(12, GROUND, 12)), 0, 0, 0, 0L, true,
			true, Payloads.BuildMark.TERRAIN, 8, 2));
		BuildProject project = projectOf(h, team[0]);
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(project.placed >= 3, "ainda não começou: " + project.placed))
			.thenExecute(() -> {
				team[0] = TestSupport.reload(h, team[0]);
				team[1] = TestSupport.reload(h, team[1]);
				for (CompanionEntity c : team) {
					Order o = c.orders().firstOfType(OrderType.BUILD);
					h.assertTrue(o != null && project.id().equals(o.projectId()), "ordem de limpeza perdida ao recarregar");
				}
			})
			.thenWaitUntil(() -> h.assertTrue(project.status() == BuildProject.Status.COMPLETED, "projeto em " + project.status()))
			.thenExecute(() -> {
				assertFlat(h, 8, 8, 8);
				assertNoScaffolds(h);
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/** Casa em equipe: a líder não tem a madeira; as outras duas buscam a parte delas e entregam a ela. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 8000)
	public void houseTeamFetchesMissingMaterials(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(1, FLOOR, 22), GameType.SURVIVAL);
		CompanionEntity leader = TestSupport.companion(h, owner, new BlockPos(3, FLOOR, 21), "Lider");
		CompanionEntity h1 = TestSupport.companion(h, owner, new BlockPos(5, FLOOR, 21), "Ajuda");
		CompanionEntity h2 = TestSupport.companion(h, owner, new BlockPos(7, FLOOR, 21), "Apoio");
		var level = h.getLevel();
		HouseStyle style = HouseStyle.WOOD_CHALET;
		HouseSize size = HouseSize.SMALL;
		long seed = 7;
		BlockPos clicked = null;
		Direction facing = null;
		BlockPos lo = h.absolutePos(new BlockPos(1, 0, 1));
		BlockPos hi = h.absolutePos(new BlockPos(22, 14, 19));
		AABB arena = new AABB(Math.min(lo.getX(), hi.getX()), lo.getY(), Math.min(lo.getZ(), hi.getZ()), Math.max(lo.getX(), hi.getX()) + 1, hi.getY() + 1,
			Math.max(lo.getZ(), hi.getZ()) + 1);
		Blueprint probe = BlueprintGenerator.generate(style, size, seed, WoodPalette.OAK);
		for (Direction f : Direction.Plane.HORIZONTAL) {
			for (int x = 1; x < 23 && clicked == null; x++) {
				for (int z = 1; z < 20 && clicked == null; z++) {
					BlockPos abs = h.absolutePos(new BlockPos(x, 1, z));
					AABB box = probe.worldBounds(abs, f);
					if (box.minX >= arena.minX && box.minZ >= arena.minZ && box.maxX <= arena.maxX && box.maxZ <= arena.maxZ) {
						clicked = abs;
						facing = f;
					}
				}
			}
		}
		h.assertTrue(clicked != null, "a casa não cabe na arena");
		BuildPlanner.Preview preview = BuildPlanner.preview(level, style, size, seed, clicked, facing, true, null);
		h.assertTrue(preview.valid(), "local reprovado: " + preview.problems());
		// A líder recebe tudo, menos os itens da madeira escolhida; as três recebem troncos para fabricar a parte delas.
		WoodPalette wood = WoodPalette.byId(preview.wood());
		List<Item> withheld = new ArrayList<>();
		for (var e : preview.materials().entrySet()) {
			String id = BuiltInRegistries.ITEM.getKey(e.getKey()).getPath();
			if (id.startsWith(preview.wood() + "_")) {
				withheld.add(e.getKey());
			} else {
				TestSupport.give(leader, e.getKey(), e.getValue());
			}
		}
		h.assertTrue(withheld.size() >= 3, "poucos itens de madeira para dividir: " + withheld);
		for (CompanionEntity c : new CompanionEntity[]{leader, h1, h2}) {
			c.settings().debug = true;
			TestSupport.give(c, wood.log().asItem(), c == leader ? 64 : 48);
			TestSupport.give(c, Items.BREAD, 8);
		}
		TestSupport.give(leader, Items.COBBLESTONE, 24);
		ServerNetworking.handleBuildDecision(owner, new Payloads.BuildDecision(leader.getId(), clicked, facing.get2DDataValue(), style.ordinal(), size.ordinal(),
			seed, true, true, Payloads.BuildMark.HOUSE, 0, 3));
		BuildProject project = projectOf(h, leader);
		h.assertValueEqual(project.workers().size(), 3, "tamanho da equipe");
		Order o1 = h1.orders().firstOfType(OrderType.BUILD);
		Order o2 = h2.orders().firstOfType(OrderType.BUILD);
		h.assertTrue(o1 != null && o2 != null, "as ajudantes não receberam a obra");
		int[] logsBefore = {h1.inventory().count(wood.log().asItem()), h2.inventory().count(wood.log().asItem())};
		h.succeedWhen(() -> {
			h.assertTrue(project.status() != BuildProject.Status.APPROVED && project.status() != BuildProject.Status.GATHERING,
				"a líder ainda está juntando material: " + project.status());
			h.assertTrue(o1.status() == OrderStatus.COMPLETED && o2.status() == OrderStatus.COMPLETED, "ajudantes: " + o1.status() + " / " + o2.status());
			h.assertTrue(h1.inventory().count(wood.log().asItem()) < logsBefore[0] && h2.inventory().count(wood.log().asItem()) < logsBefore[1],
				"alguma ajudante não fabricou a parte dela");
			for (Item item : withheld) {
				h.assertTrue(leader.inventory().count(item) > 0 || leader.mainHandItem().is(item) || project.placed > 0, "a líder não recebeu " + item);
			}
			TestSupport.cleanup(h, owner);
		});
	}
}
