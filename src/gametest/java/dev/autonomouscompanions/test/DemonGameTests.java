package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.demon.DemonMode;
import dev.autonomouscompanions.demon.DemonState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionVariant;
import dev.autonomouscompanions.item.WhistleItem;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.GameType;

/** Testes A–J do Modo Demônio (habilidade exclusiva da companheira de cabelo vermelho). */
public class DemonGameTests {
	// Lote próprio: a caçada tem raio de 100 blocos e alcançaria os mobs dos testes das arenas vizinhas.
	private static final String DAY = "autonomouscompanions-test:ac_demon";
	private static final String ARENA = "autonomouscompanions-test:arena";

	private static CompanionEntity rubi(GameTestHelper h, ServerPlayer owner, BlockPos at, String name) {
		CompanionEntity c = TestSupport.companion(h, owner, at, name);
		c.setVariant(CompanionVariant.RUBI);
		c.settings().debug = true;
		return c;
	}

	private static Husk husk(GameTestHelper h, BlockPos at) {
		Husk z = h.spawn(EntityTypes.HUSK, at, EntitySpawnReason.EVENT);
		z.setNoAi(true);
		return z;
	}

	/** Golpe de um hostil (fonte de dano em combate). */
	private static boolean hit(GameTestHelper h, CompanionEntity c, LivingEntity attacker, float damage) {
		return c.hurtServer(h.getLevel(), c.damageSources().mobAttack(attacker), damage);
	}

	/** A: em combate, chega a 1 coração → transforma, sobrevive e recebe 100 corações; a chuva começa. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 200)
	public void activatesAtOneHeart(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = rubi(h, owner, new BlockPos(12, FLOOR, 12), "Rubi");
		Husk z = husk(h, new BlockPos(13, FLOOR, 12));
		c.setHealth(5.0F);
		hit(h, c, z, 3.5F);
		h.assertTrue(c.isAlive(), "morreu");
		h.assertTrue(c.demonState() == DemonState.TRANSFORMING, "não transformou: " + c.demonState());
		h.assertValueEqual((int) c.getMaxHealth(), 200, "vida máxima (100 corações)");
		h.assertValueEqual((int) c.getHealth(), 200, "vida atual");
		h.assertTrue(c.demon().weatherActive() && h.getLevel().getServer().getWeatherData().isRaining() && h.getLevel().getServer().getWeatherData().isThundering(), "a tempestade não começou");
		// Não ativa de novo nem reinicia o tempo durante o modo.
		long start = c.demon().activatedAt();
		c.setHealth(1.0F);
		hit(h, c, z, 3.0F);
		h.assertValueEqual(c.demon().activatedAt(), start, "reativou durante o modo");
		h.assertTrue(c.getHealth() == 1.0F, "levou dano protegida");
		TestSupport.cleanup(h, owner);
		h.succeed();
	}

	/** B: um golpe que mataria de uma vez (vida cheia → muito abaixo de zero) é interceptado antes da morte. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 200)
	public void fatalBlowIsIntercepted(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = rubi(h, owner, new BlockPos(12, FLOOR, 12), "Rubi");
		Husk z = husk(h, new BlockPos(13, FLOOR, 12));
		hit(h, c, z, 500.0F);
		h.assertTrue(c.isAlive() && !c.isDeadOrDying() && !c.isRemoved(), "morreu com o golpe fatal");
		h.assertTrue(c.demonState() == DemonState.TRANSFORMING, "não transformou: " + c.demonState());
		h.assertValueEqual((int) c.getHealth(), 200, "vida após a interceptação");
		var record = dev.autonomouscompanions.persist.CompanionWorldData.get(h.getLevel().getServer()).record(c.getUUID());
		h.assertTrue(record == null || !record.dead(), "a morte foi registrada");
		h.runAfterDelay(5, () -> {
			h.assertTrue(c.isAlive() && c.deathTime == 0, "entrou na animação de morte");
			TestSupport.cleanup(h, owner);
			h.succeed();
		});
	}

	/** C e D: caça todos os hostis da área por teletransporte e mata na hora; aliados e passivos ficam intactos. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 600)
	public void huntsHostilesAndSparesAllies(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = rubi(h, owner, new BlockPos(3, FLOOR, 12), "Rubi");
		CompanionEntity friend = TestSupport.companion(h, owner, new BlockPos(5, FLOOR, 4), "Lua");
		CompanionEntity helper = TestSupport.helper(h, owner, new BlockPos(6, FLOOR, 20));
		List<Husk> hostiles = new ArrayList<>();
		int[][] spots = {{20, 3}, {20, 20}, {12, 20}, {18, 10}, {10, 4}, {21, 15}};
		for (int[] s : spots) {
			hostiles.add(husk(h, new BlockPos(s[0], FLOOR, s[1])));
		}
		Mob cow = h.spawn(EntityTypes.COW, new BlockPos(15, FLOOR, 15), EntitySpawnReason.EVENT);
		Mob villager = h.spawn(EntityTypes.VILLAGER, new BlockPos(9, FLOOR, 16), EntitySpawnReason.EVENT);
		var wolf = h.spawn(EntityTypes.WOLF, new BlockPos(16, FLOOR, 6), EntitySpawnReason.EVENT);
		wolf.tame(owner);
		for (Mob m : new Mob[]{cow, villager, wolf}) {
			m.setNoAi(true);
		}
		hit(h, c, hostiles.getFirst(), 500.0F);
		h.assertTrue(c.demonState() == DemonState.TRANSFORMING, "não transformou");
		h.assertTrue(!c.demon().isEligible(cow) && !c.demon().isEligible(villager) && !c.demon().isEligible(wolf) && !c.demon().isEligible(friend)
			&& !c.demon().isEligible(helper), "seleção incluiu aliados ou passivos");
		double[] start = {c.getX(), c.getZ()};
		boolean[] moved = {false};
		h.onEachTick(() -> moved[0] |= Math.abs(c.getX() - start[0]) + Math.abs(c.getZ() - start[1]) > 4);
		h.succeedWhen(() -> {
			for (Husk z : hostiles) {
				h.assertTrue(!z.isAlive(), "hostil ainda vivo");
			}
			h.assertTrue(c.demon().kills() > 0 && c.demon().kills() == c.demon().executedCount(), "eliminações: " + c.demon().kills());
			h.assertTrue(moved[0], "não se teletransportou até os alvos");
			h.assertTrue(cow.isAlive() && villager.isAlive() && wolf.isAlive() && friend.isAlive() && helper.isAlive(), "matou aliado ou passivo");
			h.assertTrue(c.demonState() == DemonState.ACTIVE, "deveria continuar no modo até completar 20 s: " + c.demonState());
			TestSupport.cleanup(h, owner);
		});
	}

	/**
	 * E, F, G e I: o modo dura exatamente 20 s; ela desce, dorme exatamente 200 s deitada (sem andar, sem levar dano,
	 * sem atender ao apito, mesmo após recarregar), acorda com os atributos normais e volta a seguir a dona.
	 */
	@GameTest(environment = "autonomouscompanions-test:ac_demon_solo", structure = ARENA, maxTicks = 5200)
	public void fullCycleTimingSleepReloadAndRecovery(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity[] c = {rubi(h, owner, new BlockPos(12, FLOOR, 12), "Rubi")};
		Husk z = husk(h, new BlockPos(13, FLOOR, 12));
		hit(h, c[0], z, 500.0F);
		long activated = c[0].demon().activatedAt();
		long[] exhaustedAt = {-1};
		long[] sleepAt = {-1};
		long[] wakeAt = {-1};
		double[] sleepPos = new double[3];
		h.startSequence()
			.thenWaitUntil(() -> h.assertTrue(c[0].demonState() == DemonState.ACTIVE, "não ficou ativa"))
			.thenExecute(() -> {
				// I: recarregar no meio do modo ativo mantém o estado e os 100 corações.
				c[0] = TestSupport.reload(h, c[0]);
				h.assertTrue(c[0].demonState() == DemonState.ACTIVE, "estado perdido ao recarregar: " + c[0].demonState());
				h.assertValueEqual((int) c[0].getMaxHealth(), 200, "vida máxima após recarregar");
				h.assertTrue(c[0].getHealth() > 150, "vida após recarregar: " + c[0].getHealth());
			})
			.thenWaitUntil(() -> h.assertTrue(c[0].demonState() == DemonState.EXHAUSTED, "ainda não acabou: " + c[0].demonState()))
			.thenExecute(() -> {
				exhaustedAt[0] = c[0].demon().stateStart();
				// E: exatamente 400 ticks (20 s) depois da ativação, contados pelo relógio do mundo.
				h.assertValueEqual(exhaustedAt[0] - activated, (long) DemonMode.DURATION_TICKS, "duração do modo em ticks");
				h.assertTrue(!c[0].demon().weatherActive(), "a tempestade não foi encerrada");
			})
			.thenWaitUntil(() -> h.assertTrue(c[0].demonState() == DemonState.SLEEPING, "não deitou"))
			.thenExecute(() -> {
				sleepAt[0] = c[0].demon().stateStart();
				sleepPos[0] = c[0].getX();
				sleepPos[1] = c[0].getY();
				sleepPos[2] = c[0].getZ();
				BlockPos below = c[0].blockPosition().below();
				boolean onFloor = !h.getLevel().getBlockState(below).getCollisionShape(h.getLevel(), below).isEmpty() && c[0].getY() - c[0].blockPosition().getY() < 0.2;
				h.assertTrue(c[0].getPose() == Pose.SLEEPING && onFloor, "não está deitada no chão: y=" + c[0].getY());
				h.assertTrue(c[0].fallDistance == 0 && c[0].getHealth() > 150, "levou dano na descida");
			})
			.thenIdle(100)
			.thenExecute(() -> {
				// F: dormindo, não leva dano, não atende ao apito e não anda; continua após recarregar.
				float before = c[0].getHealth();
				h.assertTrue(!hit(h, c[0], z, 50.0F) && c[0].getHealth() == before, "levou dano dormindo");
				WhistleItem.call(owner, true, null);
				c[0] = TestSupport.reload(h, c[0]);
				h.assertTrue(c[0].demonState() == DemonState.SLEEPING && c[0].getPose() == Pose.SLEEPING, "acordou ao recarregar");
				TestSupport.moveOwner(h, owner, new BlockPos(20, FLOOR, 20));
			})
			.thenIdle(200)
			.thenExecute(() -> {
				double moved = Math.abs(c[0].getX() - sleepPos[0]) + Math.abs(c[0].getZ() - sleepPos[2]);
				h.assertTrue(moved < 0.3 && c[0].demonState() == DemonState.SLEEPING, "saiu do lugar dormindo: " + moved);
			})
			.thenWaitUntil(() -> h.assertTrue(c[0].demonState() == DemonState.RECOVERING, "ainda dormindo"))
			.thenExecute(() -> {
				wakeAt[0] = c[0].demon().stateStart();
				h.assertValueEqual(wakeAt[0] - sleepAt[0], (long) DemonMode.SLEEP_TICKS, "duração do sono em ticks");
			})
			.thenWaitUntil(() -> h.assertTrue(c[0].demonState() == DemonState.NORMAL, "não voltou ao normal"))
			.thenExecute(() -> {
				// G: atributos normais de volta.
				h.assertValueEqual((int) c[0].getMaxHealth(), 20, "vida máxima normal");
				h.assertValueEqual((int) c[0].getHealth(), 20, "vida cheia ao acordar");
				h.assertTrue(!c[0].isNoGravity() && c[0].getPose() != Pose.SLEEPING, "ficou flutuando ou deitada");
				h.assertValueEqual(c[0].demon().trackedTargets(), 0, "lista de alvos não foi limpa");
				// Volta a levar dano normal.
				h.assertTrue(hit(h, c[0], z, 1.0F), "continuou invulnerável");
			})
			.thenWaitUntil(() -> h.assertTrue(c[0].distanceTo(owner) <= c[0].settings().followMax + 0.5, "não voltou a seguir a dona"))
			.thenExecute(() -> TestSupport.cleanup(h, owner))
			.thenSucceed();
	}

	/** H: dezenas de hostis — mata todos uma única vez cada, sem crescer listas e sem deixar o servidor lento. */
	@GameTest(environment = "autonomouscompanions-test:ac_demon_many", structure = ARENA, maxTicks = 600)
	public void manyTargetsStayFastAndBounded(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		CompanionEntity c = rubi(h, owner, new BlockPos(2, FLOOR, 12), "Rubi");
		List<Husk> hostiles = new ArrayList<>();
		for (int x = 6; x <= 21; x += 3) {
			for (int zc = 2; zc <= 21; zc += 2) {
				hostiles.add(husk(h, new BlockPos(x, FLOOR, zc)));
			}
		}
		hit(h, c, hostiles.getFirst(), 500.0F);
		int total = hostiles.size();
		h.succeedWhen(() -> {
			h.assertTrue(hostiles.stream().noneMatch(LivingEntity::isAlive), "ainda há hostis vivos");
			h.assertTrue(c.demon().kills() > 0 && c.demon().kills() == c.demon().executedCount(), "alvo executado mais de uma vez: " + c.demon().kills() + " / " + c.demon().executedCount());
			h.assertTrue(c.demon().trackedTargets() <= total, "lista de alvos cresceu sem controle: " + c.demon().trackedTargets());
			// O tick inteiro do servidor tem 50 ms. A caçada em regime (busca, teletransportes, execuções e efeitos com 60
			// alvos) deve ficar abaixo de 10 ms. Medido em tempo de relógio: numa JVM recém-aberta, a primeira vez que cada
			// efeito roda (primeiros relâmpagos, primeira execução) inclui carga de classes e código ainda não compilado e
			// custou de 6 a 22 ms nas medições; esses ticks isolados só precisam caber num tick de servidor.
			String custo = "maior custo de um tick: " + c.demon().maxTickNanos() / 1_000 + " µs em " + c.demon().maxTickWhere() + "; segundo maior: "
				+ c.demon().secondTickNanos() / 1_000 + " µs; caçada em regime: " + c.demon().steadyTickNanos() / 1_000 + " µs";
			h.assertTrue(c.demon().steadyTickNanos() > 0 && c.demon().steadyTickNanos() < 10_000_000L, "caçada em regime acima de 10 ms — " + custo);
			h.assertTrue(c.demon().maxTickNanos() < 50_000_000L, "um tick do modo passou de 50 ms — " + custo);
			c.debug("[demonio] " + custo);
			TestSupport.cleanup(h, owner);
		});
	}

	/** J: só a de cabelo vermelho tem a habilidade; fora de combate, nem ela. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void onlyTheRedHairedOneHasIt(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 2), GameType.SURVIVAL);
		Husk z = husk(h, new BlockPos(12, FLOOR, 12));
		CompanionEntity violet = TestSupport.companion(h, owner, new BlockPos(10, FLOOR, 10), "Vio");
		violet.setVariant(CompanionVariant.VIOLETA);
		CompanionEntity coral = TestSupport.companion(h, owner, new BlockPos(14, FLOOR, 10), "Cora");
		coral.setVariant(CompanionVariant.CORAL);
		CompanionEntity helper = TestSupport.helper(h, owner, new BlockPos(10, FLOOR, 14));
		for (CompanionEntity c : new CompanionEntity[]{violet, coral, helper}) {
			h.assertTrue(!DemonMode.canHave(c), "outra personagem pode ter a habilidade");
			hit(h, c, z, 500.0F);
			h.assertTrue(c.isDeadOrDying() && c.demonState() == DemonState.NORMAL, "outra personagem recebeu o Modo Demônio");
		}
		// A própria Rubi, fora de combate (queda), não ativa.
		CompanionEntity rubi = rubi(h, owner, new BlockPos(16, FLOOR, 16), "Rubi");
		rubi.hurtServer(h.getLevel(), rubi.damageSources().fall(), 500.0F);
		h.assertTrue(rubi.isDeadOrDying() && rubi.demonState() == DemonState.NORMAL, "ativou fora de combate");
		TestSupport.cleanup(h, owner);
		h.succeed();
	}
}
