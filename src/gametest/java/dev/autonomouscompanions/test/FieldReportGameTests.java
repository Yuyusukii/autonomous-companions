package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.ai.HelperGuard;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.persist.CompanionWorldData;
import dev.autonomouscompanions.spawn.AnimalBoost;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.OrderService;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Problemas relatados depois de 20 minutos de jogo: madeira buscada longe com árvores ao lado (o alto das árvores ficava
 * para trás), "Madeira de Pinheiro", ajudante sumindo longe da dona, poucos animais e cansaço rápido demais.
 */
public class FieldReportGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";

	/** Pinheiro: tronco de {@code height} com folhas em volta do tronco do 3º bloco até o topo (como os de verdade). */
	private static List<BlockPos> spruce(GameTestHelper h, int x, int z, int height) {
		List<BlockPos> logs = new ArrayList<>();
		for (int y = 0; y < height; y++) {
			BlockPos p = new BlockPos(x, FLOOR + y, z);
			h.setBlock(p, Blocks.SPRUCE_LOG);
			logs.add(p);
		}
		for (int y = 2; y <= height; y++) {
			int r = y % 2 == 0 ? 1 : 2;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.abs(dx) + Math.abs(dz) > r + 1) {
						continue;
					}
					BlockPos p = new BlockPos(x + dx, FLOOR + y, z + dz);
					if (h.getBlockState(p).isAir()) {
						h.setBlock(p, Blocks.SPRUCE_LEAVES);
					}
				}
			}
		}
		return logs;
	}

	/**
	 * Pinheiro de 10 blocos ao lado dela, sem nenhum bloco no inventário para subir: ela pega terra ali mesmo, sobe
	 * (tirando as folhas do caminho), corta a árvore inteira, desmonta o pilar e não vai até a outra árvore.
	 */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 6000)
	public void cutsTheWholeTallTreeNextToHer(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(3, FLOOR, 3), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(5, FLOOR, 8), "Raze");
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_AXE, 1);
		TestSupport.give(c, Items.BREAD, 8);
		List<BlockPos> tall = spruce(h, 8, 8, 10);
		List<BlockPos> far = spruce(h, 19, 19, 4);
		OrderService.createGather(h.getLevel(), c, "minecraft:spruce_log", GatherMode.DROPS, 10, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem: " + order.status() + " (" + TestSupport.count(owner, Items.SPRUCE_LOG) + " entregues)");
			for (BlockPos p : tall) {
				h.assertTrue(h.getBlockState(p).isAir() || h.getBlockState(p).is(net.minecraft.tags.BlockTags.SAPLINGS), "sobrou " + h.getBlockState(p).getBlock() + " da árvore alta em " + p.toShortString());
			}
			for (int x = 1; x < 23; x++) {
				for (int z = 1; z < 23; z++) {
					for (int y = FLOOR; y <= FLOOR + 10; y++) {
						BlockState s = h.getBlockState(new BlockPos(x, y, z));
						h.assertTrue(!s.is(Blocks.DIRT) && !(s.is(Blocks.SPRUCE_LOG) && !far.contains(new BlockPos(x, y, z))), "pilar esquecido (" + s.getBlock() + ") em " + x + "," + y + "," + z);
					}
				}
			}
			for (BlockPos p : far) {
				h.assertTrue(h.getBlockState(p).is(Blocks.SPRUCE_LOG), "ela foi cortar a outra árvore em vez de terminar a do lado");
			}
			TestSupport.cleanup(h, owner);
		});
	}

	/** "Madeira de Pinheiro" (o bloco de casca) não existe na natureza: ela faz com os troncos do pinheiro ao lado. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3000)
	public void spruceWoodIsMadeFromTheTreeNextToHer(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(3, FLOOR, 3), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 8), "Raze");
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_AXE, 1);
		spruce(h, 9, 9, 6);
		OrderService.createGather(h.getLevel(), c, "minecraft:spruce_wood", GatherMode.DROPS, 3, SearchProfile.COMMON, 0, false);
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem: " + order.status());
			h.assertValueEqual(TestSupport.count(owner, Items.SPRUCE_WOOD), 3, "madeira de pinheiro entregue");
			h.assertTrue(order.progress().distance < 80, "andou demais: " + (int) order.progress().distance + " blocos");
			TestSupport.cleanup(h, owner);
		});
	}

	/**
	 * Como aconteceu no jogo: a ajudante está numa área carregada longe da dona (~2800 blocos, fora da área de todos os
	 * testes), a área descarrega com ela dentro (salva em disco, "sumida") e a guarda a traz de volta para perto da dona.
	 */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 2400)
	public void helperLeftFarAwayComesBack(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(12, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity helper = TestSupport.helper(h, owner, new BlockPos(14, FLOOR, 12));
		helper.settings().debug = true;
		var level = h.getLevel();
		BlockPos away = owner.blockPosition().offset(2000, 0, 2000);
		var cp = new net.minecraft.world.level.ChunkPos(away.getX() >> 4, away.getZ() >> 4);
		// Carrega (gera) a área distante por alguns segundos, como se ela tivesse andado até lá.
		level.getChunk(cp.x(), cp.z());
		level.getChunkSource().addTicketWithRadius(dev.autonomouscompanions.nav.ExpeditionChunkLoader.TICKET, cp, 2);
		int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, away.getX(), away.getZ());
		helper.teleportTo(away.getX() + 0.5, top + 1, away.getZ() + 0.5);
		CompanionWorldData.get(level.getServer()).updateCompanion(helper);
		boolean[] unloaded = {false};
		h.onEachTick(() -> {
			if (level.getEntity(helper.getUUID()) == null) {
				unloaded[0] = true;
			}
		});
		h.succeedWhen(() -> {
			var e = level.getEntity(helper.getUUID());
			h.assertTrue(e != null, "a ajudante ainda não voltou a existir");
			double d = TestSupport.horizontal(e, owner);
			h.assertTrue(d < 12, "a ajudante continua longe: " + (int) d + " blocos");
			h.assertTrue(unloaded[0], "a área distante não chegou a descarregar (o teste não exercitou o caso real)");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Sem animais por perto, aparece um grupo de animais de criação do próprio bioma. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 200)
	public void animalsAppearWhenScarce(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(12, FLOOR, 12), GameType.SURVIVAL);
		TestSupport.companion(h, owner, new BlockPos(13, FLOOR, 12), "Luna");
		int before = AnimalBoost.countNear(h.getLevel(), owner.blockPosition());
		// Arenas vizinhas de outros testes podem ter animais dentro do raio de 64: o mínimo é medido a partir do que já há.
		var config = dev.autonomouscompanions.config.ConfigManager.get();
		int savedMin = config.animaisMinimosPerto;
		config.animaisMinimosPerto = before + 3;
		int spawned = 0;
		for (int i = 0; i < 5 && spawned == 0; i++) {
			spawned = AnimalBoost.spawnGroup(h.getLevel(), owner, 4, 9);
		}
		var level = h.getLevel();
		BlockPos at = owner.blockPosition();
		h.assertTrue(spawned >= 2 && spawned <= 4, "animais criados: " + spawned + " (bioma " + level.getBiome(at).getRegisteredName() + ", animais do bioma: "
			+ level.getChunkSource().getGenerator().getMobsAt(level, level.structureManager(), net.minecraft.world.entity.MobCategory.CREATURE, at).unwrap()
				.stream().map(w -> w.value().type().toShortString()).toList() + ", chão " + level.getBlockState(at.below()).getBlock() + ", posição ok "
			+ net.minecraft.world.entity.SpawnPlacements.isSpawnPositionOk(net.minecraft.world.entity.EntityTypes.RABBIT, level, at.east(3)) + ", regra "
			+ net.minecraft.world.entity.SpawnPlacements.checkSpawnRules(net.minecraft.world.entity.EntityTypes.RABBIT, level,
				net.minecraft.world.entity.EntitySpawnReason.NATURAL, at.east(3), level.getRandom()) + ", luz " + level.getRawBrightness(at.east(3), 0) + ")");
		int after = AnimalBoost.countNear(h.getLevel(), owner.blockPosition());
		h.assertValueEqual(after, before + spawned, "animais por perto");
		// Com o mínimo atingido, não cria mais.
		config.animaisMinimosPerto = after;
		int extra = AnimalBoost.spawnGroup(h.getLevel(), owner, 4, 9);
		config.animaisMinimosPerto = savedMin;
		h.assertValueEqual(extra, 0, "criou animais mesmo com o mínimo atingido");
		h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, owner.getBoundingBox().inflate(32)).forEach(a -> a.discard());
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** Cansaço: trabalhando bem alimentada, 20 min reais não esgotam; comer devolve disposição. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void fatigueIsSlowerAndFoodHelps(GameTestHelper h) {
		var needs = new dev.autonomouscompanions.survival.CompanionNeeds();
		for (int t = 0; t < 20 * 60 * 20; t++) {
			needs.tick(2, false, false, 20, 20, net.minecraft.world.Difficulty.PEACEFUL, true);
			if (needs.hunger() < 18) {
				needs.eat(new ItemStack(Items.COOKED_BEEF).get(DataComponents.FOOD));
			}
		}
		h.assertTrue(needs.fatigue() < dev.autonomouscompanions.survival.CompanionNeeds.TIRED,
			"20 min trabalhando bem alimentada já deixa cansada: " + needs.fatigue());
		// Disposição +500%: 2 horas de trabalho bem alimentada ainda não cansam.
		for (int t = 0; t < 20 * 60 * 100; t++) {
			needs.tick(2, false, false, 20, 20, net.minecraft.world.Difficulty.PEACEFUL, true);
			if (needs.hunger() < 18) {
				needs.eat(new ItemStack(Items.COOKED_BEEF).get(DataComponents.FOOD));
			}
		}
		h.assertTrue(needs.fatigue() < dev.autonomouscompanions.survival.CompanionNeeds.TIRED, "2 h trabalhando bem alimentada já deixa cansada: " + needs.fatigue());
		needs.setFatigue(50);
		needs.eat(new ItemStack(Items.COOKED_BEEF).get(DataComponents.FOOD));
		h.assertTrue(needs.fatigue() <= 42.01f, "comer não devolveu disposição: " + needs.fatigue());
		h.succeed();
	}
	/**
	 * Cama branca (pedido que falhou no jogo da jogadora): lã das ovelhas brancas + tábuas, sem tingir camas nem procurar
	 * lírio-do-vale, sem matar as ovelhas marrons e sem planejar caçar strider (Nether).
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_hunt7", structure = ARENA, maxTicks = 6000)
	public void whiteBedFromSheepNearby(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(3, FLOOR, 3), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Rosa");
		c.settings().debug = true;
		TestSupport.give(c, Items.OAK_PLANKS, 8);
		TestSupport.give(c, Items.IRON_SWORD, 1);
		// A cor natural depende do bioma do teste: define 3 brancas e 2 marrons (estas não podem ser mortas por lã branca).
		java.util.List<net.minecraft.world.entity.animal.sheep.Sheep> brown = new java.util.ArrayList<>();
		for (int i = 0; i < 5; i++) {
			var sheep = h.spawn(net.minecraft.world.entity.EntityTypes.SHEEP, new BlockPos(13 + i * 2, FLOOR, 16), net.minecraft.world.entity.EntitySpawnReason.EVENT);
			sheep.setColor(i % 2 == 1 ? net.minecraft.world.item.DyeColor.BROWN : net.minecraft.world.item.DyeColor.WHITE);
			if (i % 2 == 1) {
				brown.add(sheep);
			}
		}
		var refusal = dev.autonomouscompanions.task.OrderService.createItemOrder(h.getLevel(), c, "minecraft:white_bed", 1);
		h.assertTrue(refusal == null, "pedido recusado: " + (refusal == null ? "" : refusal.getString()));
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem " + order.status() + (order.reason() != null ? ": " + order.reason().getString() : ""));
			h.assertValueEqual(TestSupport.count(owner, net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:white_bed"))), 1, "cama entregue");
			h.assertTrue(brown.stream().allMatch(net.minecraft.world.entity.LivingEntity::isAlive), "matou ovelha marrom atrás de lã branca");
			TestSupport.cleanup(h, owner);
		});
	}}
