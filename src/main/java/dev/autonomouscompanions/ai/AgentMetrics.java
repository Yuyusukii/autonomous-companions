package dev.autonomouscompanions.ai;

import java.util.EnumMap;
import java.util.Map;

/**
 * Medições de uma companheira, para diagnóstico e para comparar desempenho antes/depois de mudanças: custo da IA por
 * tick (média e pior caso), cálculos de caminho, trocas de comportamento, interrupções da ordem e tempo gasto em cada
 * estado. Só contadores em memória (nada é salvo); custo desprezível.
 */
public final class AgentMetrics {
	private long ticks;
	private long nanos;
	private long maxNanos;
	private long pathComputations;
	private long behaviorSwitches;
	private long orderInterruptions;
	private String lastInterruption = "-";
	private long worstStepNanos;
	private String worstStep = "-";

	/** Etapa de missão mais cara num único tick (para achar picos de processamento). */
	public void stepTick(String step, long spent) {
		if (spent > worstStepNanos) {
			worstStepNanos = spent;
			worstStep = step;
		}
	}

	public String worstStep() {
		return worstStep + String.format(" (%.1f ms)", worstStepNanos / 1.0E6);
	}
	private final Map<CompanionState, Long> stateTicks = new EnumMap<>(CompanionState.class);

	public void addTick(long spentNanos, CompanionState state) {
		ticks++;
		nanos += spentNanos;
		maxNanos = Math.max(maxNanos, spentNanos);
		stateTicks.merge(state, 1L, Long::sum);
	}

	public void pathComputed() {
		pathComputations++;
	}

	public void behaviorSwitched() {
		behaviorSwitches++;
	}

	public void orderInterrupted(String by) {
		orderInterruptions++;
		lastInterruption = by;
	}

	private long memoryRecalls;

	/** Usou a memória persistente para decidir aonde ir (recurso lembrado além do raio da busca ou área por hipótese). */
	public void memoryRecalled() {
		memoryRecalls++;
	}

	public long memoryRecalls() {
		return memoryRecalls;
	}

	private long explorationLegs;

	/** Saiu explorando sem destino conhecido (espiral ou bioma), por falta de recurso visto ou lembrado. */
	public void explorationLeg() {
		explorationLegs++;
	}

	public long explorationLegs() {
		return explorationLegs;
	}

	private long skillRecoveries;
	private long learnedAdjustments;

	/** Uma habilidade aplicou um procedimento de recuperação depois de uma etapa falhar. */
	public void skillRecovered() {
		skillRecoveries++;
	}

	public long skillRecoveries() {
		return skillRecoveries;
	}

	/** Mudou a forma de agir por causa de uma experiência recente (ex.: falhou por não achar e já começa procurando mais longe). */
	public void learnedAdjustment() {
		learnedAdjustments++;
	}

	public long learnedAdjustments() {
		return learnedAdjustments;
	}

	public long ticks() {
		return ticks;
	}

	public double averageMicros() {
		return ticks == 0 ? 0 : nanos / 1000.0 / ticks;
	}

	public double maxMicros() {
		return maxNanos / 1000.0;
	}

	public long pathComputations() {
		return pathComputations;
	}

	public long behaviorSwitches() {
		return behaviorSwitches;
	}

	public long orderInterruptions() {
		return orderInterruptions;
	}

	public String lastInterruption() {
		return lastInterruption;
	}

	public long ticksIn(CompanionState state) {
		return stateTicks.getOrDefault(state, 0L);
	}

	public Map<CompanionState, Long> stateTicks() {
		return stateTicks;
	}

	public void reset() {
		ticks = 0;
		nanos = 0;
		maxNanos = 0;
		pathComputations = 0;
		behaviorSwitches = 0;
		orderInterruptions = 0;
		lastInterruption = "-";
		worstStepNanos = 0;
		worstStep = "-";
		memoryRecalls = 0;
		explorationLegs = 0;
		skillRecoveries = 0;
		learnedAdjustments = 0;
		stateTicks.clear();
	}
}
