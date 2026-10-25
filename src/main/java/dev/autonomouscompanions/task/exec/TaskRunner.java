package dev.autonomouscompanions.task.exec;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.config.ConfigManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Executa uma pilha de etapas, um tick por vez, propagando resultados para as etapas-mãe. */
public final class TaskRunner {
	private final Deque<Step> stack = new ArrayDeque<>();
	private @Nullable StepResult finalResult;

	public TaskRunner(Step root) {
		stack.push(root);
	}

	public boolean isFinished() {
		return finalResult != null;
	}

	public @Nullable StepResult finalResult() {
		return finalResult;
	}

	public @Nullable Step current() {
		return stack.peek();
	}

	public int depth() {
		return stack.size();
	}

	/** Materiais em uso por todas as etapas da pilha. */
	public java.util.Map<net.minecraft.world.item.Item, Integer> workingItems() {
		java.util.Map<net.minecraft.world.item.Item, Integer> out = new java.util.HashMap<>();
		for (Step s : stack) {
			s.workingItems(out);
		}
		return out;
	}

	/** Descrição da pilha (da raiz para a etapa atual), exibida na interface. */
	public List<Component> describeStack() {
		List<Component> list = new ArrayList<>();
		var it = stack.descendingIterator();
		while (it.hasNext()) {
			list.add(it.next().describe());
		}
		return list;
	}

	public CompanionState currentState() {
		Step top = stack.peek();
		return top == null ? CompanionState.PLANNING : top.state();
	}

	public StepResult tick(TaskContext ctx) {
		if (finalResult != null) {
			return finalResult;
		}
		Step top = stack.peek();
		if (top == null) {
			finalResult = StepResult.success();
			return finalResult;
		}
		if (!top.started) {
			top.started = true;
			top.start(ctx);
		}
		StepResult r = top.tick(ctx);
		switch (r.kind()) {
			case RUNNING -> {
				return r;
			}
			case PUSH -> {
				Step child = r.child();
				if (child == null) {
					return StepResult.running();
				}
				if (stack.size() >= ConfigManager.get().maxProfundidadePlanejamento + 8) {
					// Cadeia de dependências profunda demais: trata como falha da etapa atual.
					StepResult fail = StepResult.fail(StepResult.FailureCode.IMPOSSIBLE, Component.translatable("reason.autonomouscompanions.plan_too_deep"));
					return finish(ctx, top, fail);
				}
				child.parent = top;
				stack.push(child);
				return StepResult.running();
			}
			default -> {
				return finish(ctx, top, r);
			}
		}
	}

	private StepResult finish(TaskContext ctx, Step top, StepResult r) {
		stack.pop();
		top.stop(ctx);
		Step parent = stack.peek();
		if (parent == null) {
			finalResult = r;
			return r;
		}
		parent.onChildFinished(ctx, top, r);
		return StepResult.running();
	}

	public void abort(TaskContext ctx) {
		while (!stack.isEmpty()) {
			stack.pop().stop(ctx);
		}
		finalResult = StepResult.fail(StepResult.FailureCode.CANCELLED, Component.translatable("reason.autonomouscompanions.cancelled"));
	}

	/** Pausa: interrompe navegação das etapas sem desmontar a pilha (para retomar depois). */
	public void pause(TaskContext ctx) {
		ctx.brain().navigator().clear();
		ctx.breaker().reset(ctx.level(), ctx.companion());
	}
}
