package dev.autonomouscompanions.survival;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.autonomouscompanions.config.ConfigManager;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.food.FoodProperties;

/**
 * Fome, saturação, exaustão e cansaço próprios da companheira. Os valores afetam diretamente as decisões
 * da IA (ver {@code CompanionBrain}) e a regeneração/dano de fome.
 */
public final class CompanionNeeds {
	public static final int MAX_HUNGER = 20;
	public static final float MAX_FATIGUE = 100.0f;
	/** Fome igual ou abaixo deste valor é crítica: tem prioridade sobre tarefas não urgentes. */
	public static final int CRITICAL_HUNGER = 6;
	/** Abaixo deste valor ela come quando houver comida e uma pausa segura. */
	public static final int HUNGRY = 14;
	public static final float TIRED = 70.0f;
	public static final float EXHAUSTED = 92.0f;

	public static final Codec<CompanionNeeds> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("hunger").forGetter(n -> n.hunger),
		Codec.FLOAT.fieldOf("saturation").forGetter(n -> n.saturation),
		Codec.FLOAT.fieldOf("exhaustion").forGetter(n -> n.exhaustion),
		Codec.FLOAT.fieldOf("fatigue").forGetter(n -> n.fatigue),
		Codec.INT.optionalFieldOf("regenTimer", 0).forGetter(n -> n.regenTimer)
	).apply(i, CompanionNeeds::new));

	private int hunger = MAX_HUNGER;
	private float saturation = 5.0f;
	private float exhaustion;
	private float fatigue;
	private int regenTimer;
	/** Regras novas (padrão): fome sempre cheia e disposição nunca cai. Definidas pela entidade a cada tick; não são salvas. */
	private boolean noHunger;
	private boolean noFatigue;

	public CompanionNeeds() {
	}

	public void setSustained(boolean noHunger, boolean noFatigue) {
		this.noHunger = noHunger;
		this.noFatigue = noFatigue;
	}

	private CompanionNeeds(int hunger, float saturation, float exhaustion, float fatigue, int regenTimer) {
		this.hunger = Mth.clamp(hunger, 0, MAX_HUNGER);
		this.saturation = Mth.clamp(saturation, 0, this.hunger);
		this.exhaustion = Math.max(0, exhaustion);
		this.fatigue = Mth.clamp(fatigue, 0, MAX_FATIGUE);
		this.regenTimer = regenTimer;
	}

	public int hunger() {
		return hunger;
	}

	public float saturation() {
		return saturation;
	}

	public float fatigue() {
		return fatigue;
	}

	public void setHunger(int hunger) {
		this.hunger = Mth.clamp(hunger, 0, MAX_HUNGER);
		this.saturation = Math.min(this.saturation, this.hunger);
	}

	public void setFatigue(float fatigue) {
		this.fatigue = Mth.clamp(fatigue, 0, MAX_FATIGUE);
	}

	public boolean isCriticallyHungry() {
		return hunger <= CRITICAL_HUNGER;
	}

	public boolean isHungry() {
		return hunger <= HUNGRY;
	}

	public boolean wantsToEat(int nutritionAvailable) {
		return hunger + nutritionAvailable <= MAX_HUNGER || hunger <= HUNGRY;
	}

	public boolean isTired() {
		return fatigue >= TIRED;
	}

	public boolean isExhausted() {
		return fatigue >= EXHAUSTED;
	}

	/** Exaustão de atividades (minerar, correr, lutar), análoga à do jogador. */
	public void addExhaustion(float amount) {
		if (noHunger) {
			return;
		}
		this.exhaustion = Math.min(40.0f, this.exhaustion + amount * ConfigManager.get().multiplicadorFome);
	}

	public void eat(FoodProperties food) {
		this.hunger = Math.min(MAX_HUNGER, this.hunger + food.nutrition());
		this.saturation = Math.min(this.hunger, this.saturation + food.saturation());
		// Comer devolve um pouco de disposição (1 ponto por ponto de fome recuperado; carne assada ≈ 8).
		this.fatigue = Math.max(0, this.fatigue - food.nutrition());
	}

	/**
	 * Avança as necessidades em um tick.
	 *
	 * @param activity 0 = parada, 1 = andando, 2 = trabalhando, 3 = combatendo
	 * @return dano de fome a aplicar neste tick (0 se nenhum) ou valor negativo indicando cura
	 */
	public float tick(int activity, boolean sleeping, boolean resting, float health, float maxHealth, Difficulty difficulty, boolean naturalRegen) {
		float hungerScale = ConfigManager.get().multiplicadorFome;
		if (noHunger) {
			// Sem fome: a barra fica sempre cheia (não precisa comer).
			hunger = MAX_HUNGER;
			saturation = Math.max(saturation, 5.0f);
			exhaustion = 0;
		} else {
			// Gasto basal: o equivalente a ~1 ponto de fome a cada ~5 minutos parada.
			addExhaustion((sleeping ? 0.0005f : 0.0012f) * (1 + activity) * (hungerScale > 0 ? 1 : 0));
		}

		while (exhaustion >= 4.0f) {
			exhaustion -= 4.0f;
			if (saturation > 0) {
				saturation = Math.max(0, saturation - 1.0f);
			} else if (difficulty != Difficulty.PEACEFUL) {
				hunger = Math.max(0, hunger - 1);
			}
		}

		float fatigueScale = ConfigManager.get().sonoHabilitado ? ConfigManager.get().multiplicadorCansaco : 0.0f;
		if (noFatigue) {
			fatigue = 0; // sem cansaço: a disposição nunca cai
		} else if (sleeping) {
			fatigue = Math.max(0, fatigue - 0.035f); // ~2.4 minutos de sono para recuperar tudo
		} else if (resting) {
			fatigue = Math.max(0, fatigue - 0.012f);
		} else {
			// Disposição +500% (pedido da jogadora): cansa 6 vezes mais devagar que a versão anterior. Trabalhando sem parar
			// (atividade 2): ~4 h reais para chegar a 100 bem alimentada (fome ≥ 16), ~3 h com fome média e ~2 h com fome
			// crítica; andando, bem mais devagar.
			float food = hunger >= 16 ? 0.75f : hunger <= CRITICAL_HUNGER ? 1.5f : 1.0f;
			fatigue = Math.min(MAX_FATIGUE, fatigue + (0.0014f / 6) * (1 + activity * 0.5f) * food * fatigueScale);
		}

		float result = 0;
		regenTimer++;
		if (naturalRegen && hunger >= MAX_HUNGER && saturation > 0 && health < maxHealth && health > 0) {
			// Fome cheia com saturação: regeneração rápida, como a do jogador (a cada 10 ticks, gastando saturação).
			if (regenTimer >= 10) {
				regenTimer = 0;
				float used = Math.min(saturation, 6.0f);
				addExhaustion(used);
				result = -used / 6.0f;
			}
		} else if (naturalRegen && hunger >= 18 && health < maxHealth && health > 0) {
			if (regenTimer >= 80) {
				regenTimer = 0;
				addExhaustion(3.0f);
				result = -1.0f;
			}
		} else if (hunger <= 0) {
			if (regenTimer >= 80) {
				regenTimer = 0;
				boolean lethal = difficulty == Difficulty.HARD;
				boolean allowed = lethal || (difficulty == Difficulty.NORMAL && health > 1.0f) || (difficulty == Difficulty.EASY && health > 10.0f);
				if (allowed) {
					result = 1.0f;
				}
			}
		} else if (regenTimer > 80) {
			regenTimer = 0;
		}
		return result;
	}
}
