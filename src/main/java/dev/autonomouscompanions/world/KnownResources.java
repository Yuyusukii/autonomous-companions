package dev.autonomouscompanions.world;

import dev.autonomouscompanions.mind.memory.WorldMemory;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Memória compartilhada da equipe de recursos vistos durante a exploração (minérios e troncos). Permite cooperação: a
 * exploradora registra, as demais aproveitam. As posições são sempre conferidas antes do uso e esquecidas quando deixam
 * de ser verdade; a memória é limitada.
 * <p>
 * Desde 18/10/2026 ela é persistente: os dados ficam na {@link WorldMemory} (salva com o mundo). Antes ficavam só na RAM
 * e eram perdidos a cada vez que o jogo fechava.
 */
public final class KnownResources {
	private KnownResources() {
	}

	public static boolean isInteresting(BlockState state) {
		return state.is(ConventionalBlockTags.ORES) || state.is(BlockTags.ORES) || state.is(BlockTags.LOGS);
	}

	public static void record(Level level, BlockPos pos, Block block) {
		if (level instanceof ServerLevel server) {
			WorldMemory.get(server.getServer()).record(level.dimension(), pos, block);
		}
	}

	/** Registra blocos interessantes visíveis ao redor (exposição ao ar), com raio pequeno e custo limitado. */
	public static void observeAround(ServerLevel level, BlockPos center, int radius) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (!level.hasChunkAt(m)) {
						continue;
					}
					BlockState s = level.getBlockState(m);
					if (isInteresting(s) && ResourceScanner.isExposed(level, m)) {
						record(level, m, s.getBlock());
					}
				}
			}
		}
	}

	/** Posição conhecida mais próxima que ainda contenha um bloco válido. */
	public static @Nullable BlockPos nearest(ServerLevel level, Predicate<BlockState> predicate, BlockPos from, double maxDistance,
		java.util.Set<BlockPos> excluded) {
		return WorldMemory.get(level.getServer()).nearest(level, predicate, from, maxDistance, excluded);
	}
}
