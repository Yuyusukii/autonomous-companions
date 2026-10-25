package dev.autonomouscompanions.world;

import dev.autonomouscompanions.config.CompanionConfig;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionRole;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import java.util.List;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Conhecimento sobre a distribuição de recursos: custo/raridade, dimensão, faixa de altura e biomas típicos.
 * Os dados do jogo base vêm de tabelas conhecidas da geração de mundo; blocos de outros mods são classificados pelas
 * tags de convenção (c:ores, c:stones...) e recebem valores moderados.
 */
public final class ResourceKnowledge {
	public enum Dim {
		OVERWORLD,
		NETHER,
		END,
		ANY;

		public boolean matches(Level level) {
			return switch (this) {
				case ANY -> true;
				case OVERWORLD -> level.dimension() == Level.OVERWORLD;
				case NETHER -> level.dimension() == Level.NETHER;
				case END -> level.dimension() == Level.END;
			};
		}

		public String translationKey() {
			return "dimension.autonomouscompanions." + name().toLowerCase();
		}
	}

	/**
	 * @param cost       custo relativo por unidade (1 = trivial, 20+ = raro)
	 * @param underground recurso tipicamente subterrâneo (justifica escavação de acesso)
	 * @param dangerous  local perigoso (ex.: Deep Dark); exige preparação de equipamento
	 */
	public record Info(int cost, Dim dim, int minY, int maxY, int bestY, boolean underground, boolean dangerous, @Nullable TagKey<Biome> biomeTag,
		List<ResourceKey<Biome>> biomes) {
		public boolean hasBiomeHint() {
			return biomeTag != null || !biomes.isEmpty();
		}

		public boolean biomeMatches(Holder<Biome> biome) {
			if (biomeTag != null && biome.is(biomeTag)) {
				return true;
			}
			for (ResourceKey<Biome> key : biomes) {
				if (biome.is(key)) {
					return true;
				}
			}
			return false;
		}
	}

	private static final int MIN = -64;
	private static final int MAX = 320;

	private ResourceKnowledge() {
	}

	private static Info ow(int cost, int min, int max, int best, boolean underground) {
		return new Info(cost, Dim.OVERWORLD, min, max, best, underground, false, null, List.of());
	}

	private static Info surface(int cost, @Nullable TagKey<Biome> tag, List<ResourceKey<Biome>> biomes) {
		return new Info(cost, Dim.OVERWORLD, 40, MAX, 64, false, false, tag, biomes);
	}

	public static Info of(BlockState state) {
		Block b = state.getBlock();
		// Minérios (do mais raro ao mais comum).
		if (state.is(ConventionalBlockTags.NETHERITE_SCRAP_ORES) || b == Blocks.ANCIENT_DEBRIS) {
			return new Info(40, Dim.NETHER, 8, 119, 15, true, true, null, List.of());
		}
		if (state.is(ConventionalBlockTags.QUARTZ_ORES) || b == Blocks.NETHER_QUARTZ_ORE) {
			return new Info(3, Dim.NETHER, 10, 117, 60, true, true, null, List.of());
		}
		if (b == Blocks.NETHER_GOLD_ORE) {
			return new Info(4, Dim.NETHER, 10, 117, 60, true, true, null, List.of());
		}
		if (state.is(ConventionalBlockTags.EMERALD_ORES)) {
			return new Info(30, Dim.OVERWORLD, -16, MAX, 232, true, false, BiomeTags.IS_MOUNTAIN, List.of());
		}
		if (state.is(ConventionalBlockTags.DIAMOND_ORES)) {
			return deepslate(b) ? ow(20, MIN, 0, -58, true) : ow(26, 0, 16, 8, true);
		}
		if (state.is(ConventionalBlockTags.LAPIS_ORES)) {
			return ow(8, MIN, 64, 0, true);
		}
		if (state.is(ConventionalBlockTags.REDSTONE_ORES)) {
			return deepslate(b) ? ow(6, MIN, 0, -58, true) : ow(9, 0, 16, 8, true);
		}
		if (state.is(ConventionalBlockTags.GOLD_ORES)) {
			return new Info(8, Dim.OVERWORLD, MIN, 32, -16, true, false, null, List.of());
		}
		if (state.is(ConventionalBlockTags.IRON_ORES)) {
			return deepslate(b) ? ow(5, MIN, 0, -8, true) : ow(4, 0, 256, 16, true);
		}
		if (state.is(ConventionalBlockTags.COPPER_ORES)) {
			return ow(3, -16, 112, 48, true);
		}
		if (state.is(ConventionalBlockTags.COAL_ORES)) {
			return deepslate(b) ? ow(4, MIN, 0, -4, true) : ow(2, 0, 192, 64, true);
		}
		if (state.is(ConventionalBlockTags.ORES) || state.is(BlockTags.ORES)) {
			return ow(6, MIN, 128, 16, true);
		}
		// Dimensões específicas.
		if (state.is(ConventionalBlockTags.NETHER_NATURAL_LOGS) || state.is(BlockTags.NYLIUM) || state.is(BlockTags.WART_BLOCKS)
			|| state.is(BlockTags.BASE_STONE_NETHER) || state.is(ConventionalBlockTags.NETHERRACKS) || b == Blocks.SOUL_SAND || b == Blocks.SOUL_SOIL
			|| b == Blocks.GLOWSTONE || b == Blocks.SHROOMLIGHT) {
			return new Info(3, Dim.NETHER, 0, 128, 64, false, true, null, List.of());
		}
		if (state.is(ConventionalBlockTags.END_STONES) || b == Blocks.CHORUS_PLANT || b == Blocks.CHORUS_FLOWER) {
			return new Info(6, Dim.END, 0, 128, 60, false, true, null, List.of());
		}
		// Madeira e vegetação.
		if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.SAPLINGS)) {
			return logInfo(b);
		}
		if (b == Blocks.RED_SAND || state.is(BlockTags.BADLANDS_TERRACOTTA) || b == Blocks.RED_SANDSTONE) {
			return surface(4, BiomeTags.IS_BADLANDS, List.of());
		}
		if (state.is(BlockTags.SAND) || b == Blocks.SANDSTONE || b == Blocks.CACTUS) {
			return surface(1, BiomeTags.IS_BEACH, List.of(Biomes.DESERT, Biomes.RIVER, Biomes.BEACH));
		}
		if (b == Blocks.CLAY) {
			return surface(3, BiomeTags.IS_RIVER, List.of(Biomes.SWAMP, Biomes.LUSH_CAVES, Biomes.MANGROVE_SWAMP));
		}
		if (b == Blocks.MUD || b == Blocks.MANGROVE_ROOTS || b == Blocks.MUDDY_MANGROVE_ROOTS) {
			return surface(3, null, List.of(Biomes.MANGROVE_SWAMP));
		}
		if (b == Blocks.MYCELIUM || b == Blocks.RED_MUSHROOM_BLOCK || b == Blocks.BROWN_MUSHROOM_BLOCK) {
			return surface(10, null, List.of(Biomes.MUSHROOM_FIELDS));
		}
		if (b == Blocks.PODZOL) {
			return surface(3, BiomeTags.IS_TAIGA, List.of());
		}
		if (state.is(BlockTags.ICE) || state.is(BlockTags.SNOW) || b == Blocks.SNOW_BLOCK || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE) {
			return surface(4, null, List.of(Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES, Biomes.SNOWY_TAIGA, Biomes.FROZEN_PEAKS, Biomes.SNOWY_SLOPES));
		}
		if (b == Blocks.BAMBOO) {
			return surface(4, BiomeTags.IS_JUNGLE, List.of());
		}
		if (b == Blocks.MOSS_BLOCK || state.is(BlockTags.CAVE_VINES)) {
			return new Info(5, Dim.OVERWORLD, -20, 60, 20, true, false, null, List.of(Biomes.LUSH_CAVES));
		}
		if (state.is(BlockTags.CRYSTAL_SOUND_BLOCKS)) {
			return ow(8, MIN, 30, -20, true);
		}
		if (state.is(BlockTags.SPELEOTHEMS) || b == Blocks.DRIPSTONE_BLOCK) {
			return new Info(6, Dim.OVERWORLD, MIN, 60, 0, true, false, null, List.of(Biomes.DRIPSTONE_CAVES));
		}
		if (b == Blocks.SCULK || b == Blocks.SCULK_VEIN) {
			return new Info(14, Dim.OVERWORLD, MIN, 0, -40, true, true, null, List.of(Biomes.DEEP_DARK));
		}
		if (b == Blocks.DEEPSLATE || b == Blocks.TUFF) {
			return ow(2, MIN, 8, -8, true);
		}
		if (b == Blocks.CALCITE) {
			return ow(5, MIN, 120, 30, true);
		}
		if (b == Blocks.OBSIDIAN) {
			return ow(8, MIN, 64, -50, true);
		}
		if (state.is(ConventionalBlockTags.STONES) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(ConventionalBlockTags.GRAVELS)) {
			return ow(1, MIN, MAX, 50, false);
		}
		if (state.is(BlockTags.DIRT) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.REPLACEABLE)) {
			return surface(1, null, List.of());
		}
		if (b == Blocks.PUMPKIN || b == Blocks.MELON || b == Blocks.SUGAR_CANE) {
			return surface(5, null, List.of());
		}
		return new Info(6, Dim.OVERWORLD, MIN, MAX, 50, false, false, null, List.of());
	}

	private static boolean deepslate(Block b) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath().startsWith("deepslate_");
	}

	private static Info logInfo(Block b) {
		String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
		if (path.startsWith("oak") || path.startsWith("stripped_oak")) {
			return surface(1, BiomeTags.IS_FOREST, List.of(Biomes.PLAINS, Biomes.FOREST));
		}
		if (path.startsWith("birch")) {
			return surface(2, null, List.of(Biomes.BIRCH_FOREST, Biomes.OLD_GROWTH_BIRCH_FOREST, Biomes.FOREST));
		}
		if (path.startsWith("spruce")) {
			return surface(2, BiomeTags.IS_TAIGA, List.of(Biomes.SNOWY_TAIGA, Biomes.GROVE, Biomes.WINDSWEPT_FOREST));
		}
		if (path.startsWith("jungle")) {
			return surface(4, BiomeTags.IS_JUNGLE, List.of());
		}
		if (path.startsWith("acacia")) {
			return surface(4, BiomeTags.IS_SAVANNA, List.of());
		}
		if (path.startsWith("dark_oak")) {
			return surface(4, null, List.of(Biomes.DARK_FOREST));
		}
		if (path.startsWith("pale_oak")) {
			return surface(8, null, List.of(Biomes.PALE_GARDEN));
		}
		if (path.startsWith("mangrove")) {
			return surface(5, null, List.of(Biomes.MANGROVE_SWAMP));
		}
		if (path.startsWith("cherry")) {
			return surface(8, null, List.of(Biomes.CHERRY_GROVE));
		}
		if (path.startsWith("poplar")) {
			return surface(6, null, List.of(Biomes.DAPPLED_FOREST));
		}
		return surface(2, BiomeTags.IS_FOREST, List.of());
	}

	/** Perfil automático conforme o custo do recurso. */
	public static SearchProfile autoProfile(int cost) {
		if (cost <= 2) {
			return SearchProfile.COMMON;
		}
		if (cost <= 7) {
			return SearchProfile.MODERATE;
		}
		if (cost <= 26) {
			return SearchProfile.RARE;
		}
		return SearchProfile.EXPEDITION;
	}

	/** Alcance máximo (blocos horizontais) para um perfil, considerando a função e limites configurados. */
	public static int range(SearchProfile profile, int cost, CompanionRole role, int orderOverride, int companionOverride) {
		CompanionConfig cfg = ConfigManager.get();
		if (orderOverride > 0) {
			return Math.min(orderOverride, cfg.alcanceBuscaMaximoAbsoluto);
		}
		if (companionOverride > 0) {
			return Math.min(companionOverride, cfg.alcanceBuscaMaximoAbsoluto);
		}
		SearchProfile p = profile == SearchProfile.AUTO ? autoProfile(cost) : profile;
		int base = switch (p) {
			case COMMON, AUTO -> cfg.alcanceBuscaComum;
			case MODERATE -> cfg.alcanceBuscaModerado;
			case RARE -> cfg.alcanceBuscaRaro;
			case EXPEDITION -> cfg.alcanceBuscaExpedicao;
		};
		return (int) Math.min(cfg.alcanceBuscaMaximoAbsoluto, base * role.searchRangeMultiplier);
	}

	/** Verifica se o recurso pode existir na dimensão em que a companheira está. */
	public static boolean availableIn(ServerLevel level, Info info) {
		return info.dim().matches(level);
	}
}
