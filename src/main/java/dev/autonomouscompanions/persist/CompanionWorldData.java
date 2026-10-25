package dev.autonomouscompanions.persist;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.build.BuildProject;
import dev.autonomouscompanions.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Dados persistentes do mundo (arquivo {@code data/autonomouscompanions/world.dat}): quem já recebeu as companheiras
 * iniciais (impede novo surgimento ao reentrar, morrer ou trocar de dimensão), registro de cada companheira (dono,
 * nome, última posição, mortes e instantâneo para ressurreição) e os projetos de construção aprovados.
 */
public final class CompanionWorldData extends SavedData {
	public record CompanionRecord(UUID id, Optional<UUID> owner, String name, int variant, ResourceKey<Level> dimension, BlockPos lastPos, boolean dead,
		long deathTime, Optional<CompoundTag> snapshot, int deaths, Optional<BlockPos> deathPos) {
		public static final Codec<CompanionRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(CompanionRecord::id),
			UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(CompanionRecord::owner),
			Codec.STRING.optionalFieldOf("name", "").forGetter(CompanionRecord::name),
			Codec.INT.optionalFieldOf("variant", 0).forGetter(CompanionRecord::variant),
			ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(CompanionRecord::dimension),
			BlockPos.CODEC.fieldOf("lastPos").forGetter(CompanionRecord::lastPos),
			Codec.BOOL.optionalFieldOf("dead", false).forGetter(CompanionRecord::dead),
			Codec.LONG.optionalFieldOf("deathTime", 0L).forGetter(CompanionRecord::deathTime),
			CompoundTag.CODEC.optionalFieldOf("snapshot").forGetter(CompanionRecord::snapshot),
			Codec.INT.optionalFieldOf("deaths", 0).forGetter(CompanionRecord::deaths),
			BlockPos.CODEC.optionalFieldOf("deathPos").forGetter(CompanionRecord::deathPos)
		).apply(i, CompanionRecord::new));

		public CompanionRecord withAlive(String name, Optional<UUID> owner, int variant, ResourceKey<Level> dim, BlockPos pos) {
			return new CompanionRecord(id, owner, name, variant, dim, pos, false, deathTime, Optional.empty(), deaths, deathPos);
		}
	}

	public static final Codec<CompanionWorldData> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.listOf().optionalFieldOf("starterPlayers", List.of()).forGetter(d -> List.copyOf(d.starterPlayers)),
		Codec.BOOL.optionalFieldOf("firstStarterGiven", false).forGetter(d -> d.firstStarterGiven),
		CompanionRecord.CODEC.listOf().optionalFieldOf("companions", List.of()).forGetter(d -> List.copyOf(d.companions.values())),
		BuildProject.CODEC.listOf().optionalFieldOf("projects", List.of()).forGetter(d -> List.copyOf(d.projects.values())),
		UUIDUtil.CODEC.listOf().optionalFieldOf("whistlePlayers", List.of()).forGetter(d -> List.copyOf(d.whistlePlayers)),
		UUIDUtil.CODEC.listOf().optionalFieldOf("helperPlayers", List.of()).forGetter(d -> List.copyOf(d.helperPlayers))
	).apply(i, CompanionWorldData::new));

	public static final SavedDataType<CompanionWorldData> TYPE = new SavedDataType<>(AutonomousCompanions.id("world"), CompanionWorldData::new, CODEC, null);

	private final Set<UUID> starterPlayers = new HashSet<>();
	private final Set<UUID> whistlePlayers = new HashSet<>();
	private final Set<UUID> helperPlayers = new HashSet<>();
	private boolean firstStarterGiven;
	private final Map<UUID, CompanionRecord> companions = new LinkedHashMap<>();
	private final Map<UUID, BuildProject> projects = new LinkedHashMap<>();

	public CompanionWorldData() {
	}

	private CompanionWorldData(List<UUID> starters, boolean firstGiven, List<CompanionRecord> records, List<BuildProject> projects, List<UUID> whistles,
		List<UUID> helpers) {
		this.whistlePlayers.addAll(whistles);
		this.helperPlayers.addAll(helpers);
		this.starterPlayers.addAll(starters);
		this.firstStarterGiven = firstGiven;
		for (CompanionRecord r : records) {
			this.companions.put(r.id(), r);
		}
		for (BuildProject p : projects) {
			this.projects.put(p.id(), p);
		}
	}

	public static CompanionWorldData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	// ------------------------------------------------------------------ companheiras iniciais

	public boolean hasReceivedStarters(UUID player) {
		return starterPlayers.contains(player);
	}

	public boolean firstStarterGiven() {
		return firstStarterGiven;
	}

	public void markStartersGiven(UUID player) {
		starterPlayers.add(player);
		firstStarterGiven = true;
		setDirty();
	}

	/** A ajudante é criada uma única vez por jogadora (junto com as iniciais, ou depois em mundos antigos). */
	public boolean hasHelper(UUID player) {
		return helperPlayers.contains(player);
	}

	public void markHelperGiven(UUID player) {
		if (helperPlayers.add(player)) {
			setDirty();
		}
	}

	/** O apito é entregue uma única vez por jogadora (depois, só por receita ou comando). */
	public boolean markWhistleGiven(UUID player) {
		if (whistlePlayers.add(player)) {
			setDirty();
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ registro

	public Collection<CompanionRecord> records() {
		return companions.values();
	}

	public @Nullable CompanionRecord record(UUID id) {
		return companions.get(id);
	}

	public void updateCompanion(CompanionEntity c) {
		CompanionRecord old = companions.get(c.getUUID());
		Optional<UUID> owner = Optional.ofNullable(c.ownerUUID());
		BlockPos pos = c.blockPosition();
		var dim = c.level().dimension();
		if (old != null && !old.dead() && old.name().equals(c.companionName()) && old.owner().equals(owner) && old.dimension() == dim
			&& old.lastPos().distManhattan(pos) < 4) {
			return;
		}
		CompanionRecord base = old != null ? old : new CompanionRecord(c.getUUID(), owner, c.companionName(), c.variant().ordinal(), dim, pos, false, 0,
			Optional.empty(), 0, Optional.empty());
		companions.put(c.getUUID(), base.withAlive(c.companionName(), owner, c.variant().ordinal(), dim, pos));
		setDirty();
	}

	public void recordDeath(CompanionEntity c, CompoundTag snapshot, long time) {
		CompanionRecord old = companions.get(c.getUUID());
		int deaths = old != null ? old.deaths() + 1 : 1;
		companions.put(c.getUUID(), new CompanionRecord(c.getUUID(), Optional.ofNullable(c.ownerUUID()), c.companionName(), c.variant().ordinal(),
			c.level().dimension(), c.blockPosition(), true, time, Optional.of(snapshot), deaths, Optional.of(c.blockPosition())));
		setDirty();
	}

	public void markRevived(UUID id, ResourceKey<Level> dim, BlockPos pos) {
		CompanionRecord old = companions.get(id);
		if (old != null) {
			companions.put(id, old.withAlive(old.name(), old.owner(), old.variant(), dim, pos));
			setDirty();
		}
	}

	public List<CompanionRecord> deadRecords() {
		List<CompanionRecord> list = new ArrayList<>();
		for (CompanionRecord r : companions.values()) {
			if (r.dead()) {
				list.add(r);
			}
		}
		return list;
	}

	// ------------------------------------------------------------------ projetos

	public Collection<BuildProject> projects() {
		return projects.values();
	}

	public @Nullable BuildProject project(UUID id) {
		return projects.get(id);
	}

	public void putProject(BuildProject project) {
		projects.put(project.id(), project);
		setDirty();
	}

	public void removeProject(UUID id) {
		projects.remove(id);
		setDirty();
	}
}
