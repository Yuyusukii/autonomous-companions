package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import dev.autonomouscompanions.world.BlockPolicy;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sobe em um pilar provisório (pula e coloca um bloco comum sob os pés, como o jogador faz) para alcançar blocos
 * altos, como troncos de árvores grandes. Os blocos do pilar são registrados e removidos depois ({@link #dismantle}).
 */
public final class PillarStep extends Step {
	private final int height;
	private final boolean allowLogs;
	private int placed;
	private int ticks;
	private int baseY = Integer.MIN_VALUE;
	private boolean childFailed;
	private int lastPlacedCount = -1;
	private int sincePlaced;
	private @org.jspecify.annotations.Nullable BlockPos lastPlaced;

	public PillarStep(int height) {
		this(height, false);
	}

	/** @param allowLogs pode subir nos próprios troncos (só ao cortar árvores; obras e fugas usam só enchimento comum). */
	public PillarStep(int height, boolean allowLogs) {
		this.height = height;
		this.allowLogs = allowLogs;
	}

	/**
	 * Tem blocos para o pilar? Vale o enchimento comum (terra, pedregulho...) e também troncos: como uma jogadora, ela
	 * sobe nos próprios troncos que acabou de cortar e os recupera ao desmontar (sem cavar buracos no chão).
	 */
	public static boolean hasFiller(TaskContext ctx, int amount) {
		return hasFiller(ctx, amount, false);
	}

	public static boolean hasFiller(TaskContext ctx, int amount, boolean allowLogs) {
		int total = 0;
		var inv = ctx.inventory();
		java.util.Set<net.minecraft.world.item.Item> counted = new java.util.HashSet<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!counted.add(s.getItem())) {
				continue;
			}
			if (s.getItem() instanceof BlockItem bi && TunnelStep.isFiller(bi)) {
				total += inv.countSpare(s.getItem());
			} else if (allowLogs && s.is(net.minecraft.tags.ItemTags.LOGS)) {
				total += inv.count(s.getItem());
			}
		}
		return total >= amount;
	}

	/** Coloca um bloco do pilar: enchimento comum primeiro; sem ele, um tronco (devolvido ao desmontar). */
	private boolean placePillarBlock(TaskContext ctx, BlockPos pos) {
		if (TunnelStep.placeFiller(ctx, pos)) {
			return true;
		}
		if (!allowLogs) {
			return false;
		}
		var inv = ctx.inventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(net.minecraft.tags.ItemTags.LOGS) && s.getItem() instanceof BlockItem bi
				&& Stations.placeExact(ctx.level(), ctx.companion(), pos, bi.getBlock().defaultBlockState())) {
				inv.removeRaw(s.getItem(), 1);
				return true;
			}
		}
		return false;
	}

	@Override
	public StepResult tick(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		if (baseY == Integer.MIN_VALUE) {
			baseY = c.getBlockY();
			// Centraliza no bloco (como a jogadora faz antes de "pilarar"): com o corpo meio na coluna vizinha, a cabeça batia
			// em folhas ao lado e ela pulava sem sair do lugar.
			if (allowLogs) {
				c.getNavigation().stop();
				center(c);
			}
			// Coluna acima precisa estar livre para subir. Folhas e galhos de árvore no caminho são quebrados durante a
			// subida (ao lado de um tronco quase sempre há folhas); qualquer outro bloco faz falhar logo (quem chamou
			// escolhe outro lugar).
			for (int dy = 2; dy <= height + 1; dy++) {
				BlockPos p = c.blockPosition().above(dy);
				if (blocks(ctx, p) && !clearable(ctx, p)) {
					return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("build.autonomouscompanions.unreachable"));
				}
			}
		}
		if (childFailed) {
			return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("build.autonomouscompanions.unreachable"));
		}
		if (placed >= height) {
			return StepResult.success();
		}
		// Sem subir nenhum bloco em 3 s (pulo bloqueado, chão que não aceita bloco, água...): desiste deste ponto e quem
		// chamou escolhe outro alvo. Antes ela ficava 30 s pulando no mesmo lugar.
		if (placed != lastPlacedCount) {
			lastPlacedCount = placed;
			sincePlaced = 0;
		} else if (++sincePlaced > 60 || ++ticks > 20 * 30) {
			c.debug("Andaime: sem conseguir subir (" + placed + "/" + height + "); desistindo deste ponto.");
			return placed > 0 ? StepResult.success() : StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("build.autonomouscompanions.unreachable"));
		}
		// Espaço para a cabeça na próxima subida: tira folhas/galhos logo acima antes de pular.
		// O pulo leva a cabeça até ~1,05 bloco acima da altura normal: precisa de 2 blocos livres acima da cabeça.
		BlockPos head = BlockPos.containing(c.getX(), baseY + placed + 2, c.getZ());
		if (c.onGround() && !blocks(ctx, head)) {
			head = head.above();
		}
		// Só folhas e galhos são tirados; outro bloco (ex.: o telhado da obra) fica como estava — a checagem inicial da
		// coluna já garante o espaço necessário para os blocos do pilar.
		if (allowLogs && c.onGround() && blocks(ctx, head) && clearable(ctx, head)) {
			return StepResult.push(new BreakSingleStep(head));
		}
		ctx.brain().navigator().clear();
		c.getNavigation().stop();
		if (ticks % 40 == 1) {
			c.debug("Andaime: tick " + ticks + " y=" + String.format("%.2f", c.getY()) + " noChão=" + c.onGround() + " colocados=" + placed + "/" + height
				+ " acima=" + ctx.level().getBlockState(c.blockPosition().above(2)).getBlock());
		}
		// Foi empurrada para fora do pilar (o bloco ficou ao lado, não sob os pés): recolhe o bloco (o tronco ou a terra
		// volta para ela) e recomeça daquela altura — antes o bloco ficava esquecido e ela ficava presa pulando no chão.
		if (allowLogs && c.onGround() && placed > 0 && lastPlaced != null && c.getBlockY() < baseY + placed) {
			BlockPos lost = lastPlaced;
			lastPlaced = null;
			placed--;
			ctx.companion().brain().orders().forgetPillarBlock(lost);
			c.debug("Andaime: fora do pilar; recolhendo o bloco em " + lost.toShortString());
			return StepResult.push(new BreakSingleStep(lost));
		}
		if (c.onGround() && c.getBlockY() == baseY + placed) {
			if (allowLogs) {
				center(c);
			}
			c.getJumpControl().jump();
			return StepResult.running();
		}
		// No ar acima da altura do último bloco: coloca o próximo sob os pés.
		BlockPos below = BlockPos.containing(c.getX(), baseY + placed, c.getZ());
		if (!c.onGround() && c.getY() > below.getY() + 1.0 && ctx.level().getBlockState(below).canBeReplaced()) {
			if (placePillarBlock(ctx, below)) {
				placed++;
				lastPlaced = below.immutable();
				ctx.companion().brain().orders().notePillarBlock(below);
				ctx.companion().brain().orders().noteActivity();
			} else {
				return StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.translatable("reason.autonomouscompanions.no_filler"));
			}
		}
		return StepResult.running();
	}

	@Override
	public void onChildFinished(TaskContext ctx, Step child, StepResult result) {
		if (result.isFailure()) {
			childFailed = true;
		}
	}

	/** No meio do bloco e sem impulso para os lados (para subir reto). */
	private static void center(CompanionEntity c) {
		double x = c.getBlockX() + 0.5;
		double z = c.getBlockZ() + 0.5;
		if (Math.abs(c.getX() - x) > 0.05 || Math.abs(c.getZ() - z) > 0.05) {
			c.teleportTo(x, c.getY(), z);
		}
		c.setDeltaMovement(0, c.getDeltaMovement().y, 0);
		c.getNavigation().stop();
		c.setXxa(0);
		c.setZza(0);
	}

	private static boolean blocks(TaskContext ctx, BlockPos p) {
		return !ctx.level().getBlockState(p).getCollisionShape(ctx.level(), p).isEmpty();
	}

	/** Folhas naturais e troncos de árvore (natural) podem ser tirados do caminho do pilar. */
	private static boolean clearable(TaskContext ctx, BlockPos p) {
		BlockState s = ctx.level().getBlockState(p);
		boolean tree = s.is(BlockTags.LEAVES) && (!s.hasProperty(LeavesBlock.PERSISTENT) || !s.getValue(LeavesBlock.PERSISTENT))
			|| s.is(BlockTags.LOGS) && BlockPolicy.isNatural(s);
		return tree && BlockPolicy.canBreak(ctx.level(), p, s, BlockPolicy.Purpose.TUNNEL, ctx.companion().getUUID());
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.pillar", height);
	}

	@Override
	public CompanionState state() {
		return CompanionState.COLLECTING;
	}
}
