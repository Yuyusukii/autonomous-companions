package dev.autonomouscompanions.demon;

/** Estados do Modo Demônio (exclusivo da companheira de cabelo vermelho). */
public enum DemonState {
	/** Comportamento habitual. */
	NORMAL,
	/** Animação de transformação (início dos 20 s), proteção de sobrevivência e início da tempestade. */
	TRANSFORMING,
	/** 100 corações, flutuação, aura e caçada com teletransporte e eliminação instantânea. */
	ACTIVE,
	/** Fim dos 20 s: a aura se apaga e ela desce até o chão. */
	EXHAUSTED,
	/** Sono obrigatório de 200 s, deitada no chão, protegida e sem IA. */
	SLEEPING,
	/** Despertar: remove os efeitos temporários e volta ao normal. */
	RECOVERING;

	public static DemonState byIndex(int i) {
		DemonState[] v = values();
		return i >= 0 && i < v.length ? v[i] : NORMAL;
	}

	/** A IA normal fica suspensa (a máquina de estados do modo controla tudo). */
	public boolean engaged() {
		return this != NORMAL;
	}

	/** Imune a dano comum e a efeitos incapacitantes. */
	public boolean protectedState() {
		return this == TRANSFORMING || this == ACTIVE || this == EXHAUSTED || this == SLEEPING;
	}

	/** Vida máxima temporária de 200 (100 corações) aplicada. */
	public boolean boosted() {
		return protectedState();
	}

	/** Aura demoníaca visível (o cliente desenha as partículas e o brilho dos olhos). */
	public boolean aura() {
		return this == TRANSFORMING || this == ACTIVE || this == EXHAUSTED;
	}
}
