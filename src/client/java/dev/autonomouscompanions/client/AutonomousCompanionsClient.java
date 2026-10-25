package dev.autonomouscompanions.client;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.client.render.CompanionRenderer;
import dev.autonomouscompanions.client.screen.BuildPreviewScreen;
import dev.autonomouscompanions.client.screen.CompanionScreen;
import dev.autonomouscompanions.client.screen.NamingScreen;
import dev.autonomouscompanions.client.screen.Ui;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

/** Ponto de entrada do cliente: renderizador, telas, recepção de pacotes e aviso do modo de marcação. */
public final class AutonomousCompanionsClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRenderers.register(ModEntities.COMPANION, CompanionRenderer::new);
		EntityRenderers.register(ModEntities.HELPER, ctx -> new CompanionRenderer(ctx, false));
		EntityRenderers.register(ModEntities.YUNO, dev.autonomouscompanions.client.render.YunoRenderer::new);
		dev.autonomouscompanions.client.render.DemonClientEffects.register();

		ClientPlayNetworking.registerGlobalReceiver(Payloads.OpenNaming.TYPE, (p, ctx) ->
			ctx.client().gui.setScreen(new NamingScreen(p.entityId(), p.variant())));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Snapshot.TYPE, (p, ctx) -> {
			ClientState.SNAPSHOTS.put(p.snapshot().entityId(), p.snapshot());
			Minecraft mc = ctx.client();
			if (mc.gui.screen() instanceof CompanionScreen screen && screen.entityId() == p.snapshot().entityId()) {
				screen.onSnapshot(p.snapshot());
			} else if (p.open()) {
				mc.gui.setScreen(new CompanionScreen(p.snapshot().entityId()));
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.WhistleMenu.TYPE, (p, ctx) ->
			ctx.client().gui.setScreen(new dev.autonomouscompanions.client.screen.WhistleScreen(p.entries())));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Analysis.TYPE, (p, ctx) -> ClientState.lastAnalysis = p);
		ClientPlayNetworking.registerGlobalReceiver(Payloads.BuildPreview.TYPE, (p, ctx) -> ctx.client().gui.setScreen(new BuildPreviewScreen(p)));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.MarkingMode.TYPE, (p, ctx) -> {
			ClientState.marking = p.active();
			ClientState.markingEntity = p.entityId();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.clear());

		// No modo de marcação, o clique com o botão direito em um bloco escolhe o local (o servidor valida).
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && ClientState.marking) {
				ClientState.marking = false;
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		HudElementRegistry.addLast(AutonomousCompanions.id("marking_hint"), (graphics, delta) -> {
			if (!ClientState.marking) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			Component hint = Component.translatable("build.autonomouscompanions.marking_hint");
			int w = mc.font.width(hint);
			int x = (graphics.guiWidth() - w) / 2;
			int y = graphics.guiHeight() / 2 + 20;
			graphics.fill(x - 4, y - 3, x + w + 4, y + 11, 0xAA000000);
			graphics.text(mc.font, hint, x, y, Ui.ACCENT, false);
		});
	}
}
