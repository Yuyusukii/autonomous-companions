package dev.autonomouscompanions;

import dev.autonomouscompanions.command.CompanionCommands;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.coop.Cooperation;
import dev.autonomouscompanions.crafting.RecipeIndex;
import dev.autonomouscompanions.mining.DropIndex;
import dev.autonomouscompanions.nav.ExpeditionChunkLoader;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.registry.ModEntities;
import dev.autonomouscompanions.spawn.StarterSpawner;
import dev.autonomouscompanions.survival.DeathRecovery;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ponto de entrada comum (servidor e cliente): registros, eventos, rede e comandos. */
public final class AutonomousCompanions implements ModInitializer {
	public static final String MOD_ID = "autonomouscompanions";
	public static final Logger LOGGER = LoggerFactory.getLogger("AutonomousCompanions");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ConfigManager.load();
		ModEntities.register();
		dev.autonomouscompanions.registry.ModItems.register();
		ExpeditionChunkLoader.register();
		ServerNetworking.registerPayloadTypes();
		ServerNetworking.registerReceivers();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> StarterSpawner.onPlayerJoin(handler.getPlayer()));
		// Conversa: "Makima, traga 10 ferro" no chat (entendido localmente, na thread do servidor).
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			String text = message.signedContent();
			((net.minecraft.server.level.ServerLevel) sender.level()).getServer().execute(() -> dev.autonomouscompanions.mind.talk.Talk.onChat(sender, text));
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Cooperation.reset();
			dev.autonomouscompanions.ai.HelperGuard.reset();
			RecipeIndex.rebuild(server);
			DropIndex.reset();
			dev.autonomouscompanions.mind.knowledge.GameCatalog.reset();
			dev.autonomouscompanions.mind.skills.SkillLibrary.load(server);
			dev.autonomouscompanions.mind.knowledge.KnowledgeBase.load(server);
		});
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success) {
				RecipeIndex.rebuild(server);
				DropIndex.reset();
				dev.autonomouscompanions.mind.skills.SkillLibrary.load(server);
			dev.autonomouscompanions.mind.knowledge.KnowledgeBase.load(server);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Cooperation.reset();
			dev.autonomouscompanions.world.SurfaceSurvey.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			StarterSpawner.tick(server);
			dev.autonomouscompanions.yuno.YunoManager.tick(server);
			dev.autonomouscompanions.ai.HelperGuard.tick(server);
			dev.autonomouscompanions.spawn.AnimalBoost.tick(server);
			ServerNetworking.tick(server);
			// Índice de obtenção construído aos poucos (no máximo ~2 ms por tick).
			DropIndex.tick(server, 2_000_000L);
			// Catálogo da versão carregada (blocos, itens, receitas, criaturas...), gravado em segundo plano quando muda.
			if (dev.autonomouscompanions.config.ConfigManager.get().exportarCatalogo) {
				dev.autonomouscompanions.mind.knowledge.GameCatalog.tick(server);
			}
			if (server.getTickCount() % 20 == 0) {
				DeathRecovery.tick(server);
			}
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer sp && level.getBlockState(hit.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
				// Baú usado pela jogadora: lembrado para os presentes da Yuno (e revela um presente ainda não descoberto).
				dev.autonomouscompanions.yuno.YunoManager.onChestOpened(sp, hit.getBlockPos());
			}
			if (!level.isClientSide() && player instanceof ServerPlayer sp && ServerNetworking.isMarking(sp)) {
				ServerNetworking.onUseBlock(sp, hit.getBlockPos());
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> CompanionCommands.register(dispatcher));
		LOGGER.info("Autonomous Companions inicializado.");
	}
}
