package dev.autonomouscompanions.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Estatísticas acumuladas (persistentes) exibidas na interface. */
public final class CompanionStats {
	public static final Codec<CompanionStats> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.DOUBLE.optionalFieldOf("distance", 0.0).forGetter(s -> s.distanceTraveled),
		Codec.INT.optionalFieldOf("mined", 0).forGetter(s -> s.blocksMined),
		Codec.INT.optionalFieldOf("crafted", 0).forGetter(s -> s.itemsCrafted),
		Codec.INT.optionalFieldOf("placed", 0).forGetter(s -> s.blocksPlaced),
		Codec.INT.optionalFieldOf("kills", 0).forGetter(s -> s.kills),
		Codec.INT.optionalFieldOf("deaths", 0).forGetter(s -> s.deaths),
		Codec.INT.optionalFieldOf("meals", 0).forGetter(s -> s.meals),
		Codec.INT.optionalFieldOf("delivered", 0).forGetter(s -> s.itemsDelivered)
	).apply(i, CompanionStats::new));

	public double distanceTraveled;
	public int blocksMined;
	public int itemsCrafted;
	public int blocksPlaced;
	public int kills;
	public int deaths;
	public int meals;
	public int itemsDelivered;

	public CompanionStats() {
	}

	private CompanionStats(double distanceTraveled, int blocksMined, int itemsCrafted, int blocksPlaced, int kills, int deaths, int meals,
		int itemsDelivered) {
		this.distanceTraveled = distanceTraveled;
		this.blocksMined = blocksMined;
		this.itemsCrafted = itemsCrafted;
		this.blocksPlaced = blocksPlaced;
		this.kills = kills;
		this.deaths = deaths;
		this.meals = meals;
		this.itemsDelivered = itemsDelivered;
	}

	public void copyFrom(CompanionStats o) {
		this.distanceTraveled = o.distanceTraveled;
		this.blocksMined = o.blocksMined;
		this.itemsCrafted = o.itemsCrafted;
		this.blocksPlaced = o.blocksPlaced;
		this.kills = o.kills;
		this.deaths = o.deaths;
		this.meals = o.meals;
		this.itemsDelivered = o.itemsDelivered;
	}
}
