package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.ai.behavior.GuardPostBehavior;
import dev.autonomouscompanions.demon.DemonMode;
import dev.autonomouscompanions.demon.DemonState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionVariant;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Regras novas (pedido da jogadora em 18/10/2026): vida infinita, sem fome e sem cansaço; a de cabelo vermelho no Modo
 * Demônio toda noite; a ajudante como segurança das outras. Cada teste roda num lote próprio (a caçada noturna alcança
 * 1000 blocos e mataria os monstros dos testes vizinhos).
 */
public class SustainedModeGameTests {
	private static final String ARENA = "autonomouscompanions-test:arena";

	/** Desliga o "modo clássico" que a TestSupport liga para os testes das regras antigas. */
	private static CompanionEntity sustained(CompanionEntity c) {
		c.settings().classicSurvival = false;
		return c;
	}

	private static Mob still(GameTestHelper h, net.minecraft.world.entity.EntityType<? extends Mob> type, int x, int z) {
		Mob m = h.spawn(type, new BlockPos(x, FLOOR, z), EntitySpawnReason.EVENT);
		m.setNoAi(true);
		return m;
	}

	/** Golpes que antes a matariam não tiram vida; fome e cansaço não existem; mesmo imune, ela revida quem a ataca. */
	@GameTest(environment = "autonomouscompanions-test:ac_sustain1", structure = ARENA, maxTicks = 1200)
	public void infiniteLifeWithoutHungerOrFatigue(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(8, FLOOR, 8), "Rosa"));
		c.settings().debug = true;
		TestSupport.give(c, Items.IRON_SWORD, 1);
		ServerLevel level = h.getLevel();
		float max = c.getMaxHealth();
		Mob husk = h.spawn(EntityTypes.HUSK, new BlockPos(12, FLOOR, 8), EntitySpawnReason.EVENT);
		boolean hurt = c.hurtServer(level, c.damageSources().mobAttack(husk), 500.0F);
		hurt |= c.hurtServer(level, c.damageSources().fall(), 100.0F);
		hurt |= c.hurtServer(level, c.damageSources().lava(), 100.0F);
		h.assertTrue(!hurt, "um golpe a feriu");
		h.assertValueEqual(c.getHealth(), max, "vida depois dos golpes");
		c.needs().setHunger(2);
		c.needs().setFatigue(95);
		float[] lowest = {c.getHealth()};
		int[] lowestHunger = {20};
		float[] highestFatigue = {0};
		int[] ticks = {0};
		h.onEachTick(() -> {
			lowest[0] = Math.min(lowest[0], c.getHealth());
			if (++ticks[0] > 3) {
				lowestHunger[0] = Math.min(lowestHunger[0], c.needs().hunger());
				highestFatigue[0] = Math.max(highestFatigue[0], c.needs().fatigue());
			}
		});
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(!husk.isAlive(), "o husk que a atacou continua vivo (ela não revidou)"))
			.thenIdle(200)
			.thenExecute(() -> {
				h.assertTrue(c.isAlive(), "morreu");
				h.assertTrue(lowest[0] >= max, "perdeu vida: " + lowest[0]);
				h.assertValueEqual(lowestHunger[0], 20, "menor fome depois do primeiro tick");
				h.assertTrue(highestFatigue[0] == 0, "cansaço chegou a " + highestFatigue[0]);
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/**
	 * Toda noite: a de cabelo vermelho desperta sozinha (sem golpe fatal), elimina os monstros em volta — inclusive um a
	 * 220 blocos, além do raio antigo de 100 —, poupa animais e aldeões, não dorme e, ao amanhecer, volta ao normal perto
	 * da dona.
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_sustain2", structure = ARENA, maxTicks = 3000)
	public void redHairedOneHuntsEveryNightAndNeverSleeps(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity c = sustained(TestSupport.companion(h, owner, new BlockPos(6, FLOOR, 6), "Mak"));
		c.setVariant(CompanionVariant.RUBI);
		c.settings().debug = true;
		h.assertTrue(DemonMode.canHave(c), "precisa ser a de cabelo vermelho");
		ServerLevel level = h.getLevel();
		h.assertTrue(DemonMode.isNight(level), "o ambiente do teste deveria ser noite");
		List<Mob> hostiles = new ArrayList<>();
		hostiles.add(still(h, EntityTypes.HUSK, 14, 6));
		hostiles.add(still(h, EntityTypes.ZOMBIE, 6, 18));
		hostiles.add(still(h, EntityTypes.SKELETON, 20, 20));
		Mob cow = still(h, EntityTypes.COW, 10, 12);
		Mob villager = still(h, EntityTypes.VILLAGER, 12, 10);
		// Um monstro longe (220 blocos), num chunk mantido carregado só durante o teste.
		BlockPos far = h.absolutePos(new BlockPos(6, FLOOR, 6)).offset(220, 0, 0);
		level.setChunkForced(far.getX() >> 4, far.getZ() >> 4, true);
		Mob farHusk = EntityTypes.HUSK.create(level, EntitySpawnReason.EVENT);
		farHusk.snapTo(Vec3.atBottomCenterOf(far), 0, 0);
		farHusk.setNoAi(true);
		farHusk.setNoGravity(true);
		farHusk.setPersistenceRequired();
		level.addFreshEntity(farHusk);
		hostiles.add(farHusk);
		boolean[] slept = {false};
		h.onEachTick(() -> slept[0] |= c.isSleeping() || c.demonState() == DemonState.SLEEPING);
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(c.demonState() == DemonState.ACTIVE && c.demon().nightly(), "Modo Demônio noturno ainda não ativo: " + c.demonState()))
			.thenWaitUntil(() -> {
				long alive = hostiles.stream().filter(Mob::isAlive).count();
				h.assertTrue(alive == 0, "monstros vivos: " + alive + (farHusk.isAlive() ? " (inclusive o de 220 blocos)" : ""));
			})
			.thenExecute(() -> {
				h.assertTrue(cow.isAlive() && villager.isAlive(), "matou a vaca ou o aldeão");
				// Amanhece: o relógio do mundo vai para a manhã seguinte.
				var clock = level.dimensionType().defaultClock().orElseThrow();
				long t = level.getDefaultClockTime();
				level.getServer().clockManager().setTotalTicks(clock, t - Math.floorMod(t, 24000L) + 24000L + 1000L);
			})
			.thenWaitUntil(() -> h.assertTrue(c.demonState() == DemonState.NORMAL, "ainda no Modo Demônio depois de amanhecer: " + c.demonState()))
			.thenIdle(100)
			.thenExecute(() -> {
				h.assertTrue(c.demonState() == DemonState.NORMAL, "voltou ao Modo Demônio de dia");
				h.assertTrue(!slept[0], "dormiu (a noite não deveria cansá-la)");
				h.assertTrue(c.needs().fatigue() == 0, "cansaço " + c.needs().fatigue());
				h.assertValueEqual(c.getHealth(), c.getMaxHealth(), "vida depois da noite");
				h.assertTrue(c.distanceTo(owner) < 30, "longe da dona ao amanhecer: " + (int) c.distanceTo(owner));
				level.setChunkForced(far.getX() >> 4, far.getZ() >> 4, false);
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}

	/**
	 * Ajudante como segurança: um monstro parado perto de uma companheira (sem atacar ninguém) é eliminado pela ajudante;
	 * a vaca ao lado fica viva; ela fica de posto junto da companheira e não abastece ninguém — mesmo carregando pedregulho
	 * e troncos, com que, nas regras antigas, faria e entregaria itens de pedra.
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_sustain3", structure = ARENA, maxTicks = 1600)
	public void helperGuardsTheOthers(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.CREATIVE);
		CompanionEntity a = sustained(TestSupport.companion(h, owner, new BlockPos(16, FLOOR, 16), "Rosa"));
		CompanionEntity helper = sustained(TestSupport.helper(h, owner, new BlockPos(4, FLOOR, 4)));
		helper.settings().debug = true;
		TestSupport.give(helper, Items.IRON_SWORD, 1);
		TestSupport.give(helper, Items.COBBLESTONE, 16);
		TestSupport.give(helper, Items.OAK_LOG, 8);
		Mob husk = still(h, EntityTypes.HUSK, 20, 18);
		Mob cow = still(h, EntityTypes.COW, 13, 16);
		boolean[] hitByHelper = {false};
		h.onEachTick(() -> hitByHelper[0] |= husk.getLastHurtByMob() == helper);
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(!husk.isAlive(), "o monstro perto da companheira continua vivo"))
			.thenIdle(300)
			.thenExecute(() -> {
				h.assertTrue(hitByHelper[0], "quem eliminou o monstro não foi a ajudante");
				h.assertTrue(cow.isAlive(), "atacou a vaca");
				int stoneTools = a.inventory().count(Items.STONE_PICKAXE) + a.inventory().count(Items.STONE_SWORD) + a.inventory().count(Items.STONE_AXE)
					+ a.inventory().count(Items.STONE_SHOVEL);
				h.assertValueEqual(stoneTools, 0, "itens de pedra entregues pela ajudante (ela não abastece mais)");
				GuardPostBehavior post = helper.brain().behavior(GuardPostBehavior.class);
				h.assertTrue(post != null && post.ward() == a, "a ajudante não está de posto junto da companheira");
				h.assertTrue(helper.distanceTo(a) < 10, "a ajudante longe da companheira: " + (int) helper.distanceTo(a));
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}
}
