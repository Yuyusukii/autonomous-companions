package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.build.Blueprint;
import dev.autonomouscompanions.build.BlueprintGenerator;
import dev.autonomouscompanions.build.BuildPlanner;
import dev.autonomouscompanions.build.BuildProject;
import dev.autonomouscompanions.build.HouseSize;
import dev.autonomouscompanions.build.HouseStyle;
import dev.autonomouscompanions.build.WoodPalette;
import dev.autonomouscompanions.coop.Cooperation;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionRole;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.persist.CompanionWorldData;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.OrderType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Teste 17 (casa completa) e teste 23 (funções diferentes sem conflito). */
public class BuildAndRoleGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";

	/** Teste 17: aprova um projeto, junta o material, constrói em fases com blocos reais e passa na inspeção. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 30000)
	public void buildsACompleteHouse(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(1, FLOOR, 22), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(3, FLOOR, 21), "Maya");
		c.settings().debug = true;
		HouseStyle style = HouseStyle.WOOD_CHALET;
		HouseSize size = HouseSize.SMALL;
		long seed = 7;
		var level = h.getLevel();
		// Escolhe orientação e ponto marcado de modo que a casa inteira caiba na arena.
		BlockPos lo = h.absolutePos(new BlockPos(1, 0, 1));
		BlockPos hi = h.absolutePos(new BlockPos(22, 14, 19));
		AABB arena = new AABB(Math.min(lo.getX(), hi.getX()), lo.getY(), Math.min(lo.getZ(), hi.getZ()), Math.max(lo.getX(), hi.getX()) + 1, hi.getY() + 1,
			Math.max(lo.getZ(), hi.getZ()) + 1);
		BlockPos clicked = null;
		Direction facing = null;
		Blueprint probe = BlueprintGenerator.generate(style, size, seed, WoodPalette.OAK);
		double bestScore = Double.MAX_VALUE;
		for (Direction f : Direction.Plane.HORIZONTAL) {
			for (int x = 1; x < 23; x++) {
				for (int z = 1; z < 20; z++) {
					BlockPos abs = h.absolutePos(new BlockPos(x, 1, z));
					AABB box = probe.worldBounds(abs, f);
					if (box.minX >= arena.minX && box.minY >= arena.minY && box.minZ >= arena.minZ && box.maxX <= arena.maxX && box.maxY <= arena.maxY
						&& box.maxZ <= arena.maxZ) {
						// Prefere a posição mais central (folga para andar em volta da obra).
						double score = Math.abs(box.getCenter().x - arena.getCenter().x) + Math.abs(box.getCenter().z - arena.getCenter().z);
						if (score < bestScore) {
							bestScore = score;
							clicked = abs;
							facing = f;
						}
					}
				}
			}
		}
		h.assertTrue(clicked != null, "a casa não cabe na arena de teste");
		BuildPlanner.Preview preview = BuildPlanner.preview(level, style, size, seed, clicked, facing, true, null);
		if (!preview.valid()) {
			// Diagnóstico: quais blocos da área o planejador considera construídos.
			BuildPlanner.Plan diag = BuildPlanner.plan(level, BlueprintGenerator.generate(style, size, seed, WoodPalette.byId(preview.wood())), preview.origin(),
				facing);
			java.util.Map<String, Integer> seen = new java.util.TreeMap<>();
			for (BlockPos p : diag.clear()) {
				var s = level.getBlockState(p);
				if (!dev.autonomouscompanions.world.BlockPolicy.isNatural(s)) {
					seen.merge(s.getBlock().toString() + "@" + h.relativePos(p).getY(), 1, Integer::sum);
				}
			}
			h.fail("local reprovado: " + preview.problems() + " blocos: " + seen + " origem " + h.relativePos(preview.origin()) + " " + facing);
		}
		for (var e : preview.materials().entrySet()) {
			TestSupport.give(c, e.getKey(), e.getValue());
		}
		TestSupport.give(c, Items.COBBLESTONE, 24);
		TestSupport.give(c, Items.DIRT, 32);
		TestSupport.give(c, Items.BREAD, 12);
		ServerNetworking.handleBuildDecision(owner, new Payloads.BuildDecision(c.getId(), clicked, facing.get2DDataValue(), style.ordinal(), size.ordinal(), seed,
			true, true));
		Order order = c.orders().firstOfType(OrderType.BUILD);
		h.assertTrue(order != null && order.projectId() != null, "a aprovação não criou a ordem de construção");
		BuildProject project = CompanionWorldData.get(level.getServer()).project(order.projectId());
		h.assertTrue(project != null, "projeto não persistido");
		final Direction finalFacing = facing;
		h.runAtTickTime(6000, () -> {
			// Diagnóstico: camadas da obra em ASCII (primeira letra do bloco; @ = companheira).
			StringBuilder sb = new StringBuilder(System.lineSeparator());
			for (int y = 1; y <= 5; y++) {
				sb.append("camada ").append(y).append(System.lineSeparator());
				for (int z = 0; z < 24; z++) {
					for (int x = 0; x < 24; x++) {
						BlockPos p = h.absolutePos(new BlockPos(x, y, z));
						if (c.blockPosition().equals(p)) {
							sb.append('@');
							continue;
						}
						var s = level.getBlockState(p);
						String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
						sb.append(s.isAir() ? '.' : n.startsWith("grass") ? ',' : n.endsWith("door") ? 'D' : n.endsWith("stairs") ? 'S' : n.endsWith("slab") ? 's'
							: n.endsWith("fence") ? 'f' : n.endsWith("planks") ? 'p' : n.endsWith("log") ? 'L' : Character.toUpperCase(n.charAt(0)));
					}
					sb.append(System.lineSeparator());
				}
			}
			dev.autonomouscompanions.AutonomousCompanions.LOGGER.info("[diagnóstico da obra] {}", sb);
		});
		h.succeedWhen(() -> {
			h.assertTrue(project.status() == BuildProject.Status.COMPLETED, "projeto em " + project.status() + " (" + project.placed + "/" + project.total + ")");
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem em " + order.status() + " " + order.reason());
			Blueprint bp = project.blueprint();
			BuildPlanner.Plan plan = BuildPlanner.plan(level, bp, project.origin(), finalFacing);
			int wrong = 0;
			for (BuildPlanner.WorldPlacement wp : plan.placements()) {
				if (!BuildPlanner.matches(level.getBlockState(wp.pos()), wp.state())) {
					wrong++;
				}
			}
			h.assertValueEqual(wrong, 0, "blocos da planta ausentes ou errados");
			h.assertTrue(project.placed >= bp.blockCount() / 2, "poucos blocos colocados pela própria companheira: " + project.placed);
			// Pilares provisórios de andaime não podem sobrar fora da planta.
			TestSupport.cleanup(h, owner);
		});
	}

	/** Teste 23: mineradora, lenhadora e guarda trabalham cada uma na sua função, sem pegar o alvo da outra. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 4000)
	public void rolesDriveDifferentWorkWithoutConflicts(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(12, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity miner = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 12), "Mina");
		CompanionEntity lumber = TestSupport.companion(h, owner, new BlockPos(14, FLOOR, 12), "Lena");
		lumber.settings().debug = true;
		CompanionEntity guard = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 10), "Gaia");
		TestSupport.give(miner, Items.WOODEN_PICKAXE, 1);
		for (int z = 3; z <= 8; z++) {
			h.setBlock(new BlockPos(4, FLOOR, z), Blocks.STONE);
		}
		for (int y = 0; y < 4; y++) {
			h.setBlock(new BlockPos(19, FLOOR + y, 18), Blocks.OAK_LOG);
		}
		for (CompanionEntity c : new CompanionEntity[]{miner, lumber, guard}) {
			c.settings().roleWork = true;
		}
		// Funções atribuídas pelo mesmo caminho da interface (pacote validado no servidor).
		ServerNetworking.handleRole(owner, new Payloads.SetRole(miner.getId(), CompanionRole.MINER.ordinal()));
		ServerNetworking.handleRole(owner, new Payloads.SetRole(lumber.getId(), CompanionRole.LUMBERJACK.ordinal()));
		ServerNetworking.handleRole(owner, new Payloads.SetRole(guard.getId(), CompanionRole.GUARD.ordinal()));
		h.assertTrue(miner.role() == CompanionRole.MINER && lumber.role() == CompanionRole.LUMBERJACK && guard.role() == CompanionRole.GUARD,
			"funções não aplicadas");
		// Reserva de alvos: uma não pode pegar o bloco reservado pela outra.
		Cooperation coop = Cooperation.get(h.getLevel().getServer());
		BlockPos probe = h.absolutePos(new BlockPos(1, FLOOR, 1));
		h.assertTrue(coop.claimTarget(miner.getUUID(), probe), "reserva inicial falhou");
		h.assertTrue(!coop.claimTarget(lumber.getUUID(), probe) && coop.isTargetClaimedByOther(lumber.getUUID(), probe), "duas reservaram o mesmo alvo");
		coop.releaseTarget(miner.getUUID(), probe);
		h.assertTrue(coop.claimTarget(lumber.getUUID(), probe), "reserva não liberada");
		coop.releaseTarget(lumber.getUUID(), probe);
		h.succeedWhen(() -> {
			int stone = miner.inventory().count(Items.COBBLESTONE);
			int logs = lumber.inventory().count(Items.OAK_LOG);
			h.assertTrue(stone >= 4, "a mineradora não minerou (pedregulho: " + stone + ")");
			h.assertTrue(logs >= 3, "a lenhadora não cortou a árvore (troncos: " + logs + ")");
			h.assertValueEqual(lumber.inventory().count(Items.COBBLESTONE), 0, "a lenhadora minerou pedra");
			h.assertValueEqual(miner.inventory().count(Items.OAK_LOG), 0, "a mineradora cortou a árvore");
			h.assertValueEqual(guard.inventory().count(Items.COBBLESTONE) + guard.inventory().count(Items.OAK_LOG), 0, "a guarda saiu da função");
			h.assertTrue(guard.distanceTo(owner) < 16, "a guarda se afastou da dona");
			TestSupport.cleanup(h, owner);
		});
	}
}
