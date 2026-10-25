package dev.autonomouscompanions.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * O que existe de verdade na superfície ao redor (raio 32): uma olhada rápida, como a jogadora faz antes de decidir de
 * onde tirar madeira. Antes o planejador escolhia pelo "bioma típico" (ex.: carvalho em floresta) e, numa floresta de
 * bétulas, saía procurando carvalho pelo mapa sem ver as bétulas ao lado.
 * <p>
 * Lê colunas de 2 em 2 blocos (só chunks carregados), do topo até 14 blocos abaixo (copas e troncos). O resultado fica
 * guardado por companheira por 10 s ou até ela andar 16 blocos.
 */
public final class SurfaceSurvey {
	public static final int RADIUS = 32;
	private static final int DEPTH = 14;

	private record Entry(long time, BlockPos center, Set<Block> blocks) {
	}

	private static final Map<UUID, Entry> CACHE = new HashMap<>();

	private SurfaceSurvey() {
	}

	public static synchronized Set<Block> around(ServerLevel level, Entity who) {
		long now = level.getGameTime();
		Entry e = CACHE.get(who.getUUID());
		BlockPos here = who.blockPosition();
		if (e != null && now - e.time() < 200 && e.center().distSqr(here) < 16 * 16) {
			return e.blocks();
		}
		Set<Block> found = scan(level, here);
		CACHE.put(who.getUUID(), new Entry(now, here, found));
		if (CACHE.size() > 256) {
			CACHE.entrySet().removeIf(x -> now - x.getValue().time() > 1200);
		}
		return found;
	}

	static Set<Block> scan(ServerLevel level, BlockPos center) {
		Set<Block> found = new HashSet<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -RADIUS; dx <= RADIUS; dx += 2) {
			for (int dz = -RADIUS; dz <= RADIUS; dz += 2) {
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				if (!level.hasChunkAt(m.set(x, center.getY(), z))) {
					continue;
				}
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				// Teto de barreira, construções altas...: começa no máximo 24 acima de quem olha.
				top = Math.min(top, center.getY() + 24);
				for (int y = top; y >= top - DEPTH && y > level.getMinY(); y--) {
					BlockState s = level.getBlockState(m.set(x, y, z));
					if (!s.isAir()) {
						found.add(s.getBlock());
					}
				}
			}
		}
		return found;
	}

	public static synchronized void clear() {
		CACHE.clear();
	}
}
