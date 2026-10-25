package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import org.jspecify.annotations.Nullable;

/** Etapa que empilha sub-etapas e, por padrão, falha quando uma sub-etapa obrigatória falha. */
public abstract class CompositeStep extends Step {
	protected @Nullable StepResult childFailure;

	@Override
	public void onChildFinished(TaskContext ctx, Step child, StepResult result) {
		if (result.isFailure() && !isOptional(child)) {
			childFailure = result;
		}
		ctx.invalidateCosts();
	}

	/** Sub-etapas opcionais (ex.: ferramenta que só acelera) não derrubam a etapa-mãe. */
	protected boolean isOptional(Step child) {
		return false;
	}

	@Override
	public final StepResult tick(TaskContext ctx) {
		if (childFailure != null) {
			StepResult f = childFailure;
			childFailure = null;
			return onFailure(ctx, f);
		}
		return run(ctx);
	}

	/** Pode ser sobrescrito para tentar alternativas antes de desistir. */
	protected StepResult onFailure(TaskContext ctx, StepResult failure) {
		return failure;
	}

	protected abstract StepResult run(TaskContext ctx);
}
