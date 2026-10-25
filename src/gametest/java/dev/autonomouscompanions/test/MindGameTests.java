package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.mind.memory.MemoryReport;
import dev.autonomouscompanions.mind.memory.WorldMemory;
import dev.autonomouscompanions.mind.perception.Perception;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.OrderService;
import dev.autonomouscompanions.world.KnownResources;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Cérebro híbrido, fundação: percepção do estado real do jogo e memória persistente (fatos, observações e hipóteses),
 * inclusive o uso da memória na prática. Cada teste roda num lote próprio (a percepção conta criaturas a 24 blocos e a
 * memória de recursos é da equipe inteira).
 */
public class MindGameTests {
	private static final String ARENA = "autonomouscompanions-test:arena";

	private static CompanionEntity sustained(CompanionEntity c) {
		c.settings().classicSurvival = false;
		return c;
	}

	/** A percepção lê o mundo de verdade: minério, lava, monstro, animal, baú, ferramentas, inventário, hora e dona. */
	@GameTest(environment = "autonomouscompanions-test:ac_mind1", structure = ARENA, maxTicks = 200)
	public void perceptionReadsTheRealWorld(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 8), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(8, FLOOR, 8), "Rosa"));
		TestSupport.give(c, Items.STONE_PICKAXE, 1);
		TestSupport.give(c, Items.IRON_PICKAXE, 1);
		TestSupport.give(c, Items.BREAD, 10);
		h.setBlock(new BlockPos(11, FLOOR, 8), Blocks.IRON_ORE);
		h.setBlock(new BlockPos(8, FLOOR - 1, 11), Blocks.LAVA);
		h.setBlock(new BlockPos(5, FLOOR, 8), Blocks.CHEST);
		Mob husk = h.spawn(EntityTypes.HUSK, new BlockPos(8, FLOOR, 14), EntitySpawnReason.EVENT);
		husk.setNoAi(true);
		Mob cow = h.spawn(EntityTypes.COW, new BlockPos(14, FLOOR, 8), EntitySpawnReason.EVENT);
		cow.setNoAi(true);
		h.runAfterDelay(5, () -> {
			Perception.Snapshot s = Perception.of(c);
			String iron = Blocks.IRON_ORE.getName().getString();
			h.assertTrue(s.sees(iron), "não viu o minério de ferro: " + s.blocks().keySet());
			h.assertTrue(s.blocks().get(iron).nearest().equals(h.absolutePos(new BlockPos(11, FLOOR, 8))), "posição do minério errada");
			h.assertTrue(s.sees("lava"), "não viu a lava");
			h.assertTrue(s.dangers().stream().anyMatch(d -> d.startsWith("lava a")), "lava a 3 blocos não apareceu como perigo: " + s.dangers());
			h.assertTrue(s.sees(Blocks.CHEST.getName().getString()), "não viu o baú");
			h.assertTrue(!s.hostiles().isEmpty() && s.hostiles().getFirst().name().equals(EntityTypes.HUSK.getDescription().getString()), "monstro: " + s.hostiles());
			h.assertTrue(s.animals().stream().anyMatch(a -> a.name().equals(EntityTypes.COW.getDescription().getString())), "animais: " + s.animals());
			h.assertValueEqual(s.bestTools().get("picareta"), new ItemStack(Items.IRON_PICKAXE).getHoverName().getString(), "melhor picareta");
			h.assertValueEqual(s.inventory().get(new ItemStack(Items.BREAD).getHoverName().getString()), 10, "pães no inventário");
			h.assertTrue(!s.night(), "o ambiente é de dia");
			h.assertValueEqual(s.health(), c.getHealth(), "vida");
			h.assertTrue(s.ownerDirection() != null && Math.abs(s.ownerDistance() - c.distanceTo(owner)) < 0.01, "dona");
			h.assertTrue(s.describe().size() >= 6, "descrição curta demais");
			TestSupport.cleanup(h, owner);
			h.succeed();
		});
	}

	/**
	 * Memória: lembra posições e esquece as que deixaram de ser verdade; marca de "sem acesso" expira (não vira regra);
	 * três confirmações viram hipótese e duas idas sem achar nada a derrubam; experiências viram estatística; tudo
	 * sobrevive a salvar e carregar.
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_mind2", structure = ARENA, maxTicks = 100)
	public void memoryRemembersForgetsAndPersists(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(8, FLOOR, 8), "Rosa"));
		ServerLevel level = h.getLevel();
		WorldMemory memory = WorldMemory.get(level.getServer());
		// O ambiente do teste roda a função reset_memory no início do lote: a memória de recursos da equipe começa vazia.
		h.assertTrue(memory.sightingSummary(level.dimension()).isEmpty() && memory.regions(level.dimension()).isEmpty(),
			"a memória da equipe não foi limpa no início do lote: " + memory.sightingSummary(level.dimension()));
		long now = level.getGameTime();
		BlockPos real = h.absolutePos(new BlockPos(12, FLOOR, 8));
		BlockPos gone = h.absolutePos(new BlockPos(9, FLOOR, 8));
		h.setBlock(new BlockPos(12, FLOOR, 8), Blocks.EMERALD_ORE);
		KnownResources.record(level, real, Blocks.EMERALD_ORE);
		KnownResources.record(level, gone, Blocks.EMERALD_ORE); // não há esmeralda ali
		BlockPos found = memory.nearest(level, s -> s.is(Blocks.EMERALD_ORE), c.blockPosition(), 64, Set.of());
		h.assertTrue(real.equals(found), "devolveu " + found + " (esperado o minério real)");
		h.assertTrue(memory.nearest(level, s -> s.is(Blocks.EMERALD_ORE), gone, 2, Set.of()) == null, "a posição falsa não foi esquecida");
		// Marca de "sem acesso": vale agora, expira depois.
		memory.markUnreachable(c.getUUID(), level.dimension(), real, now);
		h.assertTrue(memory.unreachableNear(c.getUUID(), level.dimension(), real, 4, now + 10).contains(real), "marca não registrada");
		h.assertTrue(memory.unreachableNear(c.getUUID(), level.dimension(), real, 4, now + WorldMemory.MARK_TTL + 1).isEmpty(), "marca não expirou");
		// Hipótese: 3 mineradas na mesma área viram hipótese; duas idas sem achar nada derrubam.
		BlockPos area = h.absolutePos(new BlockPos(16, FLOOR, 16));
		for (int i = 0; i < 3; i++) {
			memory.mined(level, Blocks.EMERALD_ORE, area.offset(i, 0, 0));
		}
		var region = memory.bestRegion(level.dimension(), s -> s.is(Blocks.EMERALD_ORE), c.blockPosition(), 64);
		h.assertTrue(region != null && region.observations() == 3, "hipótese não formada: " + region);
		h.assertTrue(MemoryReport.lines(c).stream().anyMatch(l -> l.startsWith("Hipótese")), "relatório sem a hipótese");
		memory.refute(level.dimension(), region, now);
		var weaker = memory.bestRegion(level.dimension(), s -> s.is(Blocks.EMERALD_ORE), c.blockPosition(), 64);
		h.assertTrue(weaker != null && weaker.confidence() < region.confidence(), "a hipótese não perdeu confiança");
		memory.refute(level.dimension(), weaker, now);
		h.assertTrue(memory.bestRegion(level.dimension(), s -> s.is(Blocks.EMERALD_ORE), c.blockPosition(), 64) == null, "a hipótese refutada continua");
		// Experiências → estatística.
		memory.recordExperience(c.getUUID(), new WorldMemory.Experience(now, "GATHER", "minecraft:emerald", false, "NOT_FOUND: nada", real, 400));
		memory.recordExperience(c.getUUID(), new WorldMemory.Experience(now + 1, "GATHER", "minecraft:emerald", true, "", real, 600));
		WorldMemory.Stat stat = memory.stat(c.getUUID(), "GATHER|minecraft:emerald");
		h.assertTrue(stat != null && stat.attempts() == 2 && stat.failures() == 1 && stat.averageSuccessTicks() == 600, "estatística: " + stat);
		memory.setPlace(c.getUUID(), "base", level.dimension(), real, WorldMemory.Certainty.FACT, now);
		// Salvar e carregar (o mesmo codec do arquivo do mundo).
		var encoded = WorldMemory.CODEC.encodeStart(NbtOps.INSTANCE, memory).getOrThrow();
		WorldMemory loaded = WorldMemory.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		h.assertTrue(loaded.base(c.getUUID()).map(WorldMemory.Place::pos).orElse(null) != null
			&& loaded.base(c.getUUID()).get().pos().equals(real) && loaded.base(c.getUUID()).get().certainty() == WorldMemory.Certainty.FACT, "base perdida");
		h.assertValueEqual(loaded.sightingCount(level.dimension(), Blocks.EMERALD_ORE), memory.sightingCount(level.dimension(), Blocks.EMERALD_ORE),
			"recursos lembrados depois de carregar");
		h.assertValueEqual(loaded.stat(c.getUUID(), "GATHER|minecraft:emerald"), stat, "estatística depois de carregar");
		h.assertValueEqual(loaded.mind(c.getUUID()).experiences().size(), memory.mind(c.getUUID()).experiences().size(), "experiências depois de carregar");
		memory.forget(c.getUUID());
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** Trabalho de verdade alimenta a memória: minera carvão e fica lembrando dos outros minérios vistos e da experiência. */
	@GameTest(environment = "autonomouscompanions-test:ac_mind3", structure = ARENA, maxTicks = 2400)
	public void realWorkFeedsTheMemory(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa"));
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_PICKAXE, 1);
		for (int i = 0; i < 5; i++) {
			h.setBlock(new BlockPos(12 + i, FLOOR, 12), Blocks.COAL_ORE);
		}
		ServerLevel level = h.getLevel();
		WorldMemory memory = WorldMemory.get(level.getServer());
		OrderService.createGather(level, c, "minecraft:coal_ore", GatherMode.DROPS, 2, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status());
			var exps = memory.mind(c.getUUID()).experiences();
			h.assertTrue(!exps.isEmpty() && exps.getLast().success() && exps.getLast().target().contains("coal"), "experiência não registrada: " + exps);
			BlockPos left = memory.nearest(level, s -> s.is(Blocks.COAL_ORE), h.absolutePos(new BlockPos(14, FLOOR, 12)), 8, Set.of());
			h.assertTrue(left != null, "não lembra dos minérios de carvão que viu e sobraram");
			h.assertTrue(memory.regions(level.dimension()).stream().anyMatch(r -> r.block().getPath().equals("coal_ore") && r.observations() >= 2
				&& r.center().closerThan(h.absolutePos(new BlockPos(14, FLOOR, 12)), 6)), "a área do carvão minerado não foi registrada");
			memory.forget(c.getUUID());
			TestSupport.cleanup(h, owner);
		});
	}

	/**
	 * Memória na prática: carvão lembrado a 100 blocos, além do raio da busca (64), num corredor de 160 blocos: ela vai direto
	 * até ele, sem nenhuma etapa de exploração. (A 100, e não a 120: o alcance de busca padrão é 128, e na bateria ela andou
	 * 9 blocos até um carvão de outro teste, do lado de fora das barreiras, antes de lembrar — a 120 o lembrado passou de 128.) (O servidor de testes cerca cada área com barreiras enquanto o
	 * teste roda; por isso o teste usa uma estrutura longa, e não o terreno fora da arena.)
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_mind4", structure = "autonomouscompanions-test:long_corridor", maxTicks = 3000)
	public void goesStraightToARememberedOre(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(152, FLOOR, 4), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(150, FLOOR, 4), "Vera"));
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_PICKAXE, 1);
		ServerLevel level = h.getLevel();
		BlockPos rel = new BlockPos(50, FLOOR, 4);
		BlockPos ore = h.absolutePos(rel);
		h.setBlock(rel, Blocks.COAL_ORE);
		KnownResources.record(level, ore, Blocks.COAL_ORE); // visto antes (por ela ou por outra da equipe)
		OrderService.createGather(level, c, "minecraft:coal_ore", GatherMode.DROPS, 1, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + ", andou " + (int) order.progress().distance);
			h.assertTrue(c.brain().metrics().memoryRecalls() >= 1, "não usou a memória");
			h.assertValueEqual(c.brain().metrics().explorationLegs(), 0L, "etapas de exploração (deveria ir direto pela memória)");
			h.assertTrue(order.progress().distance < 300, "andou demais: " + (int) order.progress().distance);
			WorldMemory.get(level.getServer()).forget(c.getUUID());
			TestSupport.cleanup(h, owner);
		});
	}
}
