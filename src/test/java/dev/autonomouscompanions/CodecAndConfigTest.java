package dev.autonomouscompanions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.autonomouscompanions.build.BuildProject;
import dev.autonomouscompanions.build.HouseSize;
import dev.autonomouscompanions.build.HouseStyle;
import dev.autonomouscompanions.config.CompanionConfig;
import dev.autonomouscompanions.entity.CompanionSettings;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.survival.CompanionNeeds;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderBook;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Persistência (codecs) das ordens, projetos, necessidades e ajustes; validação da configuração; nomes. */
class CodecAndConfigTest {
	@BeforeAll
	static void boot() {
		McBootstrap.init();
	}

	private static <T> T roundTrip(Codec<T> codec, T value) {
		var json = codec.encodeStart(JsonOps.INSTANCE, value).getOrThrow();
		return codec.parse(JsonOps.INSTANCE, json).getOrThrow();
	}

	@Test
	void ordersSurviveSaveAndLoadWithProgressAndStatus() {
		Order gather = Order.gather(Items.COBBLESTONE, "minecraft:stone", GatherMode.DROPS, 37, SearchProfile.MODERATE, 300, 1234L);
		gather.setUrgent(true);
		gather.progress().obtained = 20;
		gather.progress().delivered = 12;
		gather.progress().blocksMined = 21;
		gather.progress().distance = 88.5;
		gather.setStatus(OrderStatus.RETURNING);
		Order craft = Order.craft(Items.COOKED_BEEF, 8, 99L);
		craft.setStatus(OrderStatus.SUSPENDED);
		Order failed = Order.equip(5L);
		failed.setStatus(OrderStatus.FAILED);
		failed.setReason(Component.translatable("reason.autonomouscompanions.not_found", "x", 128));
		Order build = Order.build(UUID.randomUUID(), 7L);

		List<Order> loaded = roundTrip(OrderBook.LIST_CODEC, List.of(gather, craft, failed, build));
		assertEquals(4, loaded.size());
		Order g = loaded.getFirst();
		assertEquals(gather.id(), g.id());
		assertEquals(37, g.requested());
		assertEquals(25, g.remainingToDeliver());
		assertEquals(20, g.progress().obtained);
		assertEquals(21, g.progress().blocksMined);
		assertEquals(88.5, g.progress().distance, 1e-9);
		assertEquals(OrderStatus.RETURNING, g.status());
		assertEquals(GatherMode.DROPS, g.mode());
		assertEquals(SearchProfile.MODERATE, g.profile());
		assertEquals(300, g.maxDistance());
		assertTrue(g.urgent());
		assertEquals(Items.COBBLESTONE, g.targetItem());
		assertEquals(OrderStatus.SUSPENDED, loaded.get(1).status());
		assertEquals(Items.COOKED_BEEF, loaded.get(1).targetItem());
		assertEquals(OrderStatus.FAILED, loaded.get(2).status());
		assertEquals(failed.reason().getString(), loaded.get(2).reason().getString());
		assertEquals(build.projectId(), loaded.get(3).projectId());
	}

	@Test
	void orderBookReservesOnlyUndeliveredItemsOfActiveOrders() {
		OrderBook book = new OrderBook();
		Order a = Order.gather(Items.COBBLESTONE, "minecraft:stone", GatherMode.DROPS, 64, SearchProfile.AUTO, 0, 0);
		a.progress().obtained = 40;
		a.progress().delivered = 10;
		book.add(a);
		Order done = Order.gather(Items.COBBLESTONE, "minecraft:stone", GatherMode.DROPS, 30, SearchProfile.AUTO, 0, 0);
		done.progress().obtained = 30;
		done.progress().delivered = 30;
		done.setStatus(OrderStatus.COMPLETED);
		book.add(done);
		int reserved = book.reserved(Items.COBBLESTONE);
		assertTrue(reserved > 0 && reserved <= 54, "reserva inesperada: " + reserved);
		assertEquals(0, book.reserved(Items.DIRT));
	}

	@Test
	void buildProjectSurvivesSaveAndLoad() {
		BuildProject p = new BuildProject(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), HouseStyle.ORIENTAL, 4242L, HouseSize.LARGE,
			new BlockPos(10, 70, -3), Direction.EAST, Level.OVERWORLD, BuildProject.Status.BUILDING, true, "cherry", 120, 480, 1);
		BuildProject q = roundTrip(BuildProject.CODEC, p);
		assertEquals(p.id(), q.id());
		assertEquals(p.owner(), q.owner());
		assertEquals(HouseStyle.ORIENTAL, q.style());
		assertEquals(HouseSize.LARGE, q.size());
		assertEquals(4242L, q.seed());
		assertEquals(new BlockPos(10, 70, -3), q.origin());
		assertEquals(Direction.EAST, q.facing());
		assertEquals(BuildProject.Status.BUILDING, q.status());
		assertEquals("cherry", q.wood());
		assertEquals(120, q.placed);
		assertEquals(480, q.total);
		assertEquals(25, q.progressPercent());
	}

	@Test
	void needsAndSettingsSurviveSaveAndLoad() {
		CompanionNeeds n = new CompanionNeeds();
		n.setHunger(7);
		n.setFatigue(81.5f);
		CompanionNeeds m = roundTrip(CompanionNeeds.CODEC, n);
		assertEquals(7, m.hunger());
		assertEquals(81.5f, m.fatigue(), 1e-4);

		CompanionSettings s = new CompanionSettings();
		s.followMin = 4;
		s.followMax = 11;
		s.idleRadius = 40;
		s.stay = true;
		s.stayPos = new BlockPos(1, 2, 3);
		s.protectOwner = false;
		s.maxSearchDistance = 1024;
		CompanionSettings t = roundTrip(CompanionSettings.CODEC, s);
		assertEquals(4, t.followMin);
		assertEquals(11, t.followMax);
		assertEquals(40, t.idleRadius);
		assertTrue(t.stay);
		assertEquals(new BlockPos(1, 2, 3), t.stayPos);
		assertEquals(false, t.protectOwner);
		assertEquals(1024, t.maxSearchDistance);
	}

	@Test
	void configValidationClampsAndKeepsValidModes() {
		CompanionConfig c = new CompanionConfig();
		c.raioCarregamentoExpedicaoChunks = 99;
		c.secoesAnalisadasPorTick = 0;
		c.modoCompanheirasIniciais = "INVALIDO";
		c.comportamentoMorte = "???";
		c.validate();
		assertEquals(2, c.raioCarregamentoExpedicaoChunks);
		assertEquals(1, c.secoesAnalisadasPorTick);
		assertEquals("PRIMEIRO_JOGADOR", c.modoCompanheirasIniciais);
		assertEquals("RESSURGIR_PERTO_DO_DONO", c.comportamentoMorte);

		CompanionConfig d = new CompanionConfig();
		d.modoCompanheirasIniciais = "DESATIVADO";
		d.validate();
		assertEquals("DESATIVADO", d.modoCompanheirasIniciais);

		// Os valores padrão pedidos na especificação.
		CompanionConfig def = new CompanionConfig();
		def.validate();
		assertEquals(100, def.limiteExploracaoOciosa);
		assertEquals(128, def.alcanceBuscaComum);
		assertEquals(512, def.alcanceBuscaModerado);
		assertEquals(2048, def.alcanceBuscaRaro);
		assertEquals(4096, def.alcanceBuscaExpedicao);
		assertEquals(20.0, def.vidaMaxima, 1e-9);

		// Gson (formato do arquivo) preserva os campos.
		Gson gson = new Gson();
		CompanionConfig back = gson.fromJson(gson.toJson(def), CompanionConfig.class);
		assertEquals(def.limiteExploracaoOciosa, back.limiteExploracaoOciosa);
		assertEquals(def.segundosParaRessurgir, back.segundosParaRessurgir);
	}

	@Test
	void namesAreSanitized() {
		assertEquals("Luna", ServerNetworking.sanitizeName("  Lu@na!! "));
		assertEquals("Ana Maria", ServerNetworking.sanitizeName("Ana    Maria"));
		assertEquals("Joana d'Arc", ServerNetworking.sanitizeName("Joana d'Arc"));
		assertEquals("", ServerNetworking.sanitizeName("§§§"));
		assertEquals(24, ServerNetworking.sanitizeName("abcdefghijklmnopqrstuvwxyz0123").length());
	}
}
