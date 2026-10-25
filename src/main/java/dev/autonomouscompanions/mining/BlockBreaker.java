package dev.autonomouscompanions.mining;

import dev.autonomouscompanions.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Quebra de blocos com tempo real de mineração, seguindo a fórmula do jogador: velocidade da ferramenta (com
 * Eficiência), dureza do bloco, penalidades na água/no ar e divisor 30 (ferramenta correta) ou 100 (incorreta).
 * Os drops vêm da tabela de loot do bloco com a ferramenta usada e só existem se a ferramenta for adequada.
 */
public final class BlockBreaker {
	public enum Status {
		BREAKING,
		BROKEN,
		INVALID
	}

	private @Nullable BlockPos pos;
	private @Nullable BlockState expected;
	private float progress;
	private int lastStage = -1;
	private int ticks;
	private final List<ItemStack> lastDrops = new ArrayList<>();

	public @Nullable BlockPos target() {
		return pos;
	}

	public void begin(BlockPos pos, BlockState state) {
		if (this.pos == null || !this.pos.equals(pos)) {
			this.pos = pos.immutable();
			this.expected = state;
			this.progress = 0;
			this.ticks = 0;
			this.lastStage = -1;
		}
	}

	public void reset(ServerLevel level, CompanionEntity c) {
		if (pos != null) {
			level.destroyBlockProgress(c.getId(), pos, -1);
		}
		pos = null;
		expected = null;
		progress = 0;
		lastStage = -1;
	}

	public List<ItemStack> lastDrops() {
		return lastDrops;
	}

	public static float progressPerTick(ServerLevel level, CompanionEntity c, BlockPos pos, BlockState state) {
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0) {
			return 0;
		}
		if (hardness == 0) {
			return 1.0f;
		}
		ItemStack tool = c.mainHandItem();
		float speed = tool.isEmpty() ? 1.0f : tool.getDestroySpeed(state);
		if (speed > 1.0f) {
			speed += (float) c.getAttributeValue(Attributes.MINING_EFFICIENCY);
		}
		speed *= (float) c.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
		if (c.isEyeInFluid(FluidTags.WATER)) {
			speed *= (float) c.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
		}
		if (!c.onGround()) {
			speed /= 5.0f;
		}
		boolean correct = !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
		return speed / hardness / (correct ? 30.0f : 100.0f);
	}

	/**
	 * Avança a quebra do bloco-alvo. Ao terminar, o bloco é removido do mundo, a ferramenta perde durabilidade e os
	 * drops ficam em {@link #lastDrops()} (vazio se a ferramenta não for adequada, como para o jogador).
	 */
	public Status tick(ServerLevel level, CompanionEntity c) {
		if (pos == null || expected == null) {
			return Status.INVALID;
		}
		c.setActivityLevel(2); // quebrar bloco é trabalho (mineração, túnel, desmontar pilar)
		BlockState now = level.getBlockState(pos);
		if (now.isAir() || now.getBlock() != expected.getBlock()) {
			reset(level, c);
			return Status.INVALID;
		}
		c.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		ticks++;
		if (ticks % 5 == 1) {
			c.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		}
		float delta = progressPerTick(level, c, pos, now);
		if (delta <= 0) {
			reset(level, c);
			return Status.INVALID;
		}
		progress += delta;
		int stage = (int) (progress * 10.0f);
		if (stage != lastStage && progress < 1.0f) {
			level.destroyBlockProgress(c.getId(), pos, stage);
			lastStage = stage;
		}
		if (progress < 1.0f) {
			return Status.BREAKING;
		}
		finish(level, c, now);
		return Status.BROKEN;
	}

	private void finish(ServerLevel level, CompanionEntity c, BlockState state) {
		BlockPos at = pos;
		ItemStack tool = c.mainHandItem();
		BlockEntity be = level.getBlockEntity(at);
		boolean correct = !state.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(state));
		lastDrops.clear();
		if (correct) {
			lastDrops.addAll(HarvestAnalyzer.drops(level, at, state, be, c, tool));
		}
		level.destroyBlockProgress(c.getId(), at, -1);
		level.destroyBlock(at, false, c);
		if (correct) {
			state.spawnAfterBreak(level, at, tool, true);
		}
		if (!tool.isEmpty() && tool.isDamageableItem() && state.getDestroySpeed(level, at) != 0.0f) {
			tool.hurtAndBreak(1, c, EquipmentSlot.MAINHAND);
		}
		c.needs().addExhaustion(0.005f);
		c.stats().blocksMined++;
		pos = null;
		expected = null;
		progress = 0;
		lastStage = -1;
	}

	/** Coloca os drops no inventário; o que não couber vira item no chão (para não perder nada). */
	public static List<ItemStack> storeDrops(ServerLevel level, CompanionEntity c, BlockPos at, List<ItemStack> drops) {
		List<ItemStack> leftovers = new ArrayList<>();
		for (ItemStack drop : drops) {
			ItemStack rest = c.inventory().insert(drop);
			if (!rest.isEmpty()) {
				leftovers.add(rest);
				ItemEntity entity = new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, rest.copy());
				entity.setDefaultPickUpDelay();
				level.addFreshEntity(entity);
			}
		}
		return leftovers;
	}
}
