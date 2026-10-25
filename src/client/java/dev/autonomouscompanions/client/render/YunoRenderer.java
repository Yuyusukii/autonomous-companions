package dev.autonomouscompanions.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.yuno.YunoEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;

/** Yuno: mesma geometria "slim" das companheiras, com a skin dela. Sem nome flutuante (ela não quer ser notada). */
public class YunoRenderer extends HumanoidMobRenderer<YunoEntity, CompanionRenderState, CompanionModel> {
	private static final Identifier TEXTURE = AutonomousCompanions.id("textures/entity/companion/yuno.png");

	public YunoRenderer(EntityRendererProvider.Context context) {
		super(context, new CompanionModel(context.bakeLayer(ModelLayers.PLAYER_SLIM)), 0.5F);
	}

	@Override
	public CompanionRenderState createRenderState() {
		return new CompanionRenderState();
	}

	@Override
	public Identifier getTextureLocation(CompanionRenderState state) {
		return TEXTURE;
	}

	@Override
	protected boolean shouldShowName(YunoEntity entity, double distanceToCameraSq) {
		return false;
	}

	@Override
	protected void scale(CompanionRenderState state, PoseStack poseStack) {
		poseStack.scale(0.9375F, 0.9375F, 0.9375F);
	}
}
