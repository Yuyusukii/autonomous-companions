package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.task.exec.TaskRunner;
import dev.autonomouscompanions.task.exec.steps.EscapeStep;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Detecta quando ela está presa — quer ir a algum lugar (há um destino de navegação), não sai do lugar há alguns
 * segundos e o A* do jogo não encontra caminho a partir de onde está — e executa a fuga ({@link EscapeStep}).
 */
public final class UnstuckBehavior extends Behavior {
	private Vec3 lastPos = Vec3.ZERO;
	private long lastSample;
	private int stuckSamples;
	private long lastCheck;
	private @Nullable BlockPos wantedGoal;
	private long wantedAt;
	private @Nullable BlockPos escapeTarget;
	private @Nullable TaskRunner runner;
	private @Nullable TaskContext context;

	public UnstuckBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		if (runner != null) {
			return Priorities.UNSTUCK;
		}
		long now = level.getGameTime();
		// O destino some por instantes quando a rota falha (o seguir limpa e tenta de novo): lembra o último por 3 s.
		BlockPos current = brain.navigator().goal();
		if (current != null) {
			wantedGoal = current;
			wantedAt = now;
		} else if (wantedGoal != null && now - wantedAt > 60) {
			wantedGoal = null;
		}
		BlockPos goal = wantedGoal;
		if (goal == null || companion.isSleeping() || companion.isPassenger() || !companion.isNamed()) {
			stuckSamples = 0;
			lastPos = companion.position();
			return 0;
		}
		if (now - lastSample >= 40) {
			lastSample = now;
			// Só o deslocamento horizontal conta: pular no lugar (o navegador tenta pular quando travado) não é sair do lugar.
			double dx = companion.getX() - lastPos.x;
			double dz = companion.getZ() - lastPos.z;
			stuckSamples = dx * dx + dz * dz < 0.36 ? stuckSamples + 1 : 0;
			lastPos = companion.position();
		}
		if (stuckSamples >= 3 && now - lastCheck > 100) {
			lastCheck = now;
			companion.debug("Sem sair do lugar há " + (stuckSamples * 2) + " s; verificando se está presa.");
			if (trapped(goal)) {
				escapeTarget = goal;
				return Priorities.UNSTUCK;
			}
		}
		return 0;
	}

	/** Sem caminho até o destino e com quase nenhum espaço alcançável ao redor. */
	private boolean trapped(BlockPos goal) {
		Path p = companion.getNavigation().createPath(goal, 1);
		if (dev.autonomouscompanions.nav.Paths.reaches(p, goal, 2.5)) {
			return false;
		}
		return p == null || p.getNodeCount() <= 3 || p.getEndNode() == null || p.getEndNode().asBlockPos().distManhattan(companion.blockPosition()) <= 2;
	}

	@Override
	public void start(ServerLevel level) {
		BlockPos target = escapeTarget != null ? escapeTarget : companion.blockPosition().above(3);
		companion.debug("Presa sem caminho até " + target.toShortString() + ": tentando sair sozinha.");
		context = new TaskContext(companion, brain, null, new ArrayList<>());
		runner = new TaskRunner(new EscapeStep(target));
		brain.navigator().clear();
	}

	@Override
	public boolean tick(ServerLevel level) {
		if (runner == null || context == null) {
			return false;
		}
		context.refresh(level);
		StepResult r = runner.tick(context);
		if (r.isSuccess() || r.isFailure()) {
			finish();
			return false;
		}
		return true;
	}

	private void finish() {
		wantedGoal = null;
		runner = null;
		context = null;
		escapeTarget = null;
		stuckSamples = 0;
		lastPos = companion.position();
	}

	@Override
	public void stop(ServerLevel level) {
		if (runner != null && context != null) {
			runner.abort(context);
		}
		finish();
	}
}
