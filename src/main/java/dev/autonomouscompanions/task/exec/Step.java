package dev.autonomouscompanions.task.exec;

import dev.autonomouscompanions.ai.CompanionState;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Uma etapa executável de uma tarefa. Etapas formam uma pilha: uma etapa pode empilhar sub-etapas (ex.: "obter
 * picareta" empilha "fabricar picareta", que empilha "obter tábuas"...). Ao terminar, o resultado volta para a etapa-mãe.
 */
public abstract class Step {
	boolean started;
	@Nullable Step parent;

	public void start(TaskContext ctx) {
	}

	public abstract StepResult tick(TaskContext ctx);

	/** Chamado na etapa-mãe quando uma sub-etapa termina (com sucesso ou falha). */
	public void onChildFinished(TaskContext ctx, Step child, StepResult result) {
	}

	/** Liberação de recursos (navegação, reservas). Chamado ao sair da pilha ou ao abortar a tarefa. */
	/** Materiais que esta etapa está juntando ou vai consumir (protegidos enquanto ela estiver na pilha). */
	public void workingItems(java.util.Map<net.minecraft.world.item.Item, Integer> out) {
	}

	public void stop(TaskContext ctx) {
	}

	public abstract Component describe();

	public CompanionState state() {
		return CompanionState.PLANNING;
	}

	public @Nullable Step parent() {
		return parent;
	}
}
