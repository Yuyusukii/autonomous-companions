package dev.autonomouscompanions.mining;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Análise das regras reais de obtenção de um bloco: dureza, ferramenta exigida e o que a tabela de loot produz com
 * ferramentas comuns e com Toque Suave. Usa {@link Block#getDrops} (as mesmas tabelas que o jogo usa ao quebrar).
 */
public final class HarvestAnalyzer {
	private HarvestAnalyzer() {
	}

	/** Resultado da análise de um estado de bloco. */
	public record Analysis(BlockState state, boolean unbreakable, boolean requiresCorrectTool, Set<Item> normalDrops, Set<Item> silkDrops,
		@Nullable ItemStack bestReferenceTool) {
		public Item blockItem() {
			return state.getBlock().asItem();
		}

		public boolean dropsItself() {
			Item self = blockItem();
			return self != Items.AIR && normalDrops.contains(self);
		}

		public boolean dropsItselfWithSilk() {
			Item self = blockItem();
			return self != Items.AIR && silkDrops.contains(self);
		}

		public boolean dropsNothing() {
			return normalDrops.isEmpty();
		}
	}

	private static List<ItemStack> referenceTools() {
		return List.of(new ItemStack(Items.NETHERITE_PICKAXE), new ItemStack(Items.NETHERITE_AXE), new ItemStack(Items.NETHERITE_SHOVEL),
			new ItemStack(Items.NETHERITE_HOE), new ItemStack(Items.SHEARS), new ItemStack(Items.NETHERITE_SWORD), ItemStack.EMPTY);
	}

	public static Holder<Enchantment> silkTouch(ServerLevel level) {
		return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
	}

	public static boolean hasSilkTouch(ServerLevel level, ItemStack stack) {
		return !stack.isEmpty() && EnchantmentHelper.getItemEnchantmentLevel(silkTouch(level), stack) > 0;
	}

	/** Ferramenta de referência adequada: correta para drops e mais rápida. */
	public static @Nullable ItemStack bestReferenceTool(BlockState state) {
		ItemStack best = null;
		float bestSpeed = 0;
		for (ItemStack tool : referenceTools()) {
			if (tool.isEmpty()) {
				continue;
			}
			boolean correct = tool.isCorrectToolForDrops(state);
			float speed = tool.getDestroySpeed(state);
			if ((correct || !state.requiresCorrectToolForDrops()) && speed > bestSpeed && (correct || speed > 1.0f)) {
				best = tool;
				bestSpeed = speed;
			}
		}
		return best;
	}

	public static Analysis analyze(ServerLevel level, BlockState state, BlockPos where) {
		boolean unbreakable = state.getDestroySpeed(level, where) < 0;
		Set<Item> normal = new LinkedHashSet<>();
		Set<Item> silk = new LinkedHashSet<>();
		ItemStack best = bestReferenceTool(state);
		if (!unbreakable) {
			List<ItemStack> tools = new ArrayList<>();
			if (best != null) {
				tools.add(best);
			}
			if (!state.requiresCorrectToolForDrops()) {
				tools.add(ItemStack.EMPTY);
			}
			for (ItemStack tool : tools) {
				for (int sample = 0; sample < 6; sample++) {
					for (ItemStack drop : safeDrops(level, state, where, null, tool)) {
						normal.add(drop.getItem());
					}
				}
			}
			ItemStack silkTool = new ItemStack(best != null && best.isCorrectToolForDrops(state) ? best.getItem() : Items.NETHERITE_PICKAXE);
			silkTool.enchant(silkTouch(level), 1);
			for (int sample = 0; sample < 3; sample++) {
				for (ItemStack drop : safeDrops(level, state, where, null, silkTool)) {
					silk.add(drop.getItem());
				}
			}
		}
		return new Analysis(state, unbreakable, state.requiresCorrectToolForDrops(), normal, silk, best);
	}

	/** Drops reais com uma ferramenta específica (usado também na quebra efetiva). */
	public static List<ItemStack> drops(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockEntity be, @Nullable Entity breaker, ItemStack tool) {
		return Block.getDrops(state, level, pos, be, breaker, tool);
	}

	private static List<ItemStack> safeDrops(ServerLevel level, BlockState state, BlockPos where, @Nullable Entity breaker, ItemStack tool) {
		try {
			return Block.getDrops(state, level, where, null, breaker, tool);
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	/** A ferramenta {@code tool} consegue obter drops deste bloco? */
	public static boolean canHarvest(BlockState state, ItemStack tool) {
		return !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
	}
}
