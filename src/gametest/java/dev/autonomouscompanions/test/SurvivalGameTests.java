package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.persist.CompanionWorldData;
import dev.autonomouscompanions.registry.ModEntities;
import dev.autonomouscompanions.survival.DeathRecovery;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderService;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Testes 18 a 22 e 27: fome, comida, sono, armas e armaduras, proteção da dona e integridade após a morte. */
public class SurvivalGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String NIGHT = "autonomouscompanions-test:ac_night";
	private static final String ARENA = "autonomouscompanions-test:arena";

	/** Teste 18: a fome cai com o esforço, faminta ela come o que tem e sem comida a fome causa dano. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 1200)
	public void hungerDropsAndSheEatsWhenHungry(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(8, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity fed = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 10), "Bia");
		CompanionEntity starving = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 14), "Gabi");
		CompanionEntity worker = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Lara");
		TestSupport.give(fed, Items.BREAD, 3);
		fed.needs().setHunger(4);
		starving.needs().setHunger(0);
		worker.needs().addExhaustion(40.0f);
		float startHealth = starving.getHealth();
		h.succeedWhen(() -> {
			h.assertTrue(fed.inventory().count(Items.BREAD) < 3, "faminta com pão e não comeu");
			h.assertTrue(fed.needs().hunger() > 4, "a fome não subiu depois de comer: " + fed.needs().hunger());
			h.assertTrue(fed.stats().meals >= 1, "refeição não registrada");
			h.assertTrue(worker.needs().hunger() < 20, "o esforço não reduziu a fome");
			h.assertTrue(starving.getHealth() < startHealth, "fome zerada sem comida deveria causar dano");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Teste 19 (preparo): fabrica bancada e fornalha, assa carne de verdade e entrega a quantidade pedida. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3600)
	public void cooksMeatInARealFurnace(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(8, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(11, FLOOR, 12), "Paula");
		c.settings().debug = true;
		TestSupport.give(c, Items.BEEF, 2);
		TestSupport.give(c, Items.COBBLESTONE, 8);
		TestSupport.give(c, Items.OAK_LOG, 1);
		TestSupport.give(c, Items.COAL, 2);
		h.assertTrue(OrderService.createItemOrder(h.getLevel(), c, "minecraft:cooked_beef", 2) == null, "ordem de carne assada recusada");
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertValueEqual(TestSupport.count(owner, Items.COOKED_BEEF), 2, "carne assada entregue");
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem não concluída: " + order.status());
			h.assertValueEqual(c.inventory().count(Items.BEEF), 0, "a carne crua deveria ter ido para a fornalha");
			h.assertValueEqual(c.inventory().count(Items.COBBLESTONE), 0, "o pedregulho deveria ter virado fornalha");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Teste 19 (caça): caça vacas para obter carne e entrega exatamente o pedido. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3000)
	public void huntsForMeat(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(6, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(8, FLOOR, 12), "Kira");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_SWORD, 1);
		for (int i = 0; i < 4; i++) {
			h.spawn(EntityTypes.COW, new BlockPos(16 + i % 2 * 2, FLOOR, 8 + i * 2), EntitySpawnReason.EVENT);
		}
		h.assertTrue(OrderService.createItemOrder(h.getLevel(), c, "minecraft:beef", 2) == null, "ordem de carne recusada");
		Order order = c.orders().all().getFirst();
		h.succeedWhen(() -> {
			h.assertValueEqual(TestSupport.count(owner, Items.BEEF), 2, "carne entregue");
			h.assertTrue(order.status() == OrderStatus.COMPLETED, "ordem não concluída: " + order.status());
			h.assertTrue(h.getEntities(EntityTypes.COW).size() < 4, "nenhuma vaca foi caçada");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Teste 20: exausta à noite, deita numa cama e se recupera, sem alterar o horário do mundo. */
	@GameTest(environment = NIGHT, structure = ARENA, maxTicks = 1600)
	public void sleepsInBedWithoutChangingWorldTime(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(6, FLOOR, 6), GameType.SURVIVAL);
		BlockPos foot = new BlockPos(16, FLOOR, 12);
		BlockState footState = Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT);
		h.setBlock(foot, footState);
		h.setBlock(foot.east(), footState.setValue(BedBlock.PART, BedPart.HEAD));
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 12), "Nara");
		c.needs().setFatigue(95.0f);
		long startTime = h.getLevel().getOverworldClockTime();
		long startTick = h.getLevel().getGameTime();
		float[] lowest = {100};
		h.succeedWhen(() -> {
			lowest[0] = Math.min(lowest[0], c.needs().fatigue());
			h.assertTrue(c.isSleeping(), "não está dormindo (estado " + c.companionState() + ")");
			h.assertTrue(c.companionState() == CompanionState.SLEEPING, "estado deveria ser dormindo");
			h.assertTrue(c.needs().fatigue() < 90.0f, "o cansaço não está diminuindo: " + c.needs().fatigue());
			long elapsedTicks = h.getLevel().getGameTime() - startTick;
			long elapsedClock = h.getLevel().getOverworldClockTime() - startTime;
			h.assertTrue(Math.abs(elapsedClock - elapsedTicks) <= 2, "o horário do mundo foi alterado: relógio " + elapsedClock + " vs ticks " + elapsedTicks);
			TestSupport.cleanup(h, owner);
		});
	}

	/** Teste 21: fabrica arma e armaduras proporcionais ao risco, equipa e usa em combate. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 4000)
	public void craftsEquipsAndUsesWeaponsAndArmor(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(4, FLOOR, 4), GameType.CREATIVE);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(8, FLOOR, 8), "Sol");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_INGOT, 32);
		TestSupport.give(c, Items.OAK_LOG, 4);
		TestSupport.give(c, Items.STICK, 4);
		c.settings().proactiveDefense = true;
		ServerNetworking.handleCommand(owner, new Payloads.SimpleCommand(c.getId(), Payloads.SimpleCommand.EQUIP));
		Zombie[] zombie = {null};
		h.startSequence()
			.thenWaitUntil(() -> {
				ItemStack chest = c.getItemBySlot(EquipmentSlot.CHEST);
				h.assertTrue(!chest.isEmpty(), "peitoral não equipado");
				h.assertTrue(!c.getItemBySlot(EquipmentSlot.HEAD).isEmpty() || !c.getItemBySlot(EquipmentSlot.LEGS).isEmpty(), "outras peças não equipadas");
				ItemStack hand = c.getItemBySlot(EquipmentSlot.MAINHAND);
				h.assertTrue(hand.is(Items.IRON_SWORD) || hand.is(Items.IRON_AXE), "arma de ferro não empunhada: " + hand);
			})
			.thenExecute(() -> {
				h.assertTrue(c.inventory().count(Items.IRON_INGOT) < 32, "não usou o ferro");
				// A ordem pode continuar buscando peças melhores (ex.: diamante); a dona a encerra para o teste de combate.
				for (var o : c.orders().all()) {
					if (!o.status().isFinished()) {
						ServerNetworking.handleOrderControl(owner, new Payloads.OrderControl(c.getId(), o.id(), Payloads.OrderControl.CANCEL));
					}
				}
				zombie[0] = h.spawn(EntityTypes.ZOMBIE, new BlockPos(16, FLOOR, 16), EntitySpawnReason.EVENT);
				zombie[0].setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
			})
			.thenWaitUntil(() -> h.assertTrue(!zombie[0].isAlive(), "o zumbi ainda está vivo"))
			.thenExecute(() -> {
				h.assertTrue(c.isAlive(), "a companheira morreu no combate");
				h.assertTrue(c.stats().kills >= 1, "abate não registrado");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/** Teste 22: quando um monstro ataca a dona, ela reage e a protege. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 1200)
	public void protectsOwnerFromMonster(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(12, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(9, FLOOR, 12), "Íris");
		c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		c.settings().proactiveDefense = false;
		Zombie zombie = h.spawn(EntityTypes.ZOMBIE, new BlockPos(18, FLOOR, 12), EntitySpawnReason.EVENT);
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		zombie.setTarget(owner);
		boolean[] protecting = {false};
		h.succeedWhen(() -> {
			if (c.companionState() == CompanionState.PROTECTING_OWNER) {
				protecting[0] = true;
			}
			h.assertTrue(!zombie.isAlive(), "o zumbi ainda está vivo");
			h.assertTrue(protecting[0], "não entrou no estado de proteção da dona");
			h.assertTrue(owner.isAlive(), "a dona morreu");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Teste 27: depois de morrer, revive uma única vez com a mesma identidade, nome e inventário. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 400)
	public void deathKeepsDataAndRevivesWithoutDuplicates(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(8, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Zoe");
		TestSupport.give(c, Items.DIAMOND, 10);
		c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
		UUID id = c.getUUID();
		var server = h.getLevel().getServer();
		h.startSequence()
			.thenExecute(() -> {
				h.kill(c);
				// Durante a animação de morte o corpo ainda existe: reviver agora precisa ser recusado sem perder o registro.
				h.assertTrue(DeathRecovery.revive(server, id, owner) == null, "reviveu enquanto o corpo ainda existia");
				CompanionWorldData.CompanionRecord r = CompanionWorldData.get(server).record(id);
				h.assertTrue(r != null && r.dead() && r.snapshot().isPresent(), "registro de morte ausente");
			})
			.thenWaitUntil(() -> h.assertTrue(h.getLevel().getEntity(id) == null, "o corpo ainda não foi removido"))
			.thenExecute(() -> {
				h.assertValueEqual(TestSupport.itemEntities(h, Items.DIAMOND), 0, "itens caíram no chão (inventário deveria ser mantido)");
				h.assertTrue(CompanionWorldData.get(server).record(id).dead(), "o registro deveria continuar como morta");
				CompanionEntity back = DeathRecovery.revive(server, id, owner);
				h.assertTrue(back != null, "não reviveu");
				h.assertValueEqual(back.getUUID(), id, "UUID após reviver");
				h.assertValueEqual(back.companionName(), "Zoe", "nome após reviver");
				h.assertValueEqual(back.inventory().count(Items.DIAMOND), 10, "inventário após reviver");
				boolean sword = back.mainHandItem().is(Items.IRON_SWORD) || back.inventory().count(Items.IRON_SWORD) == 1;
				h.assertTrue(sword, "a espada sumiu");
				h.assertTrue(back.getHealth() > 0 && back.isAlive(), "reviveu sem vida");
				h.assertTrue(back.isOwnedBy(owner), "perdeu a dona");
				h.assertTrue(back.stats().deaths == 1, "contador de mortes: " + back.stats().deaths);
				h.assertTrue(DeathRecovery.revive(server, id, owner) == null, "reviveu duas vezes");
				long copies = h.getLevel().getEntities(ModEntities.COMPANION, e -> e.getUUID().equals(id)).size();
				h.assertValueEqual((int) copies, 1, "cópias da companheira");
				h.assertTrue(!CompanionWorldData.get(server).record(id).dead(), "registro não marcado como viva");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}
}
