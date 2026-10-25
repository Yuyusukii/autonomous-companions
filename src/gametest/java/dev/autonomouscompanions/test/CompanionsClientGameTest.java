package dev.autonomouscompanions.test;

import dev.autonomouscompanions.client.BlockCatalog;
import dev.autonomouscompanions.client.screen.CompanionScreen;
import dev.autonomouscompanions.client.screen.NamingScreen;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.registry.ModEntities;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

/**
 * Teste de cliente real (janela do jogo): mundo novo → três companheiras; clique com o botão direito → tela de nome;
 * confirmar → painel de comandos abre na hora; todas as 8 abas desenham; catálogo de blocos pesquisável; fechar e
 * reabrir o mundo → as mesmas três, com o nome salvo e sem duplicar.
 */
public class CompanionsClientGameTest implements FabricClientGameTest {
	private static int whistles(net.minecraft.server.MinecraftServer s, UUID player) {
		var p = s.getPlayerList().getPlayer(player);
		return p == null ? -1 : p.getInventory().countItem(dev.autonomouscompanions.registry.ModItems.WHISTLE);
	}

	private static final String[] TABS = {"collect", "build", "combat", "food", "roles", "inventory", "settings", "status"};

	private static List<CompanionEntity> companions(MinecraftServer server) {
		return List.copyOf(server.overworld().getEntities(ModEntities.COMPANION, Entity::isAlive));
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		TestWorldSave save;
		UUID player;
		UUID named;
		// Interface em português do Brasil (o idioma principal do mod).
		boolean[] reloaded = {false};
		ctx.runOnClient(mc -> {
			mc.options.languageCode = "pt_br";
			mc.getLanguageManager().setSelected("pt_br");
			mc.reloadResourcePacks().thenRun(() -> reloaded[0] = true);
		});
		ctx.waitFor(mc -> reloaded[0], 1200);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			save = sp.getWorldSave();
			player = ctx.computeOnClient(mc -> mc.player.getUUID());
			// Teste 1: as três aparecem sozinhas perto da jogadora.
			sp.getServer().waitFor(s -> companions(s).size() == 3, 600);
			// A ajudante nasce junto com as três.
			sp.getServer().waitFor(s -> s.overworld().getEntities(ModEntities.HELPER, Entity::isAlive).size() == 1, 200);
			ctx.waitTicks(20);
			List<UUID> owners = sp.getServer().computeOnServer(s -> companions(s).stream().map(CompanionEntity::ownerUUID).toList());
			if (owners.stream().anyMatch(o -> !player.equals(o))) {
				throw new AssertionError("companheira sem a jogadora como dona: " + owners);
			}
			// O apito é entregue junto com as companheiras iniciais.
			sp.getServer().waitFor(s -> whistles(s, player) == 1, 200);
			ctx.takeScreenshot("ac_01_mundo_novo");
			// A ajudante (skin de braços largos).
			BlockPos helperPos = sp.getServer().computeOnServer(s -> s.overworld().getEntities(ModEntities.HELPER, Entity::isAlive).getFirst().blockPosition());
			sp.getServer().runOnServer(s -> s.overworld().getEntities(ModEntities.HELPER, Entity::isAlive).forEach(h -> h.getNavigation().stop()));
			ctx.getInput().lookAt(helperPos);
			ctx.waitTicks(10);
			ctx.takeScreenshot("ac_01b_ajudante");

			// Teste 7: catálogo com todos os blocos registrados, busca por nome traduzido, nome simples e id.
			ctx.runOnClient(mc -> {
				int all = BlockCatalog.all().size();
				if (all < BuiltInRegistries.BLOCK.size() - 64) {
					throw new AssertionError("catálogo incompleto: " + all + " de " + BuiltInRegistries.BLOCK.size());
				}
				if (BlockCatalog.search("minecraft:diamond_ore").stream().noneMatch(e -> e.id().equals("minecraft:diamond_ore"))) {
					throw new AssertionError("busca por id falhou");
				}
				if (BlockCatalog.search("iron").stream().noneMatch(e -> e.id().equals("minecraft:iron_ore"))) {
					throw new AssertionError("busca por nome falhou");
				}
			});

			// Teste 3 e 4: olhar para uma delas, clicar com o botão direito, dar o nome e ver o painel abrir.
			BlockPos target = sp.getServer().computeOnServer(s -> companions(s).getFirst().blockPosition());
			named = sp.getServer().computeOnServer(s -> companions(s).getFirst().getUUID());
			sp.getServer().runOnServer(s -> companions(s).forEach(c -> c.getNavigation().stop()));
			ctx.getInput().lookAt(target);
			ctx.waitTick();
			ctx.getInput().pressKey(options -> options.keyUse);
			ctx.waitForScreen(NamingScreen.class);
			ctx.getInput().typeChars("Luna");
			ctx.takeScreenshot("ac_02_tela_de_nome");
			ctx.clickScreenButton("gui.autonomouscompanions.naming.confirm");
			ctx.waitForScreen(CompanionScreen.class);
			ctx.waitTicks(10);
			ctx.takeScreenshot("ac_03_painel_estado");
			for (String tab : TABS) {
				ctx.clickScreenButton("gui.autonomouscompanions.tab." + tab);
				ctx.waitTicks(5);
				ctx.takeScreenshot("ac_04_aba_" + tab);
			}
			// Modo "Dar meus itens" na aba Itens: mostra o inventário da jogadora (com o apito e o pedregulho).
			sp.getServer().runOnServer(s -> s.getPlayerList().getPlayer(player).getInventory().setItem(9, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 20)));
			ctx.clickScreenButton("gui.autonomouscompanions.tab.inventory");
			ctx.waitTicks(5);
			ctx.clickScreenButton("gui.autonomouscompanions.inventory.give_mode");
			ctx.waitTicks(5);
			ctx.takeScreenshot("ac_06_dar_itens");
			ctx.clickScreenButton("gui.autonomouscompanions.inventory.view_hers");
			ctx.waitTicks(5);
			String name = sp.getServer().computeOnServer(s -> ((CompanionEntity) s.overworld().getEntity(named)).companionName());
			if (!"Luna".equals(name)) {
				throw new AssertionError("nome não salvo no servidor: " + name);
			}
			ctx.setScreen(() -> null);
			ctx.waitTicks(5);
			// Apito: botão direito com ele na mão (olhando para o céu) abre o menu de escolha.
			int whistleSlot = sp.getServer().computeOnServer(s -> {
				var inv = s.getPlayerList().getPlayer(player).getInventory();
				for (int i = 0; i < 9; i++) {
					if (inv.getItem(i).is(dev.autonomouscompanions.registry.ModItems.WHISTLE)) {
						return i;
					}
				}
				return -1;
			});
			if (whistleSlot < 0) {
				throw new AssertionError("o apito não está na barra rápida");
			}
			ctx.runOnClient(mc -> {
				mc.player.getInventory().setSelectedSlot(whistleSlot);
				mc.player.setXRot(-80.0F);
			});
			ctx.waitTicks(5);
			ctx.getInput().pressKey(options -> options.keyUse);
			// Efeito observável do chamado: elas saem do modo "ficar aqui".
			sp.getServer().runOnServer(s -> companions(s).forEach(c -> c.settings().stay = true));
			ctx.waitForScreen(dev.autonomouscompanions.client.screen.WhistleScreen.class);
			ctx.waitTicks(5);
			ctx.takeScreenshot("ac_07_menu_do_apito");
			ctx.clickScreenButton("gui.autonomouscompanions.whistle.all");
			ctx.waitTicks(5);
			boolean recalled = sp.getServer().computeOnServer(s -> companions(s).stream().noneMatch(c -> c.settings().stay));
			if (!recalled) {
				throw new AssertionError("o botão \"Chamar as três\" não chamou todas");
			}
		}

		// Testes 2 e 26: fechar e reabrir o mundo.
		try (TestSingleplayerContext sp = save.open()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().waitFor(s -> companions(s).size() >= 3, 400);
			ctx.waitTicks(120);
			List<CompanionEntity> list = sp.getServer().computeOnServer(s -> List.copyOf(companions(s)));
			if (list.size() != 3) {
				throw new AssertionError("companheiras após reabrir: " + list.size());
			}
			String name = sp.getServer().computeOnServer(s -> {
				Entity e = s.overworld().getEntity(named);
				return e instanceof CompanionEntity c ? c.companionName() : null;
			});
			if (!"Luna".equals(name)) {
				throw new AssertionError("nome perdido ao reabrir o mundo: " + name);
			}
			boolean owned = sp.getServer().computeOnServer(s -> companions(s).stream().allMatch(c -> player.equals(c.ownerUUID())));
			if (!owned) {
				throw new AssertionError("dona perdida ao reabrir");
			}
			ctx.takeScreenshot("ac_05_mundo_reaberto");

			// Modo Demônio (só a de cabelo vermelho): golpe fatal de um husk em combate → transformação, caçada e sono.
			UUID rubi = sp.getServer().computeOnServer(s -> companions(s).stream()
				.filter(c -> c.variant() == dev.autonomouscompanions.entity.CompanionVariant.RUBI).findFirst().orElseThrow().getUUID());
			BlockPos rubiPos = sp.getServer().computeOnServer(s -> {
				CompanionEntity c = (CompanionEntity) s.overworld().getEntity(rubi);
				// Golpe fatal só existe com as regras antigas (com vida infinita nada a fere): este trecho verifica esse gatilho.
				c.settings().classicSurvival = true;
				var husk = net.minecraft.world.entity.EntityTypes.HUSK.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.EVENT);
				husk.snapTo(c.getX() + 6, c.getY(), c.getZ(), 0, 0);
				husk.setNoAi(true);
				s.overworld().addFreshEntity(husk);
				c.hurtServer(s.overworld(), c.damageSources().mobAttack(husk), 500.0F);
				return c.blockPosition();
			});
			ctx.getInput().lookAt(rubiPos.above());
			ctx.waitTicks(32);
			ctx.takeScreenshot("ac_08_demonio_transformando");
			ctx.waitTicks(16);
			ctx.getInput().lookAt(sp.getServer().computeOnServer(s -> s.overworld().getEntity(rubi).blockPosition().above()));
			ctx.takeScreenshot("ac_09_demonio_despertou");
			ctx.waitTicks(40);
			ctx.getInput().lookAt(sp.getServer().computeOnServer(s -> s.overworld().getEntity(rubi).blockPosition().above()));
			ctx.takeScreenshot("ac_10_demonio_ativo");
			sp.getServer().waitFor(s -> ((CompanionEntity) s.overworld().getEntity(rubi)).demonState() == dev.autonomouscompanions.demon.DemonState.SLEEPING, 600);
			ctx.waitTicks(20);
			sp.getServer().runOnServer(s -> {
				var e = s.overworld().getEntity(rubi);
				s.getPlayerList().getPlayer(player).teleportTo(e.getX() + 1.6, e.getY(), e.getZ() + 0.4);
			});
			ctx.waitTicks(10);
			ctx.getInput().lookAt(sp.getServer().computeOnServer(s -> s.overworld().getEntity(rubi).blockPosition()));
			ctx.takeScreenshot("ac_11_demonio_dormindo");
			boolean ok = sp.getServer().computeOnServer(s -> {
				CompanionEntity c = (CompanionEntity) s.overworld().getEntity(rubi);
				return c.isAlive() && c.getMaxHealth() == 200.0F && c.getPose() == net.minecraft.world.entity.Pose.SLEEPING;
			});
			if (!ok) {
				throw new AssertionError("Modo Demônio: estado inesperado no cliente real");
			}
			// Yuno: observando de longe (no campo de visão, mas fora do centro)... e some quando a jogadora olha para ela.
			ctx.runOnClient(mc -> mc.player.setXRot(0.0F));
			ctx.waitTicks(5);
			net.minecraft.world.phys.Vec3 yunoPos = sp.getServer().computeOnServer(s -> {
				net.minecraft.server.level.ServerPlayer p = s.getPlayerList().getPlayer(player);
				var entry = dev.autonomouscompanions.yuno.YunoData.get(s).entry(player);
				var y = entry == null ? null : dev.autonomouscompanions.yuno.YunoManager.find(s, entry.yunoId());
				if (y == null) {
					dev.autonomouscompanions.yuno.YunoManager.introduce(p);
					y = dev.autonomouscompanions.yuno.YunoManager.find(s, dev.autonomouscompanions.yuno.YunoData.get(s).entry(player).yunoId());
				}
				double a = Math.toRadians(p.getYRot() + 90 + 22);
				double x = p.getX() + Math.cos(a) * 22;
				double z = p.getZ() + Math.sin(a) * 22;
				int top = s.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
				net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(Math.floor(x) + 0.5, top, Math.floor(z) + 0.5);
				y.appearAt(at, s.overworld().getGameTime() + 100_000);
				return at;
			});
			ctx.waitTicks(8);
			ctx.takeScreenshot("ac_12_yuno_observando");
			ctx.getInput().lookAt(BlockPos.containing(yunoPos.add(0, 1.2, 0)));
			ctx.waitTicks(3);
			ctx.takeScreenshot("ac_13_yuno_vislumbre");
			ctx.waitTicks(15);
			ctx.takeScreenshot("ac_14_yuno_sumiu");
			boolean gone = sp.getServer().computeOnServer(s -> {
				var y = dev.autonomouscompanions.yuno.YunoManager.find(s, dev.autonomouscompanions.yuno.YunoData.get(s).entry(player).yunoId());
				return y != null && y.mode() == dev.autonomouscompanions.yuno.YunoEntity.Mode.VANISHED && y.isInvisible();
			});
			if (!gone) {
				throw new AssertionError("Yuno não sumiu ao ser vista na janela real");
			}

			// Limpar terreno: "Limpar terreno" (área 12×12, equipe de 2) e botão direito no chão → confirmação real.
			int[] worker = sp.getServer().computeOnServer(s -> {
				CompanionEntity w = companions(s).stream().filter(c -> !c.demonState().engaged()).findFirst().orElseThrow();
				net.minecraft.server.level.ServerPlayer p = s.getPlayerList().getPlayer(player);
				dev.autonomouscompanions.network.ServerNetworking.handleBuildMark(p, new dev.autonomouscompanions.network.Payloads.BuildMark(w.getId(), 0, 0, 0L,
					true, dev.autonomouscompanions.network.Payloads.BuildMark.TERRAIN, 12, 2));
				BlockPos ground = p.blockPosition().relative(p.getDirection(), 4).below();
				return new int[]{w.getId(), ground.getX(), ground.getY(), ground.getZ()};
			});
			ctx.waitTicks(5);
			ctx.getInput().lookAt(new BlockPos(worker[1], worker[2], worker[3]));
			ctx.waitTicks(3);
			ctx.getInput().pressKey(options -> options.keyUse);
			ctx.waitForScreen(dev.autonomouscompanions.client.screen.BuildPreviewScreen.class);
			ctx.waitTicks(5);
			ctx.takeScreenshot("ac_15_limpar_terreno");
			ctx.clickScreenButton("gui.autonomouscompanions.preview.clear_confirm");
			ctx.waitTicks(10);
			boolean started = sp.getServer().computeOnServer(s -> {
				// No superplano não há nada a limpar e a obra pode já ter sido concluída, então
				// procura o projeto em si (em qualquer estado), não a ordem.
				// Só companheiras com nome entram na equipe; neste mundo de teste só uma tem nome.
				var e = s.overworld().getEntity(worker[0]);
				if (e == null) {
					return false;
				}
				java.util.UUID id = e.getUUID();
				return dev.autonomouscompanions.persist.CompanionWorldData.get(s).projects().stream()
					.anyMatch(p -> p.isTerrain() && p.area() == 12 && p.workers().contains(id));
			});
			if (!started) {
				throw new AssertionError("a limpeza de terreno não foi criada pela confirmação na janela real");
			}
			ctx.waitTicks(60);
			ctx.takeScreenshot("ac_16_limpando");

			// Jogo individual: companheira de uma "dona antiga" (outra identidade, fora do mundo) passa para quem está jogando.
			int adopted = sp.getServer().computeOnServer(s -> {
				CompanionEntity w = companions(s).getFirst();
				w.setOwnerUUID(UUID.randomUUID());
				return w.getId();
			});
			ctx.waitTicks(60);
			boolean mine = sp.getServer().computeOnServer(s -> s.overworld().getEntity(adopted) instanceof CompanionEntity c && c.isOwnedBy(
				s.getPlayerList().getPlayer(player)));
			if (!mine) {
				throw new AssertionError("a companheira da dona antiga não passou para a jogadora no jogo individual");
			}
		}
	}
}
