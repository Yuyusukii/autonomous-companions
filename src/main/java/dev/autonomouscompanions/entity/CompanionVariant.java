package dev.autonomouscompanions.entity;

/** Aparência de cada companheira. Cada variante usa uma skin própria (modelo humanoide de braços finos). */
public enum CompanionVariant {
	VIOLETA("violeta"),
	RUBI("rubi"),
	CORAL("coral"),
	/** A ajudante: serve as outras três (não recebe ordens da dona). */
	AJUDANTE("ajudante");

	private final String id;

	CompanionVariant(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public String translationKey() {
		return "variant.autonomouscompanions." + id;
	}

	public static CompanionVariant byIndex(int index) {
		CompanionVariant[] values = values();
		return values[Math.floorMod(index, values.length)];
	}
}
