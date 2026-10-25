package dev.autonomouscompanions.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Modelo humanoide feminino de braços finos (3 px, geometria "slim" do jogador) com camadas externas da skin
 * (cabelo/chapéu, mangas, calças e jaqueta). Adiciona poses próprias: corrida (tronco inclinado), trabalho
 * (braço de ferramenta firme à frente) e descanso sentada.
 */
public class CompanionModel extends HumanoidModel<CompanionRenderState> {
	private final ModelPart leftSleeve;
	private final ModelPart rightSleeve;
	private final ModelPart leftPants;
	private final ModelPart rightPants;
	private final ModelPart jacket;

	public CompanionModel(ModelPart root) {
		super(root, RenderTypes::entityTranslucent);
		this.leftSleeve = this.leftArm.getChild("left_sleeve");
		this.rightSleeve = this.rightArm.getChild("right_sleeve");
		this.leftPants = this.leftLeg.getChild("left_pants");
		this.rightPants = this.rightLeg.getChild("right_pants");
		this.jacket = this.body.getChild("jacket");
	}

	@Override
	public void setupAnim(CompanionRenderState state) {
		this.hat.visible = true;
		this.jacket.visible = true;
		this.leftSleeve.visible = true;
		this.rightSleeve.visible = true;
		this.leftPants.visible = true;
		this.rightPants.visible = true;
		super.setupAnim(state);
		if (state.running && !state.isPassenger && state.swingAnimation <= 0) {
			// Corrida: leve inclinação do tronco e braços mais abertos.
			float lean = 0.18F * Math.min(1.0F, state.walkAnimationSpeed * 1.5F);
			this.body.xRot += lean;
			this.head.xRot -= lean * 0.5F;
			this.rightArm.xRot *= 1.25F;
			this.leftArm.xRot *= 1.25F;
		}
		if (state.working && state.swingAnimation <= 0 && !state.isPassenger) {
			// Trabalho: braço da ferramenta firme à frente entre golpes.
			ModelPart arm = state.mainArm == HumanoidArm.RIGHT ? this.rightArm : this.leftArm;
			arm.xRot = Mth.lerp(0.6F, arm.xRot, -0.75F);
		}
		if (state.sitting) {
			// Descanso: mãos sobre os joelhos e cabeça levemente baixa.
			this.rightArm.xRot = -0.55F;
			this.leftArm.xRot = -0.55F;
			this.rightArm.zRot = 0.08F;
			this.leftArm.zRot = -0.08F;
			this.head.xRot += 0.15F;
		}
		if (state.demon.engaged()) {
			animateDemon(state);
		}
	}

	/**
	 * Animações do Modo Demônio, sobre o mesmo modelo e a mesma skin. O "cabelo" é a camada externa da cabeça (chapéu da
	 * skin): ela pulsa e oscila com a energia, sem trocar a textura.
	 */
	private void animateDemon(CompanionRenderState s) {
		float a = s.demonAge;
		boolean swinging = s.swingAnimation > 0;
		switch (s.demon) {
			case TRANSFORMING -> {
				// 0–0,5 s: para; 0,25–1 s: a cabeça inclina como se algo despertasse; depois se ergue, braços se abrem.
				float tilt = smooth(a, 5, 20) * (1.0F - smooth(a, 30, 45));
				this.head.zRot = 0.35F * tilt;
				this.head.xRot = 0.35F * tilt - 0.1F * smooth(a, 30, 50);
				float open = smooth(a, 18, 48);
				pose(open * 0.85F, open * 0.3F, 0.12F * open);
				this.body.xRot = -0.08F * open;
				dangle(a, smooth(a, 15, 50));
				hair(a, 0.2F + 0.8F * smooth(a, 10, 45));
			}
			case ACTIVE -> {
				if (!swinging) {
					pose(0.55F + 0.05F * Mth.sin(a * 0.2F), 0.25F, 0.1F);
				}
				this.body.xRot = 0.1F;
				dangle(a, 1.0F);
				hair(a, 1.0F);
			}
			case EXHAUSTED -> {
				// A energia acaba: braços caem, cabeça baixa, a flutuação falha.
				float fade = 1.0F - smooth(a, 0, 40);
				pose(0.5F * fade + 0.05F, 0.1F * fade, 0.0F);
				this.head.xRot = 0.55F * smooth(a, 0, 30);
				this.body.xRot = 0.2F * smooth(a, 0, 30);
				dangle(a, fade);
				hair(a, fade * 0.6F);
			}
			case SLEEPING -> {
				// Deitada (o renderizador do jogo deita o corpo): braços ao lado do corpo e respiração lenta.
				this.rightArm.xRot = 0.0F;
				this.leftArm.xRot = 0.0F;
				this.rightArm.zRot = 0.05F;
				this.leftArm.zRot = -0.05F;
				this.rightLeg.xRot = 0.0F;
				this.leftLeg.xRot = 0.0F;
				this.head.xRot = 0.0F;
				this.head.yRot = 0.0F;
				float breath = 1.0F + 0.018F * Mth.sin(a * 0.12F);
				this.body.xScale = breath;
				this.body.zScale = breath;
				this.jacket.xScale = breath;
				this.jacket.zScale = breath;
			}
			case RECOVERING -> {
				// Despertar: espreguiça (braços para cima) e olha ao redor antes de voltar ao normal.
				float stretch = 1.0F - smooth(a, 8, 30);
				this.rightArm.xRot = Mth.lerp(stretch, this.rightArm.xRot, -2.9F);
				this.leftArm.xRot = Mth.lerp(stretch, this.leftArm.xRot, -2.9F);
				this.head.yRot = 0.45F * Mth.sin(a * 0.18F) * smooth(a, 20, 30);
				this.head.xRot = -0.15F * stretch;
			}
			default -> {
			}
		}
	}

	/** Braços abertos para os lados ({@code out}), levemente para trás ({@code back}), com tensão nas mãos. */
	private void pose(float out, float back, float bend) {
		this.rightArm.zRot = out;
		this.leftArm.zRot = -out;
		this.rightArm.xRot = back;
		this.leftArm.xRot = back;
		this.rightArm.yRot = bend;
		this.leftArm.yRot = -bend;
	}

	/** Pernas penduradas durante a flutuação, com oscilação sutil. */
	private void dangle(float a, float k) {
		this.rightLeg.xRot = Mth.lerp(k, this.rightLeg.xRot, 0.22F + 0.05F * Mth.sin(a * 0.15F));
		this.leftLeg.xRot = Mth.lerp(k, this.leftLeg.xRot, 0.08F + 0.05F * Mth.sin(a * 0.15F + 1.3F));
		this.rightLeg.zRot = 0.04F * k;
		this.leftLeg.zRot = -0.04F * k;
	}

	/** Cabelo agitado pela energia: a camada externa da cabeça pulsa e oscila. */
	private void hair(float a, float k) {
		float pulse = 1.0F + k * (0.05F + 0.035F * Mth.sin(a * 0.9F));
		this.hat.xScale = pulse;
		this.hat.zScale = pulse;
		this.hat.yScale = 1.0F + k * 0.07F * (0.5F + 0.5F * Mth.sin(a * 0.6F));
		this.hat.zRot = k * 0.05F * Mth.sin(a * 0.7F);
		this.hat.xRot = k * 0.04F * Mth.sin(a * 0.53F + 0.8F);
	}

	private static float smooth(float a, float from, float to) {
		float t = Mth.clamp((a - from) / (to - from), 0.0F, 1.0F);
		return t * t * (3 - 2 * t);
	}

	@Override
	public void translateToHand(CompanionRenderState state, HumanoidArm arm, PoseStack poseStack) {
		this.root().translateAndRotate(poseStack);
		ModelPart part = this.getArm(arm);
		// Braço fino: centraliza o item na mão de 3 px (mesma correção do jogador "slim").
		float offset = 0.5F * (arm == HumanoidArm.RIGHT ? 1 : -1);
		part.x += offset;
		part.translateAndRotate(poseStack);
		part.x -= offset;
	}
}
