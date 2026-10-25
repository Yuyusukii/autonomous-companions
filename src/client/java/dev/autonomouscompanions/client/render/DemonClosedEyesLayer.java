package dev.autonomouscompanions.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.entity.CompanionVariant;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;

/**
 * Olhos fechados da companheira de cabelo vermelho enquanto ela dorme (no sono do Modo Demônio e no sono comum): uma
 * textura só com os 8 pixels dos olhos pintados como pálpebras (pele + cílios), desenhada por cima do rosto original.
 */
public class DemonClosedEyesLayer extends RenderLayer<CompanionRenderState, CompanionModel> {
	private static final Identifier TEXTURE = AutonomousCompanions.id("textures/entity/companion/rubi_closed_eyes.png");

	public DemonClosedEyesLayer(RenderLayerParent<CompanionRenderState, CompanionModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CompanionRenderState state, float yRot, float xRot) {
		if (state.variant != CompanionVariant.RUBI || !state.hasPose(Pose.SLEEPING) || state.isInvisible) {
			return;
		}
		collector.order(1).submitModel(this.getParentModel(), state, poseStack, RenderTypes.entityCutout(TEXTURE), lightCoords, OverlayTexture.NO_OVERLAY,
			state.outlineColor);
	}
}
