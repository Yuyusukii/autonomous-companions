package dev.autonomouscompanions.nav;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Verificações de segurança de terreno: onde é seguro ficar em pé, surgir ou descansar. */
public final class SafeSpot {
	private SafeSpot() {
	}

	public static boolean isDangerousBlock(BlockState state) {
		return state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK) || state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)
			|| state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.WITHER_ROSE)
			|| state.is(Blocks.POINTED_DRIPSTONE) || state.getFluidState().is(FluidTags.LAVA);
	}

	/** Posição (pés) onde uma entidade humanoide pode ficar sem sufocar, cair, se queimar ou se afogar. */
	public static boolean isSafeStand(Level level, BlockPos feet, @Nullable EntityType<?> type) {
		if (!level.hasChunkAt(feet) || feet.getY() <= level.getMinY() + 1 || feet.getY() >= level.getMaxY() - 2) {
			return false;
		}
		BlockPos below = feet.below();
		BlockState ground = level.getBlockState(below);
		if (!ground.isFaceSturdy(level, below, Direction.UP) || isDangerousBlock(ground) || ground.is(BlockTags.LEAVES)) {
			return false;
		}
		for (int dy = 0; dy <= 1; dy++) {
			BlockPos p = feet.above(dy);
			BlockState s = level.getBlockState(p);
			if (!s.getCollisionShape(level, p).isEmpty() || !s.getFluidState().isEmpty() || isDangerousBlock(s)) {
				return false;
			}
		}
		// Sem lava ou fogo imediatamente ao redor.
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockState side = level.getBlockState(feet.relative(d));
			BlockState sideBelow = level.getBlockState(below.relative(d));
			if (isDangerousBlock(side) || sideBelow.getFluidState().is(FluidTags.LAVA)) {
				return false;
			}
		}
		if (type != null) {
			AABB box = type.getDimensions().makeBoundingBox(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
			return level.noCollision(box);
		}
		return true;
	}

	/** Procura a posição segura mais próxima em anéis ao redor do centro, variando a altura em ±{@code vertical}. */
	public static @Nullable BlockPos findNear(Level level, BlockPos center, int minRadius, int maxRadius, int vertical, @Nullable EntityType<?> type) {
		for (int r = minRadius; r <= maxRadius; r++) {
			List<BlockPos> ring = ring(center, r);
			for (BlockPos column : ring) {
				for (int dy = 0; dy <= vertical; dy++) {
					for (int sign = 1; sign >= -1; sign -= 2) {
						BlockPos p = column.above(dy * sign);
						if (isSafeStand(level, p, type)) {
							return p;
						}
						if (dy == 0) {
							break;
						}
					}
				}
			}
		}
		return null;
	}

	public static List<BlockPos> ring(BlockPos center, int r) {
		List<BlockPos> out = new ArrayList<>();
		if (r == 0) {
			out.add(center);
			return out;
		}
		for (int dx = -r; dx <= r; dx++) {
			out.add(center.offset(dx, 0, -r));
			out.add(center.offset(dx, 0, r));
		}
		for (int dz = -r + 1; dz <= r - 1; dz++) {
			out.add(center.offset(-r, 0, dz));
			out.add(center.offset(r, 0, dz));
		}
		return out;
	}

	public static double horizontalDistance(BlockPos a, double x, double z) {
		double dx = a.getX() + 0.5 - x;
		double dz = a.getZ() + 0.5 - z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Destino de exploração em terra firme: tenta 8 direções a partir de {@code angle}, a {@code distance} blocos de
	 * {@code center}, e devolve a superfície da primeira coluna carregada com chão firme e sem água/lava. Se nenhuma
	 * coluna carregada servir, devolve a primeira coluna ainda não carregada (a checagem é feita ao chegar perto), ou nulo.
	 */
	public static @Nullable BlockPos landTarget(Level level, BlockPos center, double angle, double distance, @Nullable EntityType<?> type) {
		BlockPos unloaded = null;
		for (int i = 0; i < 8; i++) {
			double a = angle + i * (Math.PI / 4);
			int x = net.minecraft.util.Mth.floor(center.getX() + Math.cos(a) * distance);
			int z = net.minecraft.util.Mth.floor(center.getZ() + Math.sin(a) * distance);
			BlockPos column = new BlockPos(x, center.getY(), z);
			if (!level.hasChunkAt(column)) {
				if (unloaded == null) {
					unloaded = column;
				}
				continue;
			}
			// Desce a coluna a partir do topo (ou de 16 acima) até achar chão firme de verdade: nunca o topo de um teto,
			// de uma barreira ou a superfície de água.
			int top = Math.min(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), center.getY() + 16);
			for (int y = top; y >= center.getY() - 16; y--) {
				BlockPos feet = new BlockPos(x, y, z);
				if (isSafeStand(level, feet, type) && !level.getBlockState(feet.below()).is(net.minecraft.world.level.block.Blocks.BARRIER)) {
					return feet;
				}
			}
		}
		return unloaded;
	}
}
