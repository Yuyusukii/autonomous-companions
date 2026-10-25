package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.coop.Cooperation;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.SafeSpot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Utilitários de estações de trabalho (bancada, fornalha) e colocação de blocos no mundo. */
public final class Stations {
	private Stations() {
	}

	public static @Nullable BlockPos findNearby(ServerLevel level, BlockPos center, Block block, int radius, int vertical) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -vertical, -radius), center.offset(radius, vertical, radius))) {
			if (level.getBlockState(p).is(block)) {
				double d = p.distSqr(center);
				if (d < bestDist) {
					bestDist = d;
					best = p.immutable();
				}
			}
		}
		return best;
	}

	/** Fornalha utilizável: vazia ou contendo apenas itens compatíveis com o que vamos fundir. */
	public static boolean furnaceUsable(ServerLevel level, BlockPos pos, java.util.function.Predicate<net.minecraft.world.item.ItemStack> ourInput) {
		if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
			return false;
		}
		var in = furnace.getItem(0);
		var out = furnace.getItem(2);
		return (in.isEmpty() || ourInput.test(in)) && out.isEmpty();
	}

	/** Escolhe um local adjacente seguro para colocar uma estação. */
	public static @Nullable BlockPos placementSpot(ServerLevel level, CompanionEntity c) {
		BlockPos feet = c.blockPosition();
		for (int r = 1; r <= 3; r++) {
			for (BlockPos p : SafeSpot.ring(feet, r)) {
				for (int dy = 0; dy <= 1; dy++) {
					BlockPos q = p.above(dy == 0 ? 0 : -1);
					if (q.equals(feet) || q.equals(feet.above())) {
						continue;
					}
					BlockState here = level.getBlockState(q);
					if (!(here.isAir() || here.canBeReplaced()) || !here.getFluidState().isEmpty()) {
						continue;
					}
					BlockPos below = q.below();
					if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
						continue;
					}
					if (Cooperation.get(level.getServer()).isInsideProtectedArea(level, q)) {
						continue;
					}
					if (!level.getEntities(c, new net.minecraft.world.phys.AABB(q)).isEmpty()) {
						continue;
					}
					return q;
				}
			}
		}
		return null;
	}

	/** Coloca um bloco no mundo virado para a companheira, com som e animação. Não consome o item (quem chama consome). */
	public static boolean place(ServerLevel level, CompanionEntity c, BlockPos pos, BlockState state) {
		if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
			Direction toward = Direction.getApproximateNearest(c.getX() - (pos.getX() + 0.5), 0, c.getZ() - (pos.getZ() + 0.5));
			if (toward.getAxis().isHorizontal()) {
				state = state.setValue(HorizontalDirectionalBlock.FACING, toward);
			}
		}
		return placeExact(level, c, pos, state);
	}

	/** Coloca exatamente o estado pedido (usado nas construções: escadas, portas, camas etc. mantêm a orientação). */
	public static boolean placeExact(ServerLevel level, CompanionEntity c, BlockPos pos, BlockState state) {
		if (!state.canSurvive(level, pos)) {
			return false;
		}
		boolean ok = level.setBlock(pos, state, Block.UPDATE_ALL);
		if (ok) {
			c.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			var sound = state.getSoundType();
			level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
			c.stats().blocksPlaced++;
		}
		return ok;
	}
}
