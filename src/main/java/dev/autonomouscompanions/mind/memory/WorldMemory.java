package dev.autonomouscompanions.mind.memory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.autonomouscompanions.AutonomousCompanions;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Memória persistente das companheiras ({@code data/autonomouscompanions/memory.dat}, salva junto com o mundo; continua
 * existindo depois que o jogo é fechado). Três tipos de conhecimento, sempre separados:
 * <ul>
 *   <li><b>Fato</b> ({@link Certainty#FACT}): confirmado — por exemplo, a base que a dona indicou.</li>
 *   <li><b>Observação</b> ({@link Certainty#OBSERVATION}): algo que ela viu ou viveu, com data — posições de minérios e
 *       troncos vistos, lugares sem acesso. Toda posição é conferida no mundo antes do uso e esquecida quando deixa de
 *       ser verdade; as marcas de "sem acesso" expiram em 10 min: uma falha não vira regra.</li>
 *   <li><b>Hipótese</b> ({@link Certainty#HYPOTHESIS}): padrão inferido de várias observações — "área de ferro" onde ela
 *       já minerou ferro 3 vezes ou mais —, com confiança que sobe a cada confirmação e cai quando ela vai lá e não acha
 *       nada (abaixo de zero, a hipótese é descartada).</li>
 * </ul>
 * Também guarda, por companheira: experiências (tentativas, resultado, motivo, duração), estatísticas por atividade e
 * preferências da dona. Tudo limitado em tamanho. Usada só na thread do servidor.
 * <p>
 * Escolha de formato: o documento de ideias da jogadora sugeria SQLite; dentro de um mod, os dados salvos do próprio
 * mundo (NBT via codecs) são a alternativa mais próxima sem biblioteca nativa: viajam com o mundo, entram nos backups e
 * são gravados junto com o resto do salvamento.
 */
public final class WorldMemory extends SavedData {
	public static final int MAX_PER_BLOCK = 2048;
	public static final int MAX_EXPERIENCES = 256;
	public static final int MAX_MARKS = 256;
	public static final int MAX_REGIONS = 256;
	/** Marcas de "sem acesso" valem por 10 min de jogo. */
	public static final long MARK_TTL = 12000;
	/** Observações necessárias para uma área virar hipótese utilizável. */
	public static final int REGION_MIN_OBSERVATIONS = 3;
	private static final int REGION_RADIUS = 24;

	public enum Certainty {
		FACT,
		OBSERVATION,
		HYPOTHESIS;

		public static final Codec<Certainty> CODEC = Codec.STRING.xmap(Certainty::valueOf, Certainty::name);

		public String label() {
			return switch (this) {
				case FACT -> "fato";
				case OBSERVATION -> "observação";
				case HYPOTHESIS -> "hipótese";
			};
		}
	}

	/** Área onde um recurso costuma aparecer (hipótese). */
	public record Region(Identifier block, BlockPos center, int observations, float confidence, long updatedAt) {
		public static final Codec<Region> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("block").forGetter(Region::block),
			BlockPos.CODEC.fieldOf("center").forGetter(Region::center),
			Codec.INT.fieldOf("observations").forGetter(Region::observations),
			Codec.FLOAT.fieldOf("confidence").forGetter(Region::confidence),
			Codec.LONG.fieldOf("updatedAt").forGetter(Region::updatedAt)
		).apply(i, Region::new));

		public boolean usable() {
			return observations >= REGION_MIN_OBSERVATIONS && confidence > 0;
		}
	}

	/** Lugar com significado (a base, por exemplo). */
	public record Place(String kind, ResourceKey<Level> dimension, BlockPos pos, Certainty certainty, long since) {
		public static final Codec<Place> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("kind").forGetter(Place::kind),
			ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Place::dimension),
			BlockPos.CODEC.fieldOf("pos").forGetter(Place::pos),
			Certainty.CODEC.fieldOf("certainty").forGetter(Place::certainty),
			Codec.LONG.fieldOf("since").forGetter(Place::since)
		).apply(i, Place::new));
	}

	/** Observação temporária sobre um ponto (ex.: "sem acesso"), que expira. */
	public record Mark(String kind, ResourceKey<Level> dimension, BlockPos pos, long expiresAt) {
		public static final Codec<Mark> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("kind").forGetter(Mark::kind),
			ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Mark::dimension),
			BlockPos.CODEC.fieldOf("pos").forGetter(Mark::pos),
			Codec.LONG.fieldOf("expiresAt").forGetter(Mark::expiresAt)
		).apply(i, Mark::new));
	}

	/** Uma tentativa e o resultado dela. */
	public record Experience(long time, String activity, String target, boolean success, String reason, BlockPos pos, int durationTicks) {
		public static final Codec<Experience> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.LONG.fieldOf("time").forGetter(Experience::time),
			Codec.STRING.fieldOf("activity").forGetter(Experience::activity),
			Codec.STRING.fieldOf("target").forGetter(Experience::target),
			Codec.BOOL.fieldOf("success").forGetter(Experience::success),
			Codec.STRING.optionalFieldOf("reason", "").forGetter(Experience::reason),
			BlockPos.CODEC.fieldOf("pos").forGetter(Experience::pos),
			Codec.INT.optionalFieldOf("duration", 0).forGetter(Experience::durationTicks)
		).apply(i, Experience::new));

		public String key() {
			return activity + "|" + target;
		}
	}

	/** Estatística acumulada de uma atividade (ex.: "GATHER|minecraft:raw_iron"). */
	public record Stat(String key, int attempts, int failures, long lastFailure, long lastSuccess, long totalSuccessTicks, String lastReason) {
		public static final Codec<Stat> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("key").forGetter(Stat::key),
			Codec.INT.fieldOf("attempts").forGetter(Stat::attempts),
			Codec.INT.fieldOf("failures").forGetter(Stat::failures),
			Codec.LONG.optionalFieldOf("lastFailure", -1L).forGetter(Stat::lastFailure),
			Codec.LONG.optionalFieldOf("lastSuccess", -1L).forGetter(Stat::lastSuccess),
			Codec.LONG.optionalFieldOf("totalSuccessTicks", 0L).forGetter(Stat::totalSuccessTicks),
			Codec.STRING.optionalFieldOf("lastReason", "").forGetter(Stat::lastReason)
		).apply(i, Stat::new));

		public int successes() {
			return attempts - failures;
		}

		/** Duração média das tentativas bem-sucedidas (ticks), ou -1 sem nenhuma. */
		public long averageSuccessTicks() {
			return successes() <= 0 ? -1 : totalSuccessTicks / successes();
		}
	}

	/** Memória pessoal de uma companheira. */
	public static final class Mind {
		final UUID id;
		final List<Place> places = new ArrayList<>();
		final ArrayDeque<Mark> marks = new ArrayDeque<>();
		final ArrayDeque<Experience> experiences = new ArrayDeque<>();
		final Map<String, Stat> stats = new LinkedHashMap<>();
		final Map<String, String> preferences = new LinkedHashMap<>();

		Mind(UUID id) {
			this.id = id;
		}

		public List<Place> places() {
			return List.copyOf(places);
		}

		public List<Experience> experiences() {
			return List.copyOf(experiences);
		}

		public Map<String, Stat> stats() {
			return Map.copyOf(stats);
		}

		public Map<String, String> preferences() {
			return Map.copyOf(preferences);
		}

		public @Nullable Place place(String kind) {
			for (Place p : places) {
				if (p.kind().equals(kind)) {
					return p;
				}
			}
			return null;
		}
	}

	private record MindData(UUID id, List<Place> places, List<Mark> marks, List<Experience> experiences, List<Stat> stats, Map<String, String> preferences) {
		static final Codec<MindData> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(MindData::id),
			Place.CODEC.listOf().optionalFieldOf("places", List.of()).forGetter(MindData::places),
			Mark.CODEC.listOf().optionalFieldOf("marks", List.of()).forGetter(MindData::marks),
			Experience.CODEC.listOf().optionalFieldOf("experiences", List.of()).forGetter(MindData::experiences),
			Stat.CODEC.listOf().optionalFieldOf("stats", List.of()).forGetter(MindData::stats),
			Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("preferences", Map.of()).forGetter(MindData::preferences)
		).apply(i, MindData::new));
	}

	private record DimensionData(ResourceKey<Level> dimension, Map<Identifier, List<Long>> sightings, List<Region> regions) {
		static final Codec<DimensionData> CODEC = RecordCodecBuilder.create(i -> i.group(
			ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(DimensionData::dimension),
			Codec.unboundedMap(Identifier.CODEC, Codec.LONG.listOf()).optionalFieldOf("sightings", Map.of()).forGetter(DimensionData::sightings),
			Region.CODEC.listOf().optionalFieldOf("regions", List.of()).forGetter(DimensionData::regions)
		).apply(i, DimensionData::new));
	}

	public static final Codec<WorldMemory> CODEC = RecordCodecBuilder.create(i -> i.group(
		DimensionData.CODEC.listOf().optionalFieldOf("dimensions", List.of()).forGetter(WorldMemory::dimensionData),
		MindData.CODEC.listOf().optionalFieldOf("minds", List.of()).forGetter(WorldMemory::mindData)
	).apply(i, WorldMemory::new));

	public static final SavedDataType<WorldMemory> TYPE = new SavedDataType<>(AutonomousCompanions.id("memory"), WorldMemory::new, CODEC, null);

	/** Recursos vistos pela equipe (por dimensão e bloco): observações, sempre conferidas antes do uso. */
	private final Map<ResourceKey<Level>, Map<Identifier, LongLinkedOpenHashSet>> sightings = new HashMap<>();
	private final Map<ResourceKey<Level>, List<Region>> regions = new HashMap<>();
	private final Map<UUID, Mind> minds = new LinkedHashMap<>();

	public WorldMemory() {
	}

	private WorldMemory(List<DimensionData> dims, List<MindData> mindList) {
		for (DimensionData d : dims) {
			Map<Identifier, LongLinkedOpenHashSet> byBlock = new HashMap<>();
			d.sightings().forEach((block, list) -> byBlock.put(block, new LongLinkedOpenHashSet(list)));
			sightings.put(d.dimension(), byBlock);
			regions.put(d.dimension(), new ArrayList<>(d.regions()));
		}
		for (MindData m : mindList) {
			Mind mind = new Mind(m.id());
			mind.places.addAll(m.places());
			mind.marks.addAll(m.marks());
			mind.experiences.addAll(m.experiences());
			m.stats().forEach(s -> mind.stats.put(s.key(), s));
			mind.preferences.putAll(m.preferences());
			minds.put(m.id(), mind);
		}
	}

	private List<DimensionData> dimensionData() {
		List<DimensionData> out = new ArrayList<>();
		Set<ResourceKey<Level>> keys = new java.util.LinkedHashSet<>(sightings.keySet());
		keys.addAll(regions.keySet());
		for (ResourceKey<Level> dim : keys) {
			Map<Identifier, List<Long>> s = new LinkedHashMap<>();
			sightings.getOrDefault(dim, Map.of()).forEach((block, set) -> s.put(block, List.copyOf(set)));
			out.add(new DimensionData(dim, s, List.copyOf(regions.getOrDefault(dim, List.of()))));
		}
		return out;
	}

	private List<MindData> mindData() {
		List<MindData> out = new ArrayList<>();
		for (Mind m : minds.values()) {
			out.add(new MindData(m.id, List.copyOf(m.places), List.copyOf(m.marks), List.copyOf(m.experiences), List.copyOf(m.stats.values()),
				Map.copyOf(m.preferences)));
		}
		return out;
	}

	public static WorldMemory get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	// ------------------------------------------------------------------ recursos vistos (equipe)

	public void record(ResourceKey<Level> dim, BlockPos pos, Block block) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(block);
		LongLinkedOpenHashSet set = sightings.computeIfAbsent(dim, k -> new HashMap<>()).computeIfAbsent(id, k -> new LongLinkedOpenHashSet());
		if (set.add(pos.asLong())) {
			while (set.size() > MAX_PER_BLOCK) {
				set.removeFirstLong();
			}
			setDirty();
		}
	}

	/** Posição lembrada mais próxima que ainda contenha um bloco válido (as que não contêm mais são esquecidas). */
	public @Nullable BlockPos nearest(ServerLevel level, Predicate<BlockState> predicate, BlockPos from, double maxDistance, Set<BlockPos> excluded) {
		Map<Identifier, LongLinkedOpenHashSet> byBlock = sightings.get(level.dimension());
		if (byBlock == null) {
			return null;
		}
		BlockPos best = null;
		double bestSq = maxDistance * maxDistance;
		for (var entry : byBlock.entrySet()) {
			Block block = BuiltInRegistries.BLOCK.getValue(entry.getKey());
			if (!predicate.test(block.defaultBlockState())) {
				continue;
			}
			LongIterator it = entry.getValue().iterator();
			while (it.hasNext()) {
				BlockPos p = BlockPos.of(it.nextLong());
				double d = p.distSqr(from);
				if (d >= bestSq || excluded.contains(p) || !level.hasChunkAt(p)) {
					continue;
				}
				if (!predicate.test(level.getBlockState(p))) {
					it.remove(); // não é mais verdade: esquece
					setDirty();
					continue;
				}
				best = p;
				bestSq = d;
			}
		}
		return best;
	}

	/** Quantas posições de um bloco ela lembra (nesta dimensão), sem conferir. */
	public int sightingCount(ResourceKey<Level> dim, Block block) {
		var set = sightings.getOrDefault(dim, Map.of()).get(BuiltInRegistries.BLOCK.getKey(block));
		return set == null ? 0 : set.size();
	}

	public Map<Identifier, Integer> sightingSummary(ResourceKey<Level> dim) {
		Map<Identifier, Integer> out = new LinkedHashMap<>();
		sightings.getOrDefault(dim, Map.of()).forEach((id, set) -> {
			if (!set.isEmpty()) {
				out.put(id, set.size());
			}
		});
		return out;
	}

	/**
	 * Ela minerou este bloco aqui: a posição deixa de existir (é esquecida) e a área ganha uma confirmação. Três ou mais
	 * confirmações viram hipótese ("costuma ter isso aqui").
	 */
	public void mined(ServerLevel level, Block block, BlockPos pos) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(block);
		var set = sightings.getOrDefault(level.dimension(), Map.of()).get(id);
		if (set != null) {
			set.remove(pos.asLong());
		}
		List<Region> list = regions.computeIfAbsent(level.dimension(), k -> new ArrayList<>());
		long now = level.getGameTime();
		for (int i = 0; i < list.size(); i++) {
			Region r = list.get(i);
			if (r.block().equals(id) && r.center().distSqr(pos) <= REGION_RADIUS * REGION_RADIUS) {
				int n = r.observations() + 1;
				BlockPos center = new BlockPos((r.center().getX() * r.observations() + pos.getX()) / n, (r.center().getY() * r.observations() + pos.getY()) / n,
					(r.center().getZ() * r.observations() + pos.getZ()) / n);
				list.set(i, new Region(id, center, n, Math.min(1.0f, r.confidence() + 0.15f), now));
				setDirty();
				return;
			}
		}
		list.add(new Region(id, pos.immutable(), 1, 0.2f, now));
		while (list.size() > MAX_REGIONS) {
			list.stream().min(Comparator.comparingLong(Region::updatedAt)).ifPresent(list::remove);
		}
		setDirty();
	}

	/** A hipótese mais próxima (já com observações suficientes) de um bloco que atenda ao predicado. */
	public @Nullable Region bestRegion(ResourceKey<Level> dim, Predicate<BlockState> predicate, BlockPos from, double maxDistance) {
		Region best = null;
		double bestSq = maxDistance * maxDistance;
		for (Region r : regions.getOrDefault(dim, List.of())) {
			if (!r.usable() || !predicate.test(BuiltInRegistries.BLOCK.getValue(r.block()).defaultBlockState())) {
				continue;
			}
			double d = r.center().distSqr(from);
			if (d < bestSq) {
				best = r;
				bestSq = d;
			}
		}
		return best;
	}

	/** Ela foi até a área e não achou nada: a hipótese perde confiança (e some se chegar a zero). */
	public void refute(ResourceKey<Level> dim, Region region, long now) {
		List<Region> list = regions.getOrDefault(dim, new ArrayList<>());
		for (int i = 0; i < list.size(); i++) {
			Region r = list.get(i);
			if (r.block().equals(region.block()) && r.center().equals(region.center())) {
				float c = r.confidence() - 0.3f;
				if (c <= 0) {
					list.remove(i);
				} else {
					list.set(i, new Region(r.block(), r.center(), r.observations(), c, now));
				}
				setDirty();
				return;
			}
		}
	}

	public List<Region> regions(ResourceKey<Level> dim) {
		return List.copyOf(regions.getOrDefault(dim, List.of()));
	}

	// ------------------------------------------------------------------ memória pessoal

	public Mind mind(UUID id) {
		return minds.computeIfAbsent(id, Mind::new);
	}

	public void setPlace(UUID id, String kind, ResourceKey<Level> dim, BlockPos pos, Certainty certainty, long now) {
		Mind m = mind(id);
		m.places.removeIf(p -> p.kind().equals(kind));
		m.places.add(new Place(kind, dim, pos.immutable(), certainty, now));
		setDirty();
	}

	public Optional<Place> base(UUID id) {
		return Optional.ofNullable(mind(id).place("base"));
	}

	/** Ponto sem acesso: observação temporária (vale 10 min; depois ela tenta de novo). */
	public void markUnreachable(UUID id, ResourceKey<Level> dim, BlockPos pos, long now) {
		Mind m = mind(id);
		m.marks.removeIf(x -> x.expiresAt() <= now || x.pos().equals(pos) && x.dimension().equals(dim));
		m.marks.addLast(new Mark("inalcancavel", dim, pos.immutable(), now + MARK_TTL));
		while (m.marks.size() > MAX_MARKS) {
			m.marks.removeFirst();
		}
		setDirty();
	}

	/** Pontos sem acesso ainda válidos nesta dimensão, a até {@code radius} blocos. */
	public List<BlockPos> unreachableNear(UUID id, ResourceKey<Level> dim, BlockPos center, double radius, long now) {
		List<BlockPos> out = new ArrayList<>();
		for (Mark x : mind(id).marks) {
			if (x.kind().equals("inalcancavel") && x.dimension().equals(dim) && x.expiresAt() > now && x.pos().distSqr(center) <= radius * radius) {
				out.add(x.pos());
			}
		}
		return out;
	}

	/** Registra uma tentativa (e atualiza a estatística da atividade). */
	public void recordExperience(UUID id, Experience e) {
		Mind m = mind(id);
		m.experiences.addLast(e);
		while (m.experiences.size() > MAX_EXPERIENCES) {
			m.experiences.removeFirst();
		}
		Stat s = m.stats.get(e.key());
		if (s == null) {
			s = new Stat(e.key(), 0, 0, -1, -1, 0, "");
		}
		m.stats.put(e.key(), new Stat(e.key(), s.attempts() + 1, s.failures() + (e.success() ? 0 : 1), e.success() ? s.lastFailure() : e.time(),
			e.success() ? e.time() : s.lastSuccess(), s.totalSuccessTicks() + (e.success() ? e.durationTicks() : 0), e.success() ? s.lastReason() : e.reason()));
		setDirty();
	}

	public @Nullable Stat stat(UUID id, String key) {
		return mind(id).stats.get(key);
	}

	public void setPreference(UUID id, String key, String value) {
		mind(id).preferences.put(key, value);
		setDirty();
	}

	/** Esquece o que a equipe sabe de recursos (posições vistas e hipóteses de área): comando de administrador e início de cada teste. */
	public void clearTeamKnowledge() {
		sightings.clear();
		regions.clear();
		setDirty();
	}

	/** Esquece a memória pessoal de uma companheira (ex.: comando de depuração). */
	public void forget(UUID id) {
		if (minds.remove(id) != null) {
			setDirty();
		}
	}
}
