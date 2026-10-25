package dev.autonomouscompanions.task.exec;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Resultado de um tick de etapa. */
public record StepResult(Kind kind, @Nullable Component reason, @Nullable Step child, @Nullable FailureCode code) {
	public enum Kind {
		RUNNING,
		SUCCESS,
		FAILURE,
		PUSH
	}

	/** Falhas com tratamento especial pelo executor de ordens. */
	public enum FailureCode {
		INVENTORY_FULL,
		IMPOSSIBLE,
		NOT_FOUND,
		NO_ROUTE,
		DANGER,
		WRONG_DIMENSION,
		/** Os itens do pedido não estão mais com ela (comidos, gastos, perdidos): não há o que entregar. */
		ITEMS_MISSING,
		CANCELLED
	}

	private static final StepResult RUNNING = new StepResult(Kind.RUNNING, null, null, null);
	private static final StepResult SUCCESS = new StepResult(Kind.SUCCESS, null, null, null);

	public static StepResult running() {
		return RUNNING;
	}

	public static StepResult success() {
		return SUCCESS;
	}

	public static StepResult fail(Component reason) {
		return new StepResult(Kind.FAILURE, reason, null, null);
	}

	public static StepResult fail(FailureCode code, Component reason) {
		return new StepResult(Kind.FAILURE, reason, null, code);
	}

	public static StepResult push(Step child) {
		return new StepResult(Kind.PUSH, null, child, null);
	}

	public boolean isSuccess() {
		return kind == Kind.SUCCESS;
	}

	public boolean isFailure() {
		return kind == Kind.FAILURE;
	}
}
