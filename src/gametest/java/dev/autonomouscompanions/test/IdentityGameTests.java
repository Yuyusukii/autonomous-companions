package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import com.mojang.serialization.Dynamic;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionVariant;
import dev.autonomouscompanions.nav.SafeSpot;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.persist.CompanionWorldData;
import dev.autonomouscompanions.registry.ModEntities;
import dev.autonomouscompanions.spawn.StarterSpawner;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/** Testes 1, 2, 3, 5 e 6 (criação, duplicação, nome, acompanhamento e limite de exploração) e controle só pela dona. */
public class IdentityGameTests {
	private static List<CompanionEntity> ownedBy(GameTestHelper h, ServerPlayer p) {
		return h.getEntities(ModEntities.COMPANION).stream().filter(c -> c.isOwnedBy(p)).toList();
	}

	/** Teste 1 e 2: três companheiras, uma vez só, em locais seguros, com identidades únicas e registro persistente. */
	@GameTest(environment = "autonomouscompanions-test:ac_spawn", structure = "autonomouscompanions-test:arena", maxTicks = 600)
	public void starterCompanionsSpawnOnceAndNeverDuplicate(GameTestHelper h) {
		ServerPlayer p = TestSupport.owner(h, new BlockPos(12, FLOOR, 12), GameType.SURVIVAL);
		h.assertTrue(StarterSpawner.queue(p), "a criação inicial deveria ser agendada");
		h.startSequence()
			.thenWaitUntil(() -> h.assertValueEqual(ownedBy(h, p).size(), 3, "companheiras criadas"))
			.thenExecute(() -> {
				List<CompanionEntity> list = ownedBy(h, p);
				Set<CompanionVariant> variants = new HashSet<>();
				Set<UUID> ids = new HashSet<>();
				for (CompanionEntity c : list) {
					variants.add(c.variant());
					ids.add(c.getUUID());
					h.assertTrue(c.distanceTo(p) <= 6.0, "nasceu longe demais da jogadora: " + c.distanceTo(p));
					h.assertTrue(SafeSpot.isSafeStand(h.getLevel(), c.blockPosition(), ModEntities.COMPANION), "local de nascimento inseguro");
					h.assertTrue(!c.isNamed(), "deveria começar sem nome");
				}
				h.assertValueEqual(variants.size(), 3, "aparências distintas");
				h.assertValueEqual(ids.size(), 3, "UUIDs distintos");
				// A ajudante nasce junto, já com nome, e é registrada uma única vez.
				List<CompanionEntity> helpers = h.getEntities(ModEntities.HELPER).stream().filter(c -> c.isOwnedBy(p)).toList();
				h.assertValueEqual(helpers.size(), 1, "ajudantes criadas");
				h.assertTrue(helpers.getFirst().isHelper() && helpers.getFirst().isNamed() && helpers.getFirst().variant() == CompanionVariant.AJUDANTE, "ajudante mal configurada");
				h.assertTrue(CompanionWorldData.get(h.getLevel().getServer()).hasHelper(p.getUUID()), "registro da ajudante ausente");
				CompanionWorldData data = CompanionWorldData.get(h.getLevel().getServer());
				h.assertTrue(data.hasReceivedStarters(p.getUUID()), "registro persistente de entrega ausente");
				for (UUID id : ids) {
					h.assertTrue(data.record(id) != null, "companheira sem registro persistente");
				}
				// O registro sobrevive a salvar/carregar (como ao reabrir o mundo).
				Tag saved = CompanionWorldData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
				CompanionWorldData loaded = CompanionWorldData.CODEC.parse(new Dynamic<>(NbtOps.INSTANCE, saved)).getOrThrow();
				h.assertTrue(loaded.hasReceivedStarters(p.getUUID()), "registro de entrega perdido ao recarregar");
				// Reentrar no mundo (mesmo com o modo "cada jogador") não cria outras.
				ConfigManager.get().modoCompanheirasIniciais = "CADA_JOGADOR";
				StarterSpawner.onPlayerJoin(p);
				h.assertTrue(!StarterSpawner.queue(p), "não pode agendar de novo para quem já recebeu");
			})
			.thenIdle(100)
			.thenExecute(() -> {
				ConfigManager.get().modoCompanheirasIniciais = "DESATIVADO";
				h.assertValueEqual(ownedBy(h, p).size(), 3, "companheiras após reentrar");
				h.assertValueEqual((int) h.getEntities(ModEntities.HELPER).stream().filter(c -> c.isOwnedBy(p)).count(), 1, "ajudantes após reentrar");
				h.assertTrue(!StarterSpawner.isPending(p.getUUID()), "ficou uma criação pendente");
				TestSupport.cleanup(h, p);
			})
			.thenSucceed();
	}

	/** Teste 3: o nome é validado, salvo, exibido e persistido; só a dona pode nomear e controlar. */
	@GameTest(environment = "autonomouscompanions-test:ac_day", structure = "autonomouscompanions-test:arena", maxTicks = 200)
	public void namingIsSanitizedSavedAndOwnerOnly(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(10, FLOOR, 10), GameType.SURVIVAL);
		ServerPlayer stranger = TestSupport.owner(h, new BlockPos(14, FLOOR, 10), GameType.SURVIVAL);
		CompanionEntity c = h.spawn(ModEntities.COMPANION, new BlockPos(12, FLOOR, 12), net.minecraft.world.entity.EntitySpawnReason.EVENT);
		c.setOwner(owner);
		h.assertTrue(!c.isNamed(), "deveria começar sem nome");
		// Estranha tenta nomear: recusado.
		ServerNetworking.handleRename(stranger, new Payloads.Rename(c.getId(), "Intrusa"));
		h.assertTrue(!c.isNamed(), "uma jogadora que não é a dona conseguiu nomear");
		// Dona nomeia com caracteres inválidos: sanitizado.
		ServerNetworking.handleRename(owner, new Payloads.Rename(c.getId(), "  Lu@na!! "));
		h.assertValueEqual(c.companionName(), "Luna", "nome salvo");
		h.assertTrue(c.isNamed(), "deveria estar nomeada");
		h.assertTrue(c.hasCustomName() && c.getCustomName().getString().equals("Luna") && c.isCustomNameVisible(), "placa de nome não sincronizada");
		CompanionWorldData data = CompanionWorldData.get(h.getLevel().getServer());
		h.assertValueEqual(data.record(c.getUUID()).name(), "Luna", "nome no registro persistente");
		// Estranha não muda ajustes nem dá ordens.
		ServerNetworking.handleRole(stranger, new Payloads.SetRole(c.getId(), dev.autonomouscompanions.entity.CompanionRole.GUARD.ordinal()));
		h.assertTrue(c.role() == dev.autonomouscompanions.entity.CompanionRole.NONE, "uma estranha mudou a função");
		ServerNetworking.handleStartGather(stranger, new Payloads.StartGather(c.getId(), "minecraft:stone", 1, 3, 0, 0, false));
		h.assertTrue(c.orders().all().isEmpty(), "uma estranha conseguiu dar uma ordem");
		// O nome sobrevive a salvar e recarregar.
		UUID id = c.getUUID();
		CompanionEntity back = TestSupport.reload(h, c);
		h.assertValueEqual(back.getUUID(), id, "UUID após recarregar");
		h.assertValueEqual(back.companionName(), "Luna", "nome após recarregar");
		h.assertTrue(back.isOwnedBy(owner), "dona após recarregar");
		TestSupport.cleanup(h, owner, stranger);
		h.succeed();
	}

	/** Teste 5: acompanha a dona andando de verdade (sem teleporte) até a distância configurada. */
	@GameTest(environment = "autonomouscompanions-test:ac_day", structure = "autonomouscompanions-test:corridor", maxTicks = 900)
	public void followsOwnerByWalking(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(6, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(4, FLOOR, 4), "Mira");
		double[] last = {c.getX(), c.getZ()};
		double[] maxStep = {0};
		h.startSequence()
			.thenIdle(20)
			.thenExecute(() -> TestSupport.moveOwner(h, owner, new BlockPos(40, FLOOR, 4)))
			.thenWaitUntil(() -> {
				double step = Math.hypot(c.getX() - last[0], c.getZ() - last[1]);
				maxStep[0] = Math.max(maxStep[0], step);
				last[0] = c.getX();
				last[1] = c.getZ();
				h.assertTrue(TestSupport.horizontal(c, owner) <= c.settings().followMax + 1.5, "ainda longe da dona: " + TestSupport.horizontal(c, owner));
			})
			.thenExecute(() -> {
				h.assertTrue(maxStep[0] < 1.5, "deslocamento de " + maxStep[0] + " blocos em um tick indica teleporte");
				h.assertTrue(c.stats().distanceTraveled > 25, "não percorreu o caminho andando: " + c.stats().distanceTraveled);
			})
			.thenExecuteFor(60, () -> h.assertTrue(TestSupport.horizontal(c, owner) >= Math.max(1.0, c.settings().followMin - 1.5),
				"encostou na dona (bloqueando a passagem): " + TestSupport.horizontal(c, owner)))
			.thenExecute(() -> TestSupport.cleanup(h, owner))
			.thenSucceed();
	}

	/** Teste 6: a exploração ociosa respeita o raio máximo (aqui 10 blocos; o padrão global é 100). */
	@GameTest(environment = "autonomouscompanions-test:ac_day", structure = "autonomouscompanions-test:corridor", maxTicks = 2400)
	public void idleExplorationStaysWithinLimit(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(36, FLOOR, 4), GameType.SURVIVAL);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(34, FLOOR, 4), "Aria");
		c.settings().idleExplore = true;
		c.settings().idleRadius = 10;
		boolean[] explored = {false};
		double[] farthest = {0};
		h.startSequence()
			.thenExecuteFor(2200, () -> {
				double d = TestSupport.horizontal(c, owner);
				farthest[0] = Math.max(farthest[0], d);
				if (c.companionState() == CompanionState.EXPLORING) {
					explored[0] = true;
				}
				h.assertTrue(d <= 10 + 3.0, "passou do limite de exploração ociosa: " + d);
			})
			.thenExecute(() -> {
				h.assertTrue(explored[0], "não explorou nenhuma vez");
				h.assertTrue(farthest[0] > 3.0, "a exploração não se afastou da dona (" + farthest[0] + ")");
				int globalLimit = ConfigManager.get().limiteExploracaoOciosa;
				h.assertValueEqual(globalLimit, 100, "limite global padrão de exploração ociosa");
				TestSupport.cleanup(h, owner);
			})
			.thenSucceed();
	}
}
