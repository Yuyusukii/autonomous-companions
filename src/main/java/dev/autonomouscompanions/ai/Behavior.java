package dev.autonomouscompanions.ai;

import dev.autonomouscompanions.entity.CompanionEntity;
import net.minecraft.server.level.ServerLevel;

/**
 * Um comportamento de alto nível disputado pelo {@link CompanionBrain}. A cada ciclo de decisão o cérebro consulta
 * {@link #priority} de todos os comportamentos e executa o de maior prioridade. Comportamentos interrompidos devem
 * guardar o próprio estado para retomar depois (ex.: missão pausada durante um combate).
 */
public abstract class Behavior {
	protected final CompanionEntity companion;
	protected final CompanionBrain brain;

	protected Behavior(CompanionEntity companion, CompanionBrain brain) {
		this.companion = companion;
		this.brain = brain;
	}

	/** Prioridade atual (0 = não aplicável). Deve ser barata: é chamada a cada ciclo de decisão. */
	public abstract int priority(ServerLevel level);

	public void start(ServerLevel level) {
	}

	/** @return verdadeiro enquanto o comportamento quer continuar executando. */
	public abstract boolean tick(ServerLevel level);

	public void stop(ServerLevel level) {
	}

	/** Comportamentos que não devem ser trocados por outro de prioridade apenas ligeiramente maior. */
	public int hysteresis() {
		return 0;
	}

	public String name() {
		return getClass().getSimpleName();
	}
}
