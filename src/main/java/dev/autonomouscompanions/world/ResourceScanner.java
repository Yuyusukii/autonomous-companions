package dev.autonomouscompanions.world;

import dev.autonomouscompanions.config.ConfigManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Busca incremental de blocos. Percorre chunks em espiral a partir de um centro, mas apenas chunks <b>já carregados</b>
 * ({@code getChunkNow}); nunca força carregamento. Cada tick consome um orçamento global de seções (16³) compartilhado
 * por todas as companheiras, e seções cuja paleta não contém o alvo são descartadas sem varredura.
 */
public final class ResourceScanner {
	private static long budgetTick = -1;
	private static int budgetLeft;
	private static int chunkLookupsLeft;

	private ResourceScanner() {
	}

	/** Reserva orçamento do tick atual (global). */
	private static boolean takeSection(ServerLevel level) {
		long now = level.getServer().getTickCount();
		if (now != budgetTick) {
			budgetTick = now;
			budgetLeft = ConfigManager.get().secoesAnalisadasPorTick;
			chunkLookupsLeft = 512;
		}
		if (budgetLeft <= 0) {
			return false;
		}
		budgetLeft--;
		return true;
	}

	private static boolean takeChunkLookup(ServerLevel level) {
		long now = level.getServer().getTickCount();
		if (now != budgetTick) {
			budgetTick = now;
			budgetLeft = ConfigManager.get().secoesAnalisadasPorTick;
			chunkLookupsLeft = 512;
		}
		if (chunkLookupsLeft <= 0) {
			return false;
		}
		chunkLookupsLeft--;
		return true;
	}

	/** Uma busca em andamento. Não é persistida: ao recarregar o mundo uma nova busca é iniciada. */
	public static final class Job {
		private final Predicate<BlockState> predicate;
		private final BlockPos center;
		private final int maxRing;
		private final int minY;
		private final int maxY;
		private final boolean exposedOnly;
		private final Set<BlockPos> excluded;
		private final int wanted;
		private final List<BlockPos> found = new ArrayList<>();
		private int ring;
		private int ringIndex;
		private List<ChunkPos> ringChunks;
		private int sectionCursor = -1;
		private LevelChunk currentChunk;
		private boolean complete;
		private int loadedChunksScanned;
		private int unloadedChunks;
		private int sectionsScanned;
		private int firstFoundRing = -1;

		public Job(Predicate<BlockState> predicate, BlockPos center, int maxRadius, int minY, int maxY, boolean exposedOnly, Set<BlockPos> excluded,
			int wanted) {
			this.predicate = predicate;
			this.center = center.immutable();
			this.maxRing = Math.max(0, (maxRadius + 15) >> 4);
			this.minY = minY;
			this.maxY = maxY;
			this.exposedOnly = exposedOnly;
			this.excluded = excluded;
			this.wanted = Math.max(1, wanted);
			this.ringChunks = ringChunks(SectionPos.blockToSectionCoord(center.getX()), SectionPos.blockToSectionCoord(center.getZ()), 0);
		}

		public boolean isComplete() {
			return complete;
		}

		public BlockPos center() {
			return center;
		}

		public int unloadedChunks() {
			return unloadedChunks;
		}

		public int scannedChunks() {
			return loadedChunksScanned;
		}

		/** Seções de chunk analisadas por esta busca (o orçamento global limita quantas por tick). */
		public int sectionsScanned() {
			return sectionsScanned;
		}

		public int currentRing() {
			return ring;
		}

		/** Resultados ordenados pela distância ao centro (o vertical pesa mais: subir/descer custa mais). */
		public List<BlockPos> results() {
			List<BlockPos> copy = new ArrayList<>(found);
			copy.sort(Comparator.comparingDouble(p -> {
				double dx = p.getX() - center.getX();
				double dy = (p.getY() - center.getY()) * 1.6;
				double dz = p.getZ() - center.getZ();
				return dx * dx + dy * dy + dz * dz;
			}));
			return copy;
		}

		/** Avança a busca usando o orçamento global disponível neste tick. */
		public void step(ServerLevel level) {
			if (complete) {
				return;
			}
			while (!complete) {
				if (currentChunk == null) {
					if (!nextChunk(level)) {
						return; // sem orçamento neste tick ou busca concluída
					}
					continue;
				}
				if (!takeSection(level)) {
					return;
				}
				scanSection(level);
			}
		}

		private boolean nextChunk(ServerLevel level) {
			while (true) {
				if (ringIndex >= ringChunks.size()) {
					// Terminou um anel: conclui se já achou o suficiente ou chegou ao limite.
					if (firstFoundRing >= 0 && (found.size() >= wanted || ring >= firstFoundRing + 1)) {
						complete = true;
						return false;
					}
					ring++;
					if (ring > maxRing) {
						complete = true;
						return false;
					}
					ringChunks = ringChunks(SectionPos.blockToSectionCoord(center.getX()), SectionPos.blockToSectionCoord(center.getZ()), ring);
					ringIndex = 0;
				}
				if (!takeChunkLookup(level)) {
					return false;
				}
				ChunkPos cp = ringChunks.get(ringIndex++);
				LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x(), cp.z());
				if (chunk == null) {
					unloadedChunks++;
					continue;
				}
				loadedChunksScanned++;
				currentChunk = chunk;
				sectionCursor = level.getSectionIndex(Math.max(level.getMinY(), minY));
				return true;
			}
		}

		private void scanSection(ServerLevel level) {
			LevelChunk chunk = currentChunk;
			int lastIndex = level.getSectionIndex(Math.min(level.getMaxY() - 1, maxY));
			if (sectionCursor > lastIndex || sectionCursor >= chunk.getSectionsCount()) {
				currentChunk = null;
				return;
			}
			LevelChunkSection section = chunk.getSection(sectionCursor);
			sectionsScanned++;
			int sectionY = level.getSectionYFromSectionIndex(sectionCursor);
			sectionCursor++;
			if (section.hasOnlyAir() || !section.getStates().maybeHas(predicate)) {
				return;
			}
			int baseX = chunk.getPos().getMinBlockX();
			int baseY = SectionPos.sectionToBlockCoord(sectionY);
			int baseZ = chunk.getPos().getMinBlockZ();
			BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
			for (int y = 0; y < 16; y++) {
				int wy = baseY + y;
				if (wy < minY || wy > maxY) {
					continue;
				}
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (!predicate.test(state)) {
							continue;
						}
						mutable.set(baseX + x, wy, baseZ + z);
						if (excluded.contains(mutable)) {
							continue;
						}
						if (exposedOnly && !isExposed(level, mutable)) {
							continue;
						}
						found.add(mutable.immutable());
						if (firstFoundRing < 0) {
							firstFoundRing = ring;
						}
						if (found.size() > 256) {
							return;
						}
					}
				}
			}
		}
	}

	public static boolean isExposed(ServerLevel level, BlockPos pos) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.values()) {
			m.setWithOffset(pos, d);
			BlockState s = level.getBlockState(m);
			if (s.isAir() || s.getCollisionShape(level, m).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	static List<ChunkPos> ringChunks(int cx, int cz, int r) {
		List<ChunkPos> list = new ArrayList<>(Math.max(1, r * 8));
		if (r == 0) {
			list.add(new ChunkPos(cx, cz));
			return list;
		}
		for (int dx = -r; dx <= r; dx++) {
			list.add(new ChunkPos(cx + dx, cz - r));
			list.add(new ChunkPos(cx + dx, cz + r));
		}
		for (int dz = -r + 1; dz <= r - 1; dz++) {
			list.add(new ChunkPos(cx - r, cz + dz));
			list.add(new ChunkPos(cx + r, cz + dz));
		}
		return list;
	}
}
