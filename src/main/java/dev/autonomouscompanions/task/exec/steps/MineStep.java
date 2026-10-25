package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.coop.Cooperation;
import dev.autonomouscompanions.crafting.CostModel;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.inventory.InventoryManager;
import dev.autonomouscompanions.mining.BlockBreaker;
import dev.autonomouscompanions.mining.DropIndex;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.world.BlockPolicy;
import dev.autonomouscompanions.world.KnownResources;
import dev.autonomouscompanions.world.ResourceKnowledge;
import dev.autonomouscompanions.world.ResourceScanner;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Coleta real de blocos no mundo. Fases: ferramenta → busca incremental (só chunks carregados) → aproximação por
 * navegação (ou escavação controlada se o bloco estiver enterrado) → quebra com tempo real → conferência da quantidade.
 * Se nada for encontrado por perto, explora: ruma ao bioma típico do recurso, desce até a altura ideal para minérios
 * (mineração em galeria) ou percorre uma espiral, sempre dentro do alcance do perfil de busca.
 */
public final class MineStep extends CompositeStep {
	private enum Phase {
		TOOL,
		SEARCH,
		APPROACH,
		BREAK,
		EXPLORE
	}

	private final List<DropIndex.BlockSource> sources;
	private final boolean silk;
	private final ItemTarget target;
	private final int count;
	private final boolean missionItem;
	private final Set<Item> chain;
	private final Predicate<BlockState> matcher;
	private final ResourceKnowledge.Info info;
	private Phase phase = Phase.TOOL;
	private ResourceScanner.@Nullable Job job;
	private final Deque<BlockPos> candidates = new ArrayDeque<>();
	private @Nullable BlockPos current;
	private @Nullable BlockPos origin;
	private int range;
	private int searchRadius = 48;
	private int exploreLeg;
	private @Nullable BlockPos exploreTarget;
	private boolean biomeChecked;
	/** Memória: marcas de "sem acesso" já carregadas, memória além do raio já consultada, hipótese sendo verificada. */
	private boolean marksLoaded;
	private boolean memoryChecked;
	private dev.autonomouscompanions.mind.memory.WorldMemory.@Nullable Region hypothesis;
	private int approachTicks;
	private double approachBest;
	private int approachStall;
	/** Já decidiu se chega andando ou cavando até o alvo atual (decidido a até 24 blocos, alcance confiável do A*). */
	private boolean walkChecked;
	private final List<BlockPos> treeBases = new ArrayList<>();
	private int brokenHere;
	private boolean optionalToolTried;
	private boolean widenedForShare;
	/** Altura máxima do pilar provisório para alcançar troncos altos (árvores comuns: até ~9 blocos de tronco). */
	private static final int MAX_PILLAR = 10;
	/** Etapas de exploração antes de desistir (e o planejador tentar outro caminho: outra madeira, receita...). */
	private static final int MAX_EXPLORE_LEGS = 10;
	/** Quantas vezes já foi buscar terra para o pilar nesta etapa. */
	private int fillerFetches;
	private int statusTicks;

	public MineStep(List<DropIndex.BlockSource> sources, boolean silk, ItemTarget target, int count, boolean missionItem, Set<Item> chain) {
		this.sources = sources;
		this.silk = silk;
		this.target = target;
		this.count = count;
		this.missionItem = missionItem;
		this.chain = chain;
		Set<Block> blocks = new HashSet<>();
		List<BlockState> ageStates = new ArrayList<>();
		for (DropIndex.BlockSource s : sources) {
			if (hasAge(s.state())) {
				ageStates.add(s.state());
			} else {
				blocks.add(s.state().getBlock());
			}
		}
		this.matcher = state -> blocks.contains(state.getBlock()) || ageStates.contains(state);
		this.info = ResourceKnowledge.of(sources.getFirst().state());
	}

	private static boolean hasAge(BlockState state) {
		for (Property<?> p : state.getProperties()) {
			if (p instanceof IntegerProperty && p.getName().equals("age")) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void start(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		origin = c.blockPosition();
		Order order = ctx.order();
		var profile = order != null && missionItem ? order.profile() : dev.autonomouscompanions.task.OrderEnums.SearchProfile.AUTO;
		int orderMax = order != null && missionItem ? order.maxDistance() : 0;
		range = ResourceKnowledge.range(profile, info.cost(), c.role(), orderMax, c.settings().maxSearchDistance);
		if (ctx.searchWidening() > 0) {
			// Habilidade pediu "procurar mais longe" depois de não achar: dobra o alcance a cada vez (até o máximo absoluto).
			range = (int) Math.min(dev.autonomouscompanions.config.ConfigManager.get().alcanceBuscaMaximoAbsoluto, (long) range << ctx.searchWidening());
		}
		if (ctx.rangeCap() > 0) {
			range = Math.min(range, ctx.rangeCap());
		}
		searchRadius = Math.min(range, 64);
	}

	private int have(TaskContext ctx) {
		return target.count(ctx.companion(), missionItem);
	}

	@Override
	protected boolean isOptional(Step child) {
		return child instanceof EnsureToolStep e && optionalToolTried && !sources.getFirst().state().requiresCorrectToolForDrops()
			// Terra para o pilar é só um meio: se não achar, ela segue com outro alvo.
			|| child instanceof ObtainStep o && o.target().items().equals(java.util.Set.of(Items.DIRT)) && !target.items().contains(Items.DIRT);
	}

	@Override
	protected StepResult run(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		if (have(ctx) >= count) {
			replant(ctx);
			return StepResult.success();
		}
		if (!info.dim().matches(level)) {
			return StepResult.fail(StepResult.FailureCode.WRONG_DIMENSION, Component.translatable("reason.autonomouscompanions.wrong_dimension",
				target.describe(), Component.translatable(info.dim().translationKey())));
		}
		if (++statusTicks % 200 == 0) {
			c.debug("Mineração (" + target.describe().getString() + "): fase " + phase + ", alvo " + (current == null ? "-" : current.toShortString() + " " + level.getBlockState(current).getBlock().getName().getString())
				+ ", candidatos " + candidates.size() + ", estou em " + c.blockPosition().toShortString());
		}
		if (missionItem || ctx.order() != null) {
			ctx.brain().chunkLoader().keepActive();
		}
		long t0 = System.nanoTime();
		Phase was = phase;
		try {
			return runPhase(ctx);
		} finally {
			c.brain().metrics().stepTick("MineStep." + was, System.nanoTime() - t0);
		}
	}

	private StepResult runPhase(TaskContext ctx) {
		switch (phase) {
			case TOOL -> {
				return tool(ctx);
			}
			case SEARCH -> {
				return search(ctx);
			}
			case APPROACH -> {
				return approach(ctx);
			}
			case BREAK -> {
				return breakBlock(ctx);
			}
			case EXPLORE -> {
				return explore(ctx);
			}
		}
		return StepResult.running();
	}

	private StepResult tool(TaskContext ctx) {
		BlockState sample = sources.getFirst().state();
		if (silk) {
			if (Gear.bestOwnedFor(ctx.companion(), sample, false, true, ctx.level()) == null) {
				return StepResult.fail(StepResult.FailureCode.IMPOSSIBLE, Component.translatable("reason.autonomouscompanions.needs_silk", sample.getBlock().getName()));
			}
		} else if (sample.requiresCorrectToolForDrops() && !EnsureToolStep.ownsSuitable(ctx, sample, true)) {
			return StepResult.push(new EnsureToolStep(sample, true, chain));
		} else if (!optionalToolTried && count >= 3 && !EnsureToolStep.ownsSuitable(ctx, sample, false)) {
			optionalToolTried = true;
			// Ferramenta que só acelera: vale a pena se for barata e não estiver sendo produzida acima na cadeia.
			double cost = ctx.costs().efficientToolCost(sample);
			boolean toolInChain = false;
			for (Item i : chain) {
				if (Gear.isTool(new ItemStack(i))) {
					toolInChain = true;
					break;
				}
			}
			// Só compensa se os materiais estiverem praticamente à mão (custo ~ só fabricação); senão ela trabalharia
			// sem ferramenta mais rápido do que daria a volta para obtê-la.
			if (cost < 3.0 && !toolInChain) {
				return StepResult.push(new EnsureToolStep(sample, false, chain));
			}
		}
		phase = Phase.SEARCH;
		return StepResult.running();
	}

	private StepResult search(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		c.setCompanionState(CompanionState.SEARCHING, Component.translatable("detail.autonomouscompanions.searching", target.describe(), searchRadius));
		if (job == null) {
			if (!marksLoaded) {
				// Pontos sem acesso de tentativas recentes (observação temporária; expira em 10 min).
				marksLoaded = true;
				ctx.unreachable().addAll(dev.autonomouscompanions.mind.memory.WorldMemory.get(level.getServer()).unreachableNear(c.getUUID(), level.dimension(),
					c.blockPosition(), range, level.getGameTime()));
			}
			// Primeiro, posições já conhecidas pela equipe.
			long tKnown = System.nanoTime();
			BlockPos known = KnownResources.nearest(level, matcher, c.blockPosition(), searchRadius, ctx.unreachable());
			tKnown = System.nanoTime() - tKnown;
			if (tKnown > 5_000_000L) {
				c.debug(String.format("Busca: recursos conhecidos levou %.1f ms", tKnown / 1.0E6));
			}
			if (known != null && !Cooperation.get(level.getServer()).isTargetClaimedByOther(c.getUUID(), known)) {
				candidates.clear();
				candidates.add(known);
				phase = Phase.APPROACH;
				return StepResult.running();
			}
			boolean exposedOnly = !ConfigManager.get().escanearBlocosEnterrados;
			// Faixa de altura típica do recurso, sempre incluindo uma faixa em torno da altura atual (mundos planos,
			// vales e terrenos personalizados têm a superfície fora das alturas típicas).
			int minY = Math.max(level.getMinY(), Math.min(info.minY() - 8, c.getBlockY() - 24));
			int maxY = Math.min(level.getMaxY(), Math.max(info.maxY() + 16, c.getBlockY() + 24));
			job = new ResourceScanner.Job(matcher, c.blockPosition(), searchRadius, minY, maxY, exposedOnly, ctx.unreachable(), 24);
		}
		long tScan = System.nanoTime();
		job.step(level);
		tScan = System.nanoTime() - tScan;
		if (tScan > 5_000_000L) {
			c.debug(String.format("Busca: varredura levou %.1f ms neste tick", tScan / 1.0E6));
		}
		if (!job.isComplete()) {
			return StepResult.running();
		}
		c.debug("Busca por " + target.describe().getString() + ": " + job.results().size() + " encontrados (raio " + searchRadius + ", centro "
			+ job.center().toShortString() + ")");
		// O que ela viu fica na memória da equipe (minérios e troncos; o resto não vale a pena lembrar).
		int remembered = 0;
		for (BlockPos p : job.results()) {
			BlockState seen = level.getBlockState(p);
			if (remembered < 64 && KnownResources.isInteresting(seen)) {
				KnownResources.record(level, p, seen.getBlock());
				remembered++;
			}
		}
		if (hypothesis != null) {
			// Chegou à área que ela supunha ter o recurso: confirma (achou) ou enfraquece a hipótese (não achou nada).
			if (job.results().isEmpty()) {
				dev.autonomouscompanions.mind.memory.WorldMemory.get(level.getServer()).refute(level.dimension(), hypothesis, level.getGameTime());
				c.debug("Hipótese não confirmada: nada de " + target.describe().getString() + " perto de " + hypothesis.center().toShortString() + ".");
			}
			hypothesis = null;
		}
		candidates.clear();
		Cooperation coop = Cooperation.get(level.getServer());
		// Divisão do trabalho: alvos na área de outra companheira (ex.: a árvore que ela está cortando) ficam por último.
		List<BlockPos> contested = new ArrayList<>();
		// Ordem como a jogadora pensa: o desnível pesa mais (descer e subir custa caro) e um bloco enterrado longe só ganha
		// se estiver bem mais perto que um à vista (cavar até ele custa ~8 blocos de caminhada); enterrado colado nela (até 5
		// blocos) não perde nada — cavar 1 ou 2 blocos é trivial. Antes um bloco à vista no fundo de uma caverna ganhava da
		// pedra encostada nela, e ela descia 13 blocos à toa.
		long tSort = System.nanoTime();
		BlockPos from = c.blockPosition();
		List<BlockPos> ordered = new ArrayList<>(job.results());
		ordered.sort(java.util.Comparator.comparingDouble(p -> {
			double dx = p.getX() - from.getX();
			double dz = p.getZ() - from.getZ();
			double d = Math.sqrt(dx * dx + dz * dz) + Math.abs(p.getY() - from.getY()) * 1.6;
			return d + (d <= 5 || ResourceScanner.isExposed(level, p) ? 0 : 8);
		}));
		tSort = System.nanoTime() - tSort;
		long tCoop = System.nanoTime();
		if (tSort > 5_000_000L) {
			c.debug(String.format("Busca: ordenar %d alvos levou %.1f ms", ordered.size(), tSort / 1.0E6));
		}
		for (BlockPos p : ordered) {
			if (coop.isTargetClaimedByOther(c.getUUID(), p) || coop.isInsideProtectedArea(level, p)) {
				continue;
			}
			if (coop.isInOtherWorkArea(c.getUUID(), p)) {
				contested.add(p);
			} else if (candidates.size() < 32) {
				candidates.add(p);
			}
		}
		tCoop = System.nanoTime() - tCoop;
		if (tCoop > 5_000_000L) {
			c.debug(String.format("Busca: checagens de cooperação de %d alvos levaram %.1f ms", ordered.size(), tCoop / 1.0E6));
		}
		job = null;
		if (candidates.isEmpty() && !contested.isEmpty() && !widenedForShare && searchRadius < range) {
			// Só há alvos onde outra já trabalha: procura mais longe antes de dividir o mesmo lugar.
			widenedForShare = true;
			searchRadius = Math.min(range, Math.max(searchRadius * 2, searchRadius + 24));
			c.debug("Alvos próximos já estão com outra companheira; procurando mais longe (raio " + searchRadius + ").");
			return StepResult.running();
		}
		for (BlockPos p : contested) {
			if (candidates.size() >= 32) {
				break;
			}
			candidates.add(p);
		}
		if (candidates.isEmpty()) {
			phase = Phase.EXPLORE;
			return StepResult.running();
		}
		phase = Phase.APPROACH;
		return StepResult.running();
	}

	private StepResult approach(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		Cooperation coop = Cooperation.get(level.getServer());
		if (current == null) {
			while (!candidates.isEmpty()) {
				BlockPos p = candidates.poll();
				if (coop.isInOtherWorkArea(c.getUUID(), p) && candidates.stream().anyMatch(q -> !coop.isInOtherWorkArea(c.getUUID(), q))) {
					candidates.addLast(p); // outra companheira está trabalhando aí: tenta primeiro outro alvo
					continue;
				}
				BlockState s = level.getBlockState(p);
				if (matcher.test(s) && !ctx.unreachable().contains(p) && !ctx.brain().orders().isPillarBlock(p) && BlockPolicy.canBreak(level, p, s, BlockPolicy.Purpose.HARVEST, c.getUUID())
					&& coop.claimTarget(c.getUUID(), p)) {
					current = p;
					// Reserva a área ao redor (uma árvore inteira, no caso de troncos) para as outras irem a outro lugar.
					coop.claimWorkArea(c.getUUID(), p, s.is(net.minecraft.tags.BlockTags.LOGS) ? 4 : 2);
					approachTicks = 0;
					approachStall = 0;
					approachBest = Double.MAX_VALUE;
					walkChecked = false;
					break;
				}
			}
			if (current == null) {
				phase = Phase.SEARCH;
				return StepResult.running();
			}
		}
		BlockState state = level.getBlockState(current);
		if (!matcher.test(state)) {
			release(ctx);
			return StepResult.running();
		}
		c.setCompanionState(CompanionState.COLLECTING, Component.translatable("detail.autonomouscompanions.collecting", target.describe(), have(ctx), count));
		double reach = c.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
		Vec3 eye = c.getEyePosition();
		if (eye.distanceTo(Vec3.atCenterOf(current)) <= reach) {
			BlockPos obstruction = obstruction(level, c, current);
			if (obstruction == null) {
				phase = Phase.BREAK;
				ctx.brain().navigator().clear();
				return StepResult.running();
			}
			BlockState os = level.getBlockState(obstruction);
			if (BlockPolicy.canBreak(level, obstruction, os, BlockPolicy.Purpose.TUNNEL, c.getUUID())) {
				return StepResult.push(new BreakSingleStep(obstruction));
			}
			markUnreachable(ctx, current);
			release(ctx);
			return StepResult.running();
		}
		// Bloco alto demais acima de uma posição alcançável (ex.: tronco de árvore): sobe em um pilar provisório.
		// Cortando árvore: tronco logo acima e ao lado (fora do alcance ou atrás de folhas) também se resolve subindo.
		boolean overhead = horizontal(c.blockPosition(), current) <= (cuttingLogs() ? 2.5 : 2.2);
		if (current.getY() - c.getY() > (cuttingLogs() ? 3 : 4) && overhead && c.onGround()) {
			int height = Math.max(1, Mth.ceil(current.getY() - c.getY() - 3.2));
			if (height > 0 && height <= MAX_PILLAR && PillarStep.hasFiller(ctx, height, cuttingLogs())) {
				return StepResult.push(new PillarStep(height, cuttingLogs()));
			}
			if (height > 0 && height <= MAX_PILLAR && fillerFetches < 3 && !target.items().contains(Items.DIRT)) {
				// Sem blocos para o pilar: pega terra ali mesmo (o chão quase sempre é terra) em vez de largar a árvore
				// pela metade e ir procurar outra longe. A terra volta para ela quando o pilar é desmontado.
				fillerFetches++;
				c.debug("Sem blocos para subir até " + current.toShortString() + ": pegando " + (height + 2) + " de terra por perto.");
				java.util.Set<Item> next = new java.util.HashSet<>(chain);
				next.addAll(target.items());
				return StepResult.push(new ObtainStep(ItemTarget.of(Items.DIRT), height + 2, false, next, false));
			}
		}
		if (!walkChecked && c.distanceToSqr(Vec3.atCenterOf(current)) <= 24 * 24) {
			walkChecked = true;
			// Decide logo como chegar: andando, se houver caminho; cavando direto (como uma jogadora), se o bloco estiver
			// enterrado ou sem caminho. Antes ela tentava andar até desistir, pulando parada e rodando em volta.
			long tPath = System.nanoTime();
			// Tronco no alto: o destino a pé é o chão ao pé da árvore (caminho curto e barato); de lá ela sobe no pilar. O A*
			// até o bloco no ar explorava tudo antes de desistir (5 a 20 ms por cálculo, medido no teste de fluidez).
			BlockPos walkGoal = cuttingLogs() ? groundBelow(level, current) : current;
			net.minecraft.world.level.pathfinder.Path path = c.getNavigation().createPath(walkGoal, 2);
			tPath = System.nanoTime() - tPath;
			if (tPath > 5_000_000L) {
				c.debug(String.format("Aproximação: cálculo de caminho até %s levou %.1f ms (%s)", current.toShortString(), tPath / 1.0E6,
					path == null ? "nenhum" : path.getNodeCount() + " nós"));
			}
			// O fim do caminho precisa ficar ao alcance do braço (o "alcança" do jogo não basta: ver Paths).
			boolean walkable = dev.autonomouscompanions.nav.Paths.reaches(path, current, reach - 0.5);
			if (!walkable && cuttingLogs() && current.getY() > c.getY() + 2 && horizontal(c.blockPosition(), current) <= 2.5) {
				walkable = true; // tronco logo acima: sobe no pilar em vez de cavar "túnel" no ar
			}
			if (!walkable && path != null && path.getEndNode() != null) {
				// Bloco no alto (ex.: o resto do tronco de uma árvore): se dá para chegar a pé embaixo/ao lado dele, ela vai
				// até lá e sobe num pilar — cavar um "túnel" no ar falhava e ela largava a árvore e ia procurar outra longe.
				BlockPos end = path.getEndNode().asBlockPos();
				if (current.getY() > end.getY() + 2 && current.getY() - end.getY() <= MAX_PILLAR + 4 && horizontal(end, current) <= 2.2) {
					walkable = true;
				}
			}
			c.debug("Caminho até " + current.toShortString() + ": " + (path == null ? "nenhum" : "alcança=" + path.canReach() + " fim=" + (path.getEndNode() == null ? "-" : path.getEndNode().asBlockPos().toShortString()) + " nós=" + path.getNodeCount()));
			if (!walkable) {
				approachTicks = 1;
				if (c.settings().allowTerrainChanges && c.distanceToSqr(Vec3.atCenterOf(current)) < 48 * 48) {
					c.debug("Sem caminho a pé até " + current.toShortString() + ": cavando direto até o bloco.");
					return StepResult.push(new TunnelStep(current, reach - 0.5, 96));
				}
				markUnreachable(ctx, current);
				release(ctx);
				return StepResult.running();
			}
		}
		Navigator nav = ctx.brain().navigator();
		// Tronco no alto: vai até o pé da árvore (o navegador não fica recalculando caminho até um bloco no ar).
		BlockPos walkTo = cuttingLogs() && current.getY() > c.getY() + 2 ? groundBelow(level, current) : current;
		nav.setGoal(walkTo, 2.5, travelSpeed(c, current));
		Navigator.Status status = nav.tick();
		approachTicks++;
		// Sem chegar mais perto por 5 s: para de insistir (não fica pulando no lugar).
		double d = c.distanceToSqr(Vec3.atCenterOf(current));
		if (d < approachBest - 1.0) {
			approachBest = d;
			approachStall = 0;
		} else {
			approachStall++;
		}
		if (status == Navigator.Status.FAILED || approachStall > 100 || approachTicks > 20 * 45) {
			c.debug("Aproximação sem progresso até " + current.toShortString() + " (" + status + ").");
			nav.clear();
			boolean buried = !ResourceScanner.isExposed(level, current);
			if ((buried || status == Navigator.Status.FAILED) && c.settings().allowTerrainChanges && c.distanceToSqr(Vec3.atCenterOf(current)) < 48 * 48) {
				BlockPos goal = current;
				return StepResult.push(new TunnelStep(goal, reach - 0.5, 96));
			}
			markUnreachable(ctx, current);
			release(ctx);
		}
		return StepResult.running();
	}

	@Override
	protected StepResult onFailure(TaskContext ctx, StepResult failure) {
		ctx.companion().debug("Falha ao chegar ao alvo: " + (failure.reason() != null ? failure.reason().getString() : failure.code()));
		if (failure.code() == StepResult.FailureCode.NO_ROUTE || failure.code() == StepResult.FailureCode.DANGER) {
			// Túnel ou pilar falhou: abandona este alvo e tenta outro.
			if (current != null) {
				markUnreachable(ctx, current);
				release(ctx);
			}
			phase = Phase.APPROACH;
			return StepResult.running();
		}
		return failure;
	}

	private StepResult breakBlock(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		if (current == null) {
			phase = Phase.APPROACH;
			return StepResult.running();
		}
		BlockState state = level.getBlockState(current);
		if (!matcher.test(state)) {
			release(ctx);
			phase = Phase.APPROACH;
			return StepResult.running();
		}
		if (!InventoryManager.hasRoomFor(ctx, target)) {
			InventoryManager.makeRoom(ctx);
			if (!InventoryManager.hasRoomFor(ctx, target)) {
				return StepResult.fail(StepResult.FailureCode.INVENTORY_FULL, Component.translatable("reason.autonomouscompanions.inventory_full"));
			}
		}
		if (!Gear.equipBestToolFor(c, state, level, silk)) {
			phase = Phase.TOOL;
			return StepResult.running();
		}
		c.setCompanionState(CompanionState.COLLECTING, Component.translatable("detail.autonomouscompanions.collecting", target.describe(), have(ctx), count));
		ctx.breaker().begin(current, state);
		BlockBreaker.Status st = ctx.breaker().tick(level, c);
		c.setActivityLevel(2);
		if (st == BlockBreaker.Status.BROKEN) {
			int before = have(ctx);
			boolean wasTreeBase = state.is(BlockTags.LOGS) && level.getBlockState(current.below()).is(BlockTags.DIRT);
			BlockBreaker.storeDrops(level, c, current, ctx.breaker().lastDrops());
			if (KnownResources.isInteresting(state)) {
				dev.autonomouscompanions.mind.memory.WorldMemory.get(level.getServer()).mined(level, state.getBlock(), current);
			}
			Order order = ctx.order();
			if (order != null) {
				order.progress().blocksMined++;
				if (missionItem) {
					order.progress().obtained += Math.max(0, have(ctx) - before);
				}
			}
			c.debug("Quebrou " + state.getBlock().getName().getString() + " em " + current.toShortString() + " (" + have(ctx) + "/" + count + ")");
			if (state.is(BlockTags.LOGS)) {
				queueRestOfTree(ctx, current);
			}
			if (wasTreeBase) {
				treeBases.add(current);
				tryPlantSapling(ctx, current);
			}
			brokenHere++;
			release(ctx);
			phase = Phase.APPROACH;
			if (candidates.isEmpty()) {
				phase = Phase.SEARCH;
			}
			if (ctx.brain().orders().isPillarBlock(c.blockPosition().below())) {
				return StepResult.push(new DismantlePillarStep());
			}
		} else if (st == BlockBreaker.Status.INVALID) {
			if (BlockBreaker.progressPerTick(level, c, current, state) <= 0) {
				markUnreachable(ctx, current);
			}
			release(ctx);
			phase = Phase.APPROACH;
		}
		return StepResult.running();
	}

	/**
	 * Depois de cortar um tronco, os troncos ligados a ele (a mesma árvore, inclusive o alto) vão para a frente da fila:
	 * ela termina a árvore — subindo num pilar se precisar — em vez de deixar o resto flutuando e ir procurar outra.
	 */
	private void queueRestOfTree(TaskContext ctx, BlockPos cut) {
		ServerLevel level = ctx.level();
		Cooperation coop = Cooperation.get(level.getServer());
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> open = new ArrayDeque<>();
		List<BlockPos> logs = new ArrayList<>();
		open.add(cut);
		seen.add(cut);
		while (!open.isEmpty() && logs.size() < 64) {
			BlockPos p = open.poll();
			for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1))) {
				if (seen.size() > 400 || Math.abs(q.getX() - cut.getX()) > 8 || Math.abs(q.getZ() - cut.getZ()) > 8 || !seen.add(q.immutable())) {
					continue;
				}
				BlockState s = level.getBlockState(q);
				if (!matcher.test(s)) {
					continue;
				}
				BlockPos im = q.immutable();
				open.add(im);
				if (!ctx.unreachable().contains(im) && !coop.isTargetClaimedByOther(ctx.companion().getUUID(), im) && !ctx.brain().orders().isPillarBlock(im)) {
					logs.add(im);
				}
			}
		}
		if (logs.isEmpty()) {
			return;
		}
		// De baixo para cima (o mais baixo primeiro na fila).
		logs.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
		candidates.removeAll(logs);
		for (BlockPos p : logs) {
			candidates.addFirst(p);
		}
	}

	private StepResult explore(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		if (origin == null) {
			origin = c.blockPosition();
		}
		double fromOrigin = horizontal(origin, c.blockPosition());
		if (exploreTarget == null && !memoryChecked) {
			// Antes de sair andando a esmo: o que ela lembra além do raio da busca, depois as áreas onde supõe haver o recurso.
			memoryChecked = true;
			BlockPos remembered = KnownResources.nearest(level, matcher, c.blockPosition(), range, ctx.unreachable());
			if (remembered != null && !Cooperation.get(level.getServer()).isTargetClaimedByOther(c.getUUID(), remembered)) {
				c.debug("Lembrei de " + level.getBlockState(remembered).getBlock().getName().getString() + " em " + remembered.toShortString() + " ("
					+ (int) Math.sqrt(remembered.distSqr(c.blockPosition())) + " blocos, fora do raio da busca): indo direto.");
				c.brain().metrics().memoryRecalled();
				candidates.clear();
				candidates.add(remembered);
				phase = Phase.APPROACH;
				return StepResult.running();
			}
			var region = dev.autonomouscompanions.mind.memory.WorldMemory.get(level.getServer()).bestRegion(level.dimension(), matcher, c.blockPosition(), range);
			if (region != null) {
				c.brain().metrics().memoryRecalled();
				hypothesis = region;
				exploreTarget = level.hasChunkAt(region.center()) ? region.center() : surfaceAt(level, region.center());
				exploreLeg++;
				c.debug(String.format("Hipótese: costuma ter %s perto de %s (%d observações, confiança %.2f); indo verificar.",
					target.describe().getString(), region.center().toShortString(), region.observations(), region.confidence()));
			}
		}
		if (exploreTarget == null) {
			if (exploreLeg >= MAX_EXPLORE_LEGS || (searchRadius >= range && fromOrigin >= range - 8)) {
				return StepResult.fail(StepResult.FailureCode.NOT_FOUND, Component.translatable("reason.autonomouscompanions.not_found", target.describe(), range));
			}
			exploreTarget = chooseExploreTarget(ctx);
			exploreLeg++;
			c.brain().metrics().explorationLeg();
			if (exploreTarget == null) {
				return StepResult.fail(StepResult.FailureCode.NOT_FOUND, Component.translatable("reason.autonomouscompanions.not_found", target.describe(), range));
			}
			c.debug("Explorando rumo a " + exploreTarget.toShortString() + " (etapa " + exploreLeg + ")");
		}
		c.setCompanionState(CompanionState.TRAVELING, Component.translatable("detail.autonomouscompanions.exploring_for", target.describe(), (int) fromOrigin, range));
		// Recursos subterrâneos: desce e escava galeria em vez de andar na superfície.
		// Indo verificar uma área lembrada: primeiro chega lá (as galerias vêm depois, na busca local).
		if (hypothesis == null && info.underground() && c.getY() > info.bestY() + 6 && c.settings().allowTerrainChanges) {
			BlockPos down = new BlockPos(c.getBlockX() + 12, info.bestY(), c.getBlockZ());
			if (exploreLeg % 2 == 0) {
				down = new BlockPos(c.getBlockX(), info.bestY(), c.getBlockZ() + 12);
			}
			exploreTarget = null;
			searchRadius = Math.min(range, 32);
			phase = Phase.SEARCH;
			return StepResult.push(new TunnelStep(down, 1.5, 160));
		}
		if (hypothesis == null && info.underground() && Math.abs(c.getY() - info.bestY()) <= 8 && c.settings().allowTerrainChanges) {
			// Galeria horizontal de 24 blocos na direção da etapa, depois nova busca.
			Direction dir = Direction.from2DDataValue(exploreLeg % 4);
			BlockPos goal = c.blockPosition().relative(dir, 24);
			exploreTarget = null;
			searchRadius = Math.min(range, 24);
			phase = Phase.SEARCH;
			return StepResult.push(new TunnelStep(goal, 1.0, 40));
		}
		Navigator nav = ctx.brain().navigator();
		nav.setGoal(exploreTarget, 3.0, 1.2);
		Navigator.Status status = nav.tick();
		if (status != Navigator.Status.MOVING) {
			exploreTarget = null;
			nav.clear();
			searchRadius = Math.min(range, 64);
			phase = Phase.SEARCH;
		}
		return StepResult.running();
	}

	private @Nullable BlockPos chooseExploreTarget(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		if (!biomeChecked && info.hasBiomeHint()) {
			biomeChecked = true;
			int radius = Math.min(range, 2048);
			var found = level.findClosestBiome3d(info::biomeMatches, c.blockPosition(), radius, 32, 64);
			if (found != null) {
				BlockPos p = found.getFirst();
				if (horizontal(p, c.blockPosition()) > 24) {
					c.debug("Bioma adequado encontrado em " + p.toShortString());
					return surfaceAt(level, p);
				}
			}
		}
		// Espiral crescente ao redor da origem.
		double angle = exploreLeg * (Math.PI / 2.5);
		double distance = Math.min(range, 48 + exploreLeg * 40);
		BlockPos land = dev.autonomouscompanions.nav.SafeSpot.landTarget(level, origin, angle, distance, c.getType());
		if (land != null) {
			return level.hasChunkAt(land) ? land : surfaceAt(level, land);
		}
		int x = Mth.floor(origin.getX() + Math.cos(angle) * distance);
		int z = Mth.floor(origin.getZ() + Math.sin(angle) * distance);
		return surfaceAt(level, new BlockPos(x, c.getBlockY(), z));
	}

	private static BlockPos surfaceAt(ServerLevel level, BlockPos p) {
		if (level.hasChunkAt(p)) {
			return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
		}
		return new BlockPos(p.getX(), Math.max(p.getY(), level.getSeaLevel()), p.getZ());
	}

	/** Primeiro bloco sólido entre os olhos e o alvo (nulo se o alvo estiver visível). */
	private static @Nullable BlockPos obstruction(ServerLevel level, CompanionEntity c, BlockPos target) {
		Vec3 from = c.getEyePosition();
		Vec3 to = Vec3.atCenterOf(target);
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, c));
		if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target)) {
			return null;
		}
		return hit.getBlockPos();
	}

	private void tryPlantSapling(TaskContext ctx, BlockPos base) {
		ServerLevel level = ctx.level();
		if (!level.getBlockState(base).isAir() || !level.getBlockState(base.below()).is(BlockTags.DIRT)) {
			return;
		}
		var inv = ctx.inventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof SaplingBlock && inv.countFree(s.getItem()) > 0) {
				BlockState sapling = bi.getBlock().defaultBlockState();
				if (sapling.canSurvive(level, base) && Stations.place(level, ctx.companion(), base, sapling)) {
					inv.removeFree(s.getItem(), 1);
					treeBases.remove(base);
					return;
				}
			}
		}
	}

	/** Replantio ao final: recolhe mudas próximas às bases das árvores derrubadas e planta onde fizer sentido. */
	private void replant(TaskContext ctx) {
		if (treeBases.isEmpty()) {
			return;
		}
		ServerLevel level = ctx.level();
		for (BlockPos base : List.copyOf(treeBases)) {
			if (ctx.companion().distanceToSqr(Vec3.atCenterOf(base)) > 24 * 24) {
				continue;
			}
			for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(base).inflate(6),
				e -> e.getItem().getItem() instanceof BlockItem bi && bi.getBlock() instanceof SaplingBlock)) {
				CollectItemsStep.pickUp(level, ctx.companion(), item);
			}
			tryPlantSapling(ctx, base);
		}
		treeBases.clear();
	}

	/**
	 * Alvo sem acesso: descarta também os candidatos do mesmo veio (até 4 blocos), como a jogadora desiste daquele
	 * ponto — antes ela tentava bloco por bloco do mesmo lugar inacessível, 5 s cada. Em árvores descarta só o tronco.
	 */
	private void markUnreachable(TaskContext ctx, BlockPos p) {
		ctx.unreachable().add(p.immutable());
		dev.autonomouscompanions.mind.memory.WorldMemory.get(ctx.level().getServer()).markUnreachable(ctx.companion().getUUID(), ctx.level().dimension(), p,
			ctx.level().getGameTime());
		int dropped = 0;
		if (!cuttingLogs()) {
			for (java.util.Iterator<BlockPos> it = candidates.iterator(); it.hasNext(); ) {
				BlockPos q = it.next();
				if (q.distSqr(p) <= 16) {
					ctx.unreachable().add(q);
					it.remove();
					dropped++;
				}
			}
		}
		ctx.companion().debug("Inalcançável: " + p.toShortString() + (dropped > 0 ? " (e mais " + dropped + " do mesmo veio)" : ""));
	}

	private void release(TaskContext ctx) {
		if (current != null) {
			Cooperation.get(ctx.level().getServer()).releaseTarget(ctx.companion().getUUID(), current);
		}
		current = null;
		ctx.breaker().reset(ctx.level(), ctx.companion());
	}

	/** Longe do alvo: corre (mais rápido); perto: anda (para não passar do ponto). */
	static double travelSpeed(CompanionEntity c, BlockPos target) {
		double d = Math.sqrt(c.distanceToSqr(Vec3.atCenterOf(target)));
		boolean run = d > 10 && !c.isInWater();
		c.setSprinting(run);
		return run ? 1.35 : 1.1;
	}

	/** Primeira posição livre com chão firme abaixo de um bloco alto (o pé da árvore), até 12 blocos para baixo. */
	private static BlockPos groundBelow(ServerLevel level, BlockPos p) {
		BlockPos.MutableBlockPos m = p.mutable();
		for (int i = 0; i < 12; i++) {
			m.move(Direction.DOWN);
			BlockState below = level.getBlockState(m.below());
			if (level.getBlockState(m).getCollisionShape(level, m).isEmpty() && below.isFaceSturdy(level, m.below(), Direction.UP)
				&& !below.is(BlockTags.LEAVES)) {
				return m.immutable();
			}
		}
		return p;
	}

	/** Coletando troncos: pode subir nos próprios troncos cortados (devolvidos ao desmontar o pilar). */
	private boolean cuttingLogs() {
		return sources.getFirst().state().is(BlockTags.LOGS);
	}

	private static double horizontal(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	@Override
	public void stop(TaskContext ctx) {
		release(ctx);
		ctx.companion().setSprinting(false);
		Cooperation.get(ctx.level().getServer()).releaseWorkArea(ctx.companion().getUUID());
		ctx.brain().navigator().clear();
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.mine", count, target.describe(), sources.getFirst().state().getBlock().getName());
	}

	@Override
	public CompanionState state() {
		return switch (phase) {
			case SEARCH -> CompanionState.SEARCHING;
			case EXPLORE -> CompanionState.TRAVELING;
			case TOOL -> CompanionState.CRAFTING_TOOLS;
			default -> CompanionState.COLLECTING;
		};
	}

	public static double unitCost(CostModel model, DropIndex.BlockSource s) {
		return model.mineSourceCost(s, 1);
	}
}
