package dev.autonomouscompanions.entity;

/**
 * Especializações. Cada função altera prioridades, alcance de busca, agressividade e o trabalho autônomo
 * executado quando não há ordens explícitas (ver {@code RoleWorkBehavior}).
 */
public enum CompanionRole {
	NONE("nenhuma", 1.0, 1.0, 0, false),
	MINER("mineradora", 1.5, 1.0, 0, false),
	LUMBERJACK("lenhadora", 1.0, 1.0, 0, false),
	BUILDER("construtora", 1.0, 1.0, 0, false),
	EXPLORER("exploradora", 2.0, 1.0, 0, false),
	GUARD("guarda", 1.0, 0.6, 2, true),
	GATHERER("coletora", 1.25, 1.0, 0, false),
	FARMER("agricultora", 1.0, 1.0, 0, false);

	private final String id;
	/** Multiplicador do alcance automático de busca de recursos. */
	public final double searchRangeMultiplier;
	/** Multiplicador do limiar de vida para fuga (menor = foge mais tarde). */
	public final double fleeThresholdMultiplier;
	/** Bônus de prioridade de combate. */
	public final int combatPriorityBonus;
	/** Ataca hostis próximos ao dono mesmo antes de atacarem. */
	public final boolean proactiveDefense;

	CompanionRole(String id, double searchRangeMultiplier, double fleeThresholdMultiplier, int combatPriorityBonus, boolean proactiveDefense) {
		this.id = id;
		this.searchRangeMultiplier = searchRangeMultiplier;
		this.fleeThresholdMultiplier = fleeThresholdMultiplier;
		this.combatPriorityBonus = combatPriorityBonus;
		this.proactiveDefense = proactiveDefense;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "role.autonomouscompanions." + id;
	}

	public String descriptionKey() {
		return "role.autonomouscompanions." + id + ".desc";
	}

	public static CompanionRole byId(String id) {
		for (CompanionRole r : values()) {
			if (r.id.equals(id) || r.name().equalsIgnoreCase(id)) {
				return r;
			}
		}
		return NONE;
	}

	public static CompanionRole byIndex(int index) {
		CompanionRole[] values = values();
		return values[Math.floorMod(index, values.length)];
	}
}
