package dev.autonomouscompanions.world;

import dev.autonomouscompanions.coop.Cooperation;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Regras de proteção do mundo. As companheiras só quebram blocos naturais (pedra, terra, minérios, troncos naturais...),
 * nunca contêineres/entidades de bloco, nunca blocos indestrutíveis, nunca áreas reservadas por projetos de construção
 * e nunca blocos cuja remoção liberaria lava sobre o caminho. Blocos colocados pelo jogador (tábuas, tijolos, vidro etc.)
 * não são considerados naturais e, portanto, são preservados.
 */
public final class BlockPolicy {
	private BlockPolicy() {
	}

	public static boolean isNatural(BlockState s) {
		if (s.isAir()) {
			return false;
		}
		return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)
			|| s.is(ConventionalBlockTags.STONES) || s.is(ConventionalBlockTags.GRAVELS) || s.is(ConventionalBlockTags.SANDS)
			|| s.is(ConventionalBlockTags.ORES) || s.is(BlockTags.ORES) || s.is(ConventionalBlockTags.NETHERRACKS)
			|| s.is(ConventionalBlockTags.END_STONES) || s.is(ConventionalBlockTags.NATURAL_LOGS) || s.is(BlockTags.LEAVES)
			|| s.is(BlockTags.BADLANDS_TERRACOTTA) || s.is(BlockTags.ICE) || s.is(BlockTags.SNOW) || s.is(BlockTags.MOSS_BLOCKS)
			|| s.is(BlockTags.MUD) || s.is(BlockTags.NYLIUM) || s.is(BlockTags.WART_BLOCKS) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
			|| s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.CAVE_VINES) || s.is(BlockTags.CRYSTAL_SOUND_BLOCKS) || s.is(BlockTags.SPELEOTHEMS)
			|| s.is(BlockTags.SNIFFER_DIGGABLE_BLOCK) || s.is(BlockTags.CORAL_BLOCKS) || s.is(BlockTags.CORALS)
			|| s.is(Blocks.CLAY) || s.is(Blocks.GRAVEL) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.OBSIDIAN)
			|| s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.GLOWSTONE) || s.is(Blocks.SOUL_SAND) || s.is(Blocks.SOUL_SOIL) || s.is(Blocks.PUMPKIN)
			|| s.is(Blocks.MELON) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.BAMBOO) || s.is(Blocks.CACTUS) || s.is(Blocks.KELP)
			|| s.is(Blocks.KELP_PLANT) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM)
			|| s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM) || s.is(Blocks.VINE) || s.is(Blocks.SWEET_BERRY_BUSH)
			|| s.is(Blocks.COBWEB) || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.MUDDY_MANGROVE_ROOTS) || s.is(Blocks.SHROOMLIGHT)
			|| s.is(Blocks.SCULK) || s.is(Blocks.SCULK_VEIN) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK)
			|| s.is(Blocks.MYCELIUM) || s.is(Blocks.PODZOL) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE)
			|| s.is(Blocks.CHORUS_PLANT) || s.is(Blocks.CHORUS_FLOWER);
	}

	public static boolean isUnbreakable(ServerLevel level, BlockPos pos, BlockState state) {
		return state.getDestroySpeed(level, pos) < 0;
	}

	public enum Purpose {
		/** Bloco-alvo de uma coleta explícita. */
		HARVEST,
		/** Abrir passagem (túnel/escada) até um recurso. */
		TUNNEL,
		/** Preparar terreno autorizado de um projeto. */
		TERRAIN
	}

	/** Verdadeiro se a companheira pode quebrar este bloco para o propósito indicado. */
	public static boolean canBreak(ServerLevel level, BlockPos pos, BlockState state, Purpose purpose, java.util.@Nullable UUID companionId) {
		if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()) {
			return false;
		}
		if (isUnbreakable(level, pos, state) || state.is(Blocks.BEDROCK) || state.is(BlockTags.PORTALS) || state.is(Blocks.SPAWNER)
			|| state.is(Blocks.TRIAL_SPAWNER) || state.is(Blocks.VAULT) || state.is(Blocks.END_PORTAL_FRAME) || state.is(Blocks.REINFORCED_DEEPSLATE)) {
			return false;
		}
		if (level.getBlockEntity(pos) != null) {
			return false; // nunca destrói contêineres ou blocos com inventário/dados
		}
		Cooperation coop = Cooperation.get(level.getServer());
		if (purpose != Purpose.TERRAIN && coop.isInsideProtectedArea(level, pos)) {
			return false;
		}
		if (!isNatural(state)) {
			return false;
		}
		// Não abre lava sobre o caminho.
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) {
				continue;
			}
			BlockState n = level.getBlockState(pos.relative(d));
			if (n.getFluidState().is(FluidTags.LAVA)) {
				return false;
			}
			if (purpose == Purpose.TUNNEL && n.getFluidState().is(FluidTags.WATER) && d != Direction.DOWN) {
				return false;
			}
		}
		return true;
	}
}
