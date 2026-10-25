package dev.autonomouscompanions.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionVariant;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.Vec3;

/**
 * Renderizador das companheiras: geometria de jogador "slim" (as três skins têm braços de 3 px), armaduras reais
 * equipadas, item na mão e nome sincronizado acima da cabeça. A pose segue o estado real da IA.
 */
public class CompanionRenderer extends HumanoidMobRenderer<CompanionEntity, CompanionRenderState, CompanionModel> {
	private static final Identifier[] TEXTURES = new Identifier[CompanionVariant.values().length];

	static {
		for (CompanionVariant v : CompanionVariant.values()) {
			TEXTURES[v.ordinal()] = AutonomousCompanions.id("textures/entity/companion/" + v.id() + ".png");
		}
	}

	public CompanionRenderer(EntityRendererProvider.Context context) {
		this(context, true);
	}

	/** {@code slim = false}: geometria de braços largos (4 px), usada pela ajudante. */
	public CompanionRenderer(EntityRendererProvider.Context context, boolean slim) {
		super(context, new CompanionModel(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER)), 0.5F);
		this.addLayer(new HumanoidArmorLayer<>(this, ArmorModelSet.bake(slim ? ModelLayers.PLAYER_SLIM_ARMOR : ModelLayers.PLAYER_ARMOR,
			context.getModelSet(), CompanionModel::new), context.getEquipmentRenderer()));
		this.addLayer(new DemonEyesLayer(this));
		this.addLayer(new DemonClosedEyesLayer(this));
	}

	@Override
	public CompanionRenderState createRenderState() {
		return new CompanionRenderState();
	}

	@Override
	public void extractRenderState(CompanionEntity entity, CompanionRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.variant = entity.variant();
		CompanionState.Pose pose = entity.companionState().pose();
		state.companionPose = pose;
		state.sitting = pose == CompanionState.Pose.SITTING && !entity.isSleeping();
		state.running = pose == CompanionState.Pose.RUNNING || entity.isSprinting();
		state.working = pose == CompanionState.Pose.WORKING;
		state.demon = entity.demonState();
		state.demonAge = entity.demonAge(partialTicks);
		// Olhos vermelhos a partir da metade da transformação até a exaustão.
		state.demonEyes = state.variant == CompanionVariant.RUBI && (state.demon == dev.autonomouscompanions.demon.DemonState.ACTIVE
			|| state.demon == dev.autonomouscompanions.demon.DemonState.TRANSFORMING && state.demonAge > 25
			|| state.demon == dev.autonomouscompanions.demon.DemonState.EXHAUSTED && state.demonAge < 30);
		if (state.demon.engaged()) {
			state.running = false;
			state.working = false;
			state.sitting = false;
		}
		if (state.sitting) {
			state.isPassenger = true; // reaproveita a pose sentada do modelo humanoide
		}
	}

	@Override
	protected HumanoidModel.ArmPose getArmPose(CompanionEntity mob, HumanoidArm arm) {
		ItemStack held = mob.getItemHeldByArm(arm);
		if (held.isEmpty()) {
			return HumanoidModel.ArmPose.EMPTY;
		}
		InteractionHand hand = arm == mob.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		if (mob.isUsingItem() && mob.getUsedItemHand() == hand) {
			ItemUseAnimation anim = held.getUseAnimation();
			if (anim == ItemUseAnimation.BLOCK) {
				return HumanoidModel.ArmPose.BLOCK;
			}
			if (anim == ItemUseAnimation.BOW) {
				return HumanoidModel.ArmPose.BOW_AND_ARROW;
			}
		}
		return HumanoidModel.ArmPose.ITEM;
	}

	@Override
	public Identifier getTextureLocation(CompanionRenderState state) {
		return TEXTURES[state.variant.ordinal()];
	}

	@Override
	protected void scale(CompanionRenderState state, PoseStack poseStack) {
		// Mesma escala do jogador.
		poseStack.scale(0.9375F, 0.9375F, 0.9375F);
	}

	@Override
	public Vec3 getRenderOffset(CompanionRenderState state) {
		Vec3 offset = super.getRenderOffset(state);
		if (state.sitting) {
			return offset.add(0.0, -0.55 * state.scale, 0.0);
		}
		return offset;
	}
}
