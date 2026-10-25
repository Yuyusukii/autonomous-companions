package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.entity.CompanionEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** Combate mais esperto: ajuda no alvo da dona, protege a equipe, recua para comer e luta contra jogadores. */
public class CombatIntelligenceGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";

	/** A dona bate num hostil (que não está mirando ninguém): a companheira entra na luta no mesmo alvo. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 600)
	public void attacksWhatTheOwnerHits(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(4, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 4), "Iris");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_SWORD, 1);
		Husk husk = h.spawn(EntityTypes.HUSK, new BlockPos(9, FLOOR, 9), EntitySpawnReason.EVENT);
		husk.setNoAi(true);
		owner.attack(husk);
		h.succeedWhen(() -> {
			h.assertTrue(!husk.isAlive(), "o hostil que a dona atacou continua vivo (vida " + husk.getHealth() + ")");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Um hostil ataca outra companheira da mesma dona: esta vai ajudar. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 600)
	public void helpsAnotherCompanion(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity helper = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 10), "Iris");
		CompanionEntity victim = TestSupport.companion(h, owner, new BlockPos(18, FLOOR, 18), "Rosa");
		helper.settings().debug = true;
		TestSupport.give(helper, Items.IRON_SWORD, 1);
		Husk husk = h.spawn(EntityTypes.HUSK, new BlockPos(20, FLOOR, 20), EntitySpawnReason.EVENT);
		husk.setNoAi(true);
		husk.setTarget(victim);
		boolean[] engaged = {false};
		h.onEachTick(() -> {
			if (helper.brain().threats().target() == husk) {
				engaged[0] = true;
			}
		});
		h.succeedWhen(() -> {
			h.assertTrue(engaged[0], "não foi ajudar a outra companheira");
			h.assertTrue(!husk.isAlive(), "o hostil continua vivo");
			TestSupport.cleanup(h, owner);
		});
	}

	/** Com pouca vida e comida na bolsa, no meio da luta: recua, come e volta a lutar (e sobrevive). */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 1200)
	public void backsOffAndEatsWhenLow(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Iris");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_SWORD, 1);
		TestSupport.give(c, Items.COOKED_BEEF, 6);
		c.setHealth(6);
		c.needs().setHunger(12);
		Husk husk = h.spawn(EntityTypes.HUSK, new BlockPos(16, FLOOR, 12), EntitySpawnReason.EVENT);
		husk.setTarget(c);
		h.succeedWhen(() -> {
			h.assertTrue(c.isAlive(), "morreu");
			h.assertTrue(c.inventory().count(Items.COOKED_BEEF) < 6, "não comeu no meio da luta");
			h.assertTrue(!husk.isAlive(), "o hostil continua vivo");
			TestSupport.cleanup(h, owner);
		});
	}

	/** PvP: outra jogadora ataca a dona; a companheira parte para cima dela. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 600)
	public void defendsTheOwnerFromAPlayer(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(6, FLOOR, 6), GameType.SURVIVAL);
		ServerPlayer enemy = TestSupport.owner(h, new BlockPos(8, FLOOR, 6), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Iris");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_SWORD, 1);
		h.getLevel().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.PVP, true, h.getLevel().getServer());
		float before = enemy.getHealth();
		// Jogadora recém-entrada fica invulnerável por 3 s (regra do jogo): espera antes do ataque.
		h.startSequence()
			.thenIdle(80)
			.thenExecute(() -> {
				enemy.resetAttackStrengthTicker();
				enemy.attack(owner);
				h.assertTrue(owner.getHealth() < 20, "o ataque à dona não aconteceu");
			})
			.thenWaitUntil(() -> h.assertTrue(enemy.getHealth() < before, "a jogadora que atacou a dona não foi atingida"))
			.thenExecute(() -> TestSupport.cleanup(h, owner, enemy))
			.thenSucceed();
	}

	/** PvP com pouca vida: contra uma jogadora (a fuga automática só vale para monstros), recua, come e volta a lutar. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 600)
	public void eatsMidFightAgainstAPlayer(GameTestHelper h) {
		h.getLevel().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.PVP, true, h.getLevel().getServer());
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		ServerPlayer enemy = TestSupport.owner(h, new BlockPos(14, FLOOR, 12), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(12, FLOOR, 12), "Iris");
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_SWORD, 1);
		TestSupport.give(c, Items.COOKED_BEEF, 6);
		c.setHealth(6);
		c.needs().setHunger(12);
		c.brain().threats().onAttackedBy(enemy);
		float[] lowest = {c.getHealth()};
		h.onEachTick(() -> lowest[0] = Math.min(lowest[0], c.getHealth()));
		h.succeedWhen(() -> {
			h.assertTrue(c.isAlive(), "morreu");
			h.assertTrue(c.inventory().count(Items.COOKED_BEEF) < 6, "não comeu no meio da luta contra a jogadora");
			h.assertTrue(c.distanceTo(enemy) > 3 || c.getHealth() > 6, "não recuou nem recuperou vida");
			TestSupport.cleanup(h, owner, enemy);
		});
	}
}
