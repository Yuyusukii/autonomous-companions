package dev.autonomouscompanions.ai;

/**
 * Estados observáveis da máquina de estados. O estado é sincronizado com o cliente para escolher animações
 * e exibido na interface. A decisão de qual comportamento executar fica em {@link CompanionBrain}.
 */
public enum CompanionState {
	WAITING_INTERACTION("aguardando_interacao", Pose.IDLE),
	FOLLOWING("seguindo", Pose.IDLE),
	EXPLORING("explorando", Pose.IDLE),
	STAYING("aguardando_no_local", Pose.IDLE),
	RECEIVING_ORDER("recebendo_ordem", Pose.IDLE),
	PLANNING("planejando", Pose.IDLE),
	SEARCHING("procurando_recursos", Pose.IDLE),
	TRAVELING("viajando", Pose.RUNNING),
	COLLECTING("coletando", Pose.WORKING),
	CRAFTING_TOOLS("fabricando_ferramentas", Pose.WORKING),
	CRAFTING_EQUIPMENT("fabricando_equipamentos", Pose.WORKING),
	SMELTING("fundindo", Pose.WORKING),
	HUNTING("cacando", Pose.RUNNING),
	RETURNING("retornando", Pose.RUNNING),
	DELIVERING("entregando", Pose.IDLE),
	BUILDING("construindo", Pose.WORKING),
	PROTECTING_OWNER("protegendo", Pose.RUNNING),
	COMBAT("combatendo", Pose.RUNNING),
	SEEKING_FOOD("procurando_alimento", Pose.IDLE),
	EATING("alimentando", Pose.IDLE),
	SEEKING_REST("procurando_descanso", Pose.IDLE),
	SLEEPING("dormindo", Pose.SLEEPING),
	RESTING("descansando", Pose.SITTING),
	RECOVERING_FROM_DANGER("fugindo_perigo", Pose.RUNNING),
	NO_ROUTE("sem_rota", Pose.IDLE),
	TASK_COMPLETED("tarefa_concluida", Pose.IDLE),
	TASK_SUSPENDED("tarefa_suspensa", Pose.IDLE);

	/** Pose visual associada (usada pelo renderizador do cliente). */
	public enum Pose {
		IDLE,
		RUNNING,
		WORKING,
		SITTING,
		SLEEPING
	}

	private final String id;
	private final Pose pose;

	CompanionState(String id, Pose pose) {
		this.id = id;
		this.pose = pose;
	}

	public String id() {
		return id;
	}

	public Pose pose() {
		return pose;
	}

	public String translationKey() {
		return "state.autonomouscompanions." + id;
	}

	public static CompanionState byIndex(int index) {
		CompanionState[] values = values();
		return index >= 0 && index < values.length ? values[index] : WAITING_INTERACTION;
	}
}
