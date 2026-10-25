package dev.autonomouscompanions.client.render;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionVariant;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/** Estado de renderização extraído da entidade a cada quadro (o modelo nunca acessa a entidade diretamente). */
public class CompanionRenderState extends HumanoidRenderState {
	public CompanionVariant variant = CompanionVariant.VIOLETA;
	public CompanionState.Pose companionPose = CompanionState.Pose.IDLE;
	public boolean sitting;
	public boolean running;
	public boolean working;
	/** Modo Demônio: estado, ticks no estado (com fração) e olhos brilhando. */
	public dev.autonomouscompanions.demon.DemonState demon = dev.autonomouscompanions.demon.DemonState.NORMAL;
	public float demonAge;
	public boolean demonEyes;
}
