package dev.autonomouscompanions.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.autonomouscompanions.AutonomousCompanions;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Olhos vermelhos brilhantes do Modo Demônio. A textura tem só os 8 pixels dos olhos da skin original (o resto é
 * transparente) e é desenhada com o tipo de renderização "olhos" do jogo (brilha no escuro, como os da aranha), por cima
 * do modelo atual: rosto, cabelo e roupas continuam os mesmos.
 */
public class DemonEyesLayer extends RenderLayer<CompanionRenderState, CompanionModel> {
	private static final Identifier TEXTURE = AutonomousCompanions.id("textures/entity/companion/rubi_demon_eyes.png");

	public DemonEyesLayer(RenderLayerParent<CompanionRenderState, CompanionModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CompanionRenderState state, float yRot, float xRot) {
		if (!state.demonEyes || state.isInvisible) {
			return;
		}
		collector.order(1).submitModel(this.getParentModel(), state, poseStack, RenderTypes.eyes(TEXTURE), lightCoords, OverlayTexture.NO_OVERLAY,
			state.outlineColor);
	}
}
