package dev.autonomouscompanions.yuno;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.autonomouscompanions.AutonomousCompanions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Dados persistentes da Yuno por jogadora: qual é a Yuno "verdadeira" (as cópias antigas que reaparecerem em chunks
 * recarregados se desfazem), os baús que a jogadora usa, o próximo presente e os presentes ainda não descobertos.
 */
public final class YunoData extends SavedData {
	/** No máximo este número de baús lembrados por jogadora (os mais recentes). */
	public static final int MAX_CHESTS = 32;
	/** Presentes acumulados enquanto não há como entregar (ex.: a jogadora ficou perto dos baús por dias). */
	public static final int MAX_OWED = 3;

	public static final class Entry {
		@Nullable UUID yunoId;
		long nextGift;
		int owed;
		int given;
		final List<GlobalPos> chests = new ArrayList<>();
		final List<GlobalPos> surprises = new ArrayList<>();

		public @Nullable UUID yunoId() {
			return yunoId;
		}

		public long nextGift() {
			return nextGift;
		}

		public int owed() {
			return owed;
		}

		public int given() {
			return given;
		}

		public List<GlobalPos> chests() {
			return chests;
		}

		public List<GlobalPos> surprises() {
			return surprises;
		}

		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.optionalFieldOf("yuno").forGetter(e -> Optional.ofNullable(e.yunoId)),
			Codec.LONG.optionalFieldOf("nextGift", 0L).forGetter(e -> e.nextGift),
			Codec.INT.optionalFieldOf("owed", 0).forGetter(e -> e.owed),
			Codec.INT.optionalFieldOf("given", 0).forGetter(e -> e.given),
			GlobalPos.CODEC.listOf().optionalFieldOf("chests", List.of()).forGetter(e -> List.copyOf(e.chests)),
			GlobalPos.CODEC.listOf().optionalFieldOf("surprises", List.of()).forGetter(e -> List.copyOf(e.surprises))
		).apply(i, (id, next, owed, given, chests, surprises) -> {
			Entry e = new Entry();
			e.yunoId = id.orElse(null);
			e.nextGift = next;
			e.owed = owed;
			e.given = given;
			e.chests.addAll(chests);
			e.surprises.addAll(surprises);
			return e;
		}));
	}

	private final Map<UUID, Entry> players = new HashMap<>();

	public static final Codec<YunoData> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC).xmap(m -> {
		YunoData d = new YunoData();
		d.players.putAll(m);
		return d;
	}, d -> Map.copyOf(d.players));

	public static final SavedDataType<YunoData> TYPE = new SavedDataType<>(AutonomousCompanions.id("yuno"), YunoData::new, CODEC, null);

	public YunoData() {
	}

	public static YunoData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public @Nullable Entry entry(UUID player) {
		return players.get(player);
	}

	public Entry getOrCreate(UUID player) {
		return players.computeIfAbsent(player, k -> {
			setDirty();
			return new Entry();
		});
	}

	public Map<UUID, Entry> all() {
		return players;
	}

	public void setYuno(UUID player, UUID yuno) {
		getOrCreate(player).yunoId = yuno;
		setDirty();
	}

	public void setNextGift(UUID player, long time) {
		getOrCreate(player).nextGift = time;
		setDirty();
	}

	public void setOwed(UUID player, int owed) {
		getOrCreate(player).owed = Math.max(0, Math.min(MAX_OWED, owed));
		setDirty();
	}

	/** Lembra um baú usado pela jogadora (o mais recente vai para o fim). */
	public void rememberChest(UUID player, GlobalPos chest) {
		Entry e = getOrCreate(player);
		e.chests.remove(chest);
		e.chests.add(chest);
		while (e.chests.size() > MAX_CHESTS) {
			e.chests.removeFirst();
		}
		setDirty();
	}

	public void forgetChest(UUID player, GlobalPos chest) {
		Entry e = getOrCreate(player);
		if (e.chests.remove(chest)) {
			setDirty();
		}
	}

	public void giftDelivered(UUID player, GlobalPos chest) {
		Entry e = getOrCreate(player);
		e.owed = Math.max(0, e.owed - 1);
		e.given++;
		if (!e.surprises.contains(chest)) {
			e.surprises.add(chest);
		}
		setDirty();
	}

	public boolean takeSurprise(UUID player, GlobalPos chest) {
		Entry e = entry(player);
		if (e != null && e.surprises.remove(chest)) {
			setDirty();
			return true;
		}
		return false;
	}
}
