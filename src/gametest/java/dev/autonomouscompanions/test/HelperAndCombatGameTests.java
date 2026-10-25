package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.combat.Archery;
import dev.autonomouscompanions.combat.CombatHelper;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.item.WhistleItem;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.survival.FoodHelper;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.OrderService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** A ajudante, o apito com escolha, o combate com arco/crítico e comer para se curar. */
public class HelperAndCombatGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";
	private static final String CORRIDOR = "autonomouscompanions-test:corridor";

	/** Companheira com fome e sem comida: a ajudante leva carne do estoque dela. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 800)
	public void helperBringsFoodToHungryCompanion(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(4, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 4), "Lia");
		CompanionEntity helper = TestSupport.helper(h, owner, new BlockPos(18, FLOOR, 18));
		helper.settings().debug = true;
		c.needs().setHunger(9);
		// Ela já tem as ferramentas de pedra, então o único pedido é comida.
		for (var item : new net.minecraft.world.item.Item[]{Items.STONE_PICKAXE, Items.STONE_SWORD, Items.STONE_AXE, Items.STONE_SHOVEL}) {
			TestSupport.give(c, item, 1);
		}
		TestSupport.give(helper, Items.COOKED_BEEF, 12);
		h.succeedWhen(() -> {
			h.assertTrue(c.needs().hunger() > 9 || FoodHelper.freeFoodCount(c) > 0, "não recebeu comida (fome " + c.needs().hunger() + ")");
			h.assertTrue(helper.inventory().count(Items.COOKED_BEEF) < 12, "a ajudante não entregou nada");
			h.assertValueEqual(helper.inventory().count(Items.COOKED_BEEF) + c.inventory().count(Items.COOKED_BEEF) + c.stats().meals, 12, "carne sumiu ou duplicou");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Sem estoque: a ajudante caça na hora e entrega a carne para quem está com fome. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3000)
	public void helperHuntsMeatWhenOutOfStock(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(4, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 4), "Kira");
		CompanionEntity helper = TestSupport.helper(h, owner, new BlockPos(8, FLOOR, 8));
		helper.settings().debug = true;
		c.needs().setHunger(8);
		for (var item : new net.minecraft.world.item.Item[]{Items.STONE_PICKAXE, Items.STONE_SWORD, Items.STONE_AXE, Items.STONE_SHOVEL}) {
			TestSupport.give(c, item, 1);
		}
		for (int i = 0; i < 4; i++) {
			h.spawn(EntityTypes.COW, new BlockPos(16 + i % 2 * 3, FLOOR, 14 + i * 2), EntitySpawnReason.EVENT);
		}
		h.succeedWhen(() -> {
			h.assertTrue(h.getEntities(EntityTypes.COW).size() < 4, "a ajudante não caçou");
			h.assertTrue(c.needs().hunger() > 8 || FoodHelper.freeFoodCount(c) > 0, "a carne não chegou à companheira");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Companheira sem ferramentas: a ajudante fabrica e entrega picareta, espada, machado e pá de pedra. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3600)
	public void helperCraftsAndDeliversStoneItems(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(4, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 4), "Cora");
		CompanionEntity helper = TestSupport.helper(h, owner, new BlockPos(12, FLOOR, 12));
		helper.settings().debug = true;
		TestSupport.give(c, Items.BREAD, 10); // comida suficiente: o pedido é só de itens de pedra
		TestSupport.give(helper, Items.COBBLESTONE, 12);
		TestSupport.give(helper, Items.OAK_LOG, 4);
		TestSupport.give(helper, Items.COOKED_BEEF, 10);
		h.succeedWhen(() -> {
			for (var item : new net.minecraft.world.item.Item[]{Items.STONE_PICKAXE, Items.STONE_SWORD, Items.STONE_AXE, Items.STONE_SHOVEL}) {
				h.assertTrue(c.inventory().count(item) + (c.mainHandItem().is(item) ? 1 : 0) == 1, "faltou entregar " + item);
			}
			h.assertValueEqual(TestSupport.itemEntities(h, Items.STONE_PICKAXE), 0, "itens largados no chão");
			TestSupport.cleanup(h, owner);
		});
	}

	/** O apito pode chamar só uma: ela larga a ordem e vem; a outra continua trabalhando. A ajudante não atende. */
	@GameTest(environment = DAY, structure = CORRIDOR, maxTicks = 900)
	public void whistleCallsOnlyTheChosenOne(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(3, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity a = TestSupport.companion(h, owner, new BlockPos(30, FLOOR, 3), "Ana");
		CompanionEntity b = TestSupport.companion(h, owner, new BlockPos(30, FLOOR, 5), "Bel");
		CompanionEntity helper = TestSupport.helper(h, owner, new BlockPos(6, FLOOR, 6));
		for (CompanionEntity c : new CompanionEntity[]{a, b}) {
			TestSupport.give(c, Items.WOODEN_PICKAXE, 1);
			OrderService.createGather(h.getLevel(), c, "minecraft:stone", GatherMode.DROPS, 30, SearchProfile.COMMON, 0, false);
		}
		for (int x = 60; x <= 66; x++) {
			h.setBlock(new BlockPos(x, FLOOR, 2), Blocks.STONE);
			h.setBlock(new BlockPos(x, FLOOR, 6), Blocks.STONE);
		}
		Order oa = a.orders().all().getFirst();
		Order ob = b.orders().all().getFirst();
		h.assertTrue(WhistleItem.companionsOf(owner).stream().noneMatch(CompanionEntity::isHelper), "a ajudante apareceu no menu do apito");
		h.assertValueEqual(WhistleItem.entries(owner).size(), 2, "companheiras no menu do apito");
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(oa.status() == OrderStatus.ACTIVE && ob.status() == OrderStatus.ACTIVE, "ordens ainda não começaram"))
			.thenExecute(() -> {
				owner.getInventory().add(new ItemStack(dev.autonomouscompanions.registry.ModItems.WHISTLE));
				// O mesmo caminho do botão do menu (pacote validado no servidor).
				WhistleItem.handleCall(owner, false, a.getUUID());
				h.assertTrue(oa.status() == OrderStatus.SUSPENDED, "a escolhida não largou (suspendeu) a ordem: " + oa.status());
				h.assertTrue(ob.status() == OrderStatus.ACTIVE, "a outra também foi chamada: " + ob.status());
				h.assertTrue(!helper.brain().isRecalled(h.getLevel()), "a ajudante atendeu ao apito");
				// A ajudante não aceita pacotes da tela (nome, ordens).
				ServerNetworking.handleRename(owner, new Payloads.Rename(helper.getId(), "Outra"));
				h.assertTrue(helper.companionName().equals(dev.autonomouscompanions.spawn.StarterSpawner.HELPER_NAME), "a ajudante foi renomeada");
			})
			.thenWaitUntil(() -> h.assertTrue(a.distanceTo(owner) <= 4.0, "a escolhida ainda está longe: " + a.distanceTo(owner)))
			.thenExecute(() -> {
				h.assertTrue(ob.status() == OrderStatus.ACTIVE || ob.status() == OrderStatus.COMPLETED, "a outra parou de trabalhar: " + ob.status());
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/** Golpe crítico (caindo de um pulo) causa 50% a mais de dano que o golpe normal com a mesma espada. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 200)
	public void criticalHitDealsMoreDamage(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(4, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Iara");
		c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
		// Alvos sem armadura (a armadura reduz o dano de forma não proporcional).
		var normal = h.spawn(EntityTypes.COW, new BlockPos(13, FLOOR, 12), EntitySpawnReason.EVENT);
		var crit = h.spawn(EntityTypes.COW, new BlockPos(11, FLOOR, 12), EntitySpawnReason.EVENT);
		normal.setNoAi(true);
		crit.setNoAi(true);
		h.runAfterDelay(2, () -> {
		float before = normal.getHealth();
		int[] cd = {0};
			h.assertTrue(CombatHelper.tryAttack(h.getLevel(), c, normal, cd, false, false), "golpe normal não aconteceu");
		float normalDamage = before - normal.getHealth();
		cd[0] = 0;
		float before2 = crit.getHealth();
		h.assertTrue(CombatHelper.tryAttack(h.getLevel(), c, crit, cd, true, false), "golpe crítico não aconteceu");
		float critDamage = before2 - crit.getHealth();
		h.assertTrue(normalDamage > 0, "o golpe normal não causou dano");
		h.assertTrue(Math.abs(critDamage - normalDamage * 1.5f) < 0.01f, "crítico deveria causar 1,5× (" + normalDamage + " → " + critDamage + ")");
		// O bônus é temporário: o próximo golpe normal volta ao dano de antes.
		h.assertTrue(c.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)
			== c.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).getValue(), "atributo inconsistente");
		h.assertTrue(c.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).getModifiers().isEmpty()
			|| c.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).getModifiers().stream()
				.noneMatch(m -> m.id().getPath().equals("critical_hit")), "o bônus de crítico ficou no atributo");
		TestSupport.cleanup(h, owner);
		h.succeed();
		});
	}

	/** Ferida (40% de vida) com arco: não troca golpes, mantém distância e mata o inimigo a flechadas. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 1600)
	public void woundedCompanionKitesWithBow(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 12), "Mira");
		c.settings().debug = true;
		TestSupport.give(c, Items.BOW, 1);
		TestSupport.give(c, Items.ARROW, 48);
		TestSupport.give(c, Items.STONE_SWORD, 1);
		c.setHealth(8.0F);
		c.needs().setHunger(17); // abaixo de 18 não há regeneração: a vida fica baixa durante a luta
		Husk husk = h.spawn(EntityTypes.HUSK, new BlockPos(18, FLOOR, 12), EntitySpawnReason.EVENT);
		husk.setTarget(c);
		boolean[] usedSword = {false};
		h.onEachTick(() -> {
			if (husk.isAlive() && c.mainHandItem().is(Items.STONE_SWORD) && c.distanceTo(husk) < 3) {
				if (!usedSword[0]) {
					c.debug("[teste] espada na mão perto do husk; comportamento " + (c.brain().active() == null ? "-" : c.brain().active().name()));
				}
				usedSword[0] = true;
			}
		});
		h.succeedWhen(() -> {
			h.assertTrue(!husk.isAlive(), "o husk ainda está vivo");
			h.assertTrue(Archery.arrows(c) < 48, "não atirou nenhuma flecha");
			h.assertTrue(!usedSword[0], "foi para cima na espada mesmo ferida");
			h.assertTrue(c.isAlive(), "morreu");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Creeper: ela nunca chega perto; derruba com flechas antes da explosão. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 1600)
	public void shootsCreepersFromAfar(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(5, FLOOR, 12), "Tati");
		c.settings().debug = true;
		TestSupport.give(c, Items.BOW, 1);
		TestSupport.give(c, Items.ARROW, 48);
		TestSupport.give(c, Items.STONE_SWORD, 1);
		Creeper creeper = h.spawn(EntityTypes.CREEPER, new BlockPos(19, FLOOR, 12), EntitySpawnReason.EVENT);
		creeper.setTarget(c);
		float start = c.getHealth();
		h.succeedWhen(() -> {
			h.assertTrue(!creeper.isAlive(), "o creeper ainda está vivo");
			h.assertTrue(Archery.arrows(c) < 48, "não usou o arco");
			h.assertTrue(c.getHealth() >= start - 1.0F, "levou dano da explosão: " + c.getHealth());
			TestSupport.cleanup(h, owner);
		});
	}

	/** Ferida e com comida: come na hora (mesmo com uma ordem em andamento) até encher a fome, e a vida volta. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 1200)
	public void woundedCompanionEatsToHeal(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(4, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 4), "Nina");
		c.settings().debug = true;
		TestSupport.give(c, Items.COOKED_BEEF, 5);
		TestSupport.give(c, Items.WOODEN_PICKAXE, 1);
		for (int z = 3; z <= 20; z++) {
			h.setBlock(new BlockPos(20, FLOOR, z), Blocks.STONE);
		}
		OrderService.createGather(h.getLevel(), c, "minecraft:stone", GatherMode.DROPS, 18, SearchProfile.COMMON, 0, false);
		c.setHealth(6.0F);
		c.needs().setHunger(15);
		h.startSequence()
			.thenWaitUntil(() -> h.assertValueEqual(c.needs().hunger(), 20, "não comeu até encher a fome"))
			.thenExecute(() -> h.assertTrue(c.inventory().count(Items.COOKED_BEEF) < 5, "a fome subiu sem comer?"))
			.thenWaitUntil(() -> h.assertTrue(c.getHealth() >= 12.0F, "a vida não voltou: " + c.getHealth()))
			.thenExecute(() -> TestSupport.cleanup(h, owner))
			.thenSucceed();
	}

	/** Ferida, com fome e sem comida: vai caçar (obrigatório) e come. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 3000)
	public void woundedCompanionWithoutFoodHuntsAndEats(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(4, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Hana");
		c.settings().debug = true;
		TestSupport.give(c, Items.STONE_SWORD, 1);
		for (int i = 0; i < 4; i++) {
			h.spawn(EntityTypes.COW, new BlockPos(16 + i % 2 * 3, FLOOR, 14 + i * 2), EntitySpawnReason.EVENT);
		}
		c.setHealth(7.0F);
		c.needs().setHunger(12);
		h.succeedWhen(() -> {
			h.assertTrue(h.getEntities(EntityTypes.COW).size() < 4, "não caçou");
			h.assertTrue(c.needs().hunger() > 12, "não comeu (fome " + c.needs().hunger() + ")");
			TestSupport.cleanup(h, owner);
		});
	}
}
