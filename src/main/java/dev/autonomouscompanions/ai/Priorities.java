package dev.autonomouscompanions.ai;

/**
 * Prioridades gerais (maior vence). Ordem exigida:
 * 1) evitar morte iminente, 2) proteger o dono, 3) necessidades críticas, 4) ordens explícitas/urgentes,
 * 5) função especializada, 6) seguir e explorar.
 */
public final class Priorities {
	public static final int FLEE = 100;
	/** Chamado pelo apito da dona. */
	public static final int RECALL = 95;
	/** Presa num buraco ou espaço fechado: sair antes de qualquer outra coisa. */
	public static final int UNSTUCK = 92;
	public static final int DEFEND_OWNER = 90;
	public static final int SELF_DEFENSE = 86;
	public static final int CRITICAL_FOOD = 80;
	public static final int EXHAUSTED_SLEEP = 76;
	/** Ajudante: alguém está com fome ou ferida e sem comida. */
	public static final int SUPPLY_URGENT = 74;
	/** Comer para recuperar vida (obrigatório quando ferida e sem perigo imediato). */
	public static final int HEAL_EAT = 72;
	public static final int URGENT_ORDER = 70;
	public static final int EAT = 64;
	public static final int ORDER = 60;
	public static final int PROACTIVE_DEFENSE = 58;
	public static final int NIGHT_SLEEP = 50;
	/** Acabou uma ordem: volta para perto da dona antes de retomar a função. */
	/** Ajudante: entrega de itens de pedra e reposição de comida. */
	public static final int SUPPLY = 45;
	public static final int RETURN_AFTER_ORDER = 40;
	public static final int ROLE_WORK = 35;
	public static final int GUARD_POST = 34;
	public static final int RETURN_TOO_FAR = 33;
	/** Ajudante: estoque próprio de carne. */
	public static final int SUPPLY_STOCK = 30;
	/** Sair da água quando não há nada mais importante a fazer. */
	public static final int LEAVE_WATER = 25;
	public static final int STAY = 22;
	public static final int FOLLOW = 20;
	public static final int EXPLORE = 15;
	public static final int WAIT_INTERACTION = 5;

	private Priorities() {
	}
}
