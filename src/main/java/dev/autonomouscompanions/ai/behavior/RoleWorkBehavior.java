package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.coop.Cooperation;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionRole;
import dev.autonomouscompanions.inventory.DeliveryService;
import dev.autonomouscompanions.mining.BlockBreaker;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.task.exec.TaskRunner;
import dev.autonomouscompanions.task.exec.steps.CollectItemsStep;
import dev.autonomouscompanions.task.exec.steps.ObtainStep;
import dev.autonomouscompanions.task.exec.steps.Stations;
import dev.autonomouscompanions.world.KnownResources;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Trabalho autônomo da especialização quando não há ordens. Cada função gera tarefas diferentes, sempre perto do dono
 * (dentro do raio de exploração ociosa): lenhadora mantém estoque de troncos e replanta; mineradora mantém pedregulho
 * e carvão; agricultora colhe e replanta plantações maduras; coletora recolhe drops soltos e entrega ao dono; guarda
 * patrulha ao redor; exploradora percorre pontos mais distantes registrando recursos; construtora mantém estoque de
 * pedregulho e troncos e (com as demais, se permitido) atende pedidos de suprimento de projetos de construção.
 */
public final class RoleWorkBehavior extends Behavior {
	private @Nullable TaskRunner runner;
	private @Nullable TaskContext context;
	private long nextCheck;
	private Component jobName = Component.empty();

	public RoleWorkBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		if (!companion.isNamed() || !companion.settings().roleWork || !ConfigManager.get().trabalhoAutonomoDasFuncoes
			|| companion.orders().active() != null || companion.settings().stay) {
			return 0;
		}
		if (companion.ownerInLevel() == null || brain.isHoldingNearOwner(level) && runner == null) {
			return 0;
		}
		if (runner != null) {
			return Priorities.ROLE_WORK;
		}
		if (level.getGameTime() < nextCheck) {
			return 0;
		}
		nextCheck = level.getGameTime() + 200;
		Step job = findJob(level);
		if (job == null) {
			return 0;
		}
		int cap = Math.min(companion.settings().idleRadius, ConfigManager.get().limiteExploracaoOciosa);
		context = new TaskContext(companion, brain, null, new ArrayList<>()).withRangeCap(Math.max(16, cap));
		runner = new TaskRunner(job);
		jobName = job.describe();
		return Priorities.ROLE_WORK;
	}

	private @Nullable Step findJob(ServerLevel level) {
		CompanionRole role = companion.role();
		var inv = companion.inventory();
		// Pedidos de suprimento de construções (cooperação).
		if (companion.settings().allowCooperation && ConfigManager.get().cooperacaoHabilitada
			&& (role == CompanionRole.BUILDER || role == CompanionRole.GATHERER || role == CompanionRole.NONE)) {
			Cooperation.SupplyRequest req = Cooperation.get(level.getServer()).takeRequest(companion);
			if (req != null) {
				return new SupplyStep(req);
			}
		}
		switch (role) {
			case LUMBERJACK -> {
				Set<Item> logs = tagItems(ItemTags.LOGS_THAT_BURN);
				ItemTarget t = ItemTarget.anyOf(logs);
				int have = t.count(companion, false);
				if (have < 32 && inv.emptySlots() > 4) {
					return new ObtainStep(t, have + 16, false, Set.of());
				}
			}
			case MINER -> {
				int cobble = inv.countFree(Items.COBBLESTONE);
				if (cobble < 32 && inv.emptySlots() > 4) {
					return new ObtainStep(ItemTarget.of(Items.COBBLESTONE), cobble + 16, false, Set.of());
				}
				int coal = inv.countFree(Items.COAL);
				if (coal < 12 && inv.emptySlots() > 4) {
					return new ObtainStep(ItemTarget.of(Items.COAL), coal + 6, false, Set.of());
				}
				int iron = inv.countFree(Items.RAW_IRON) + inv.countFree(Items.IRON_INGOT);
				if (iron < 6 && inv.emptySlots() > 4) {
					return new ObtainStep(ItemTarget.of(Items.RAW_IRON), inv.countFree(Items.RAW_IRON) + 3, false, Set.of());
				}
			}
			case FARMER -> {
				ServerPlayer owner = companion.ownerInLevel();
				if (owner != null && !matureCrops(level, owner.blockPosition()).isEmpty()) {
					return new FarmStep(owner.blockPosition());
				}
			}
			case GATHERER -> {
				ServerPlayer owner = companion.ownerInLevel();
				if (owner != null && !looseDrops(level, owner).isEmpty()) {
					return new CollectLootStep();
				}
			}
			case GUARD -> {
				return new PatrolStep();
			}
			case EXPLORER -> {
				return new ScoutStep();
			}
			case BUILDER -> {
				// Mantém um estoque básico de materiais de obra (pedregulho e troncos) para os projetos.
				int cobble = inv.countFree(Items.COBBLESTONE);
				if (cobble < 64 && inv.emptySlots() > 6) {
					return new ObtainStep(ItemTarget.of(Items.COBBLESTONE), cobble + 32, false, Set.of());
				}
				ItemTarget logs = ItemTarget.anyOf(tagItems(ItemTags.LOGS_THAT_BURN));
				int have = logs.count(companion, false);
				if (have < 16 && inv.emptySlots() > 6) {
					return new ObtainStep(logs, have + 16, false, Set.of());
				}
			}
			default -> {
			}
		}
		return null;
	}

	private static Set<Item> tagItems(net.minecraft.tags.TagKey<Item> tag) {
		Set<Item> set = new LinkedHashSet<>();
		BuiltInRegistries.ITEM.getTagOrEmpty(tag).forEach(h -> set.add(h.value()));
		return set;
	}

	@Override
	public boolean tick(ServerLevel level) {
		if (runner == null || context == null) {
			return false;
		}
		context.refresh(level);
		StepResult r = runner.tick(context);
		var current = runner.current();
		if (current != null && companion.tickCount % 10 == 0) {
			companion.setCompanionState(runner.currentState(), Component.translatable("detail.autonomouscompanions.role_work",
				Component.translatable(companion.role().translationKey()), current.describe()));
		}
		if (r.isSuccess() || r.isFailure()) {
			if (r.isFailure()) {
				companion.debug("Trabalho da função interrompido: " + (r.reason() != null ? r.reason().getString() : "?"));
				nextCheck = level.getGameTime() + 20 * 90;
			}
			runner = null;
			return false;
		}
		// Não se afasta demais do dono durante o trabalho autônomo.
		ServerPlayer owner = companion.ownerInLevel();
		if (owner == null || companion.distanceTo(owner) > ConfigManager.get().limiteExploracaoOciosa) {
			runner.abort(context);
			runner = null;
			return false;
		}
		return true;
	}

	@Override
	public void stop(ServerLevel level) {
		if (runner != null && context != null) {
			runner.abort(context);
		}
		runner = null;
	}

	// ------------------------------------------------------------------ agricultora

	private static List<BlockPos> matureCrops(ServerLevel level, BlockPos center) {
		List<BlockPos> list = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-16, -4, -16), center.offset(16, 4, 16))) {
			BlockState s = level.getBlockState(p);
			if (s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s)) {
				list.add(p.immutable());
				if (list.size() >= 48) {
					break;
				}
			}
		}
		return list;
	}

	/** Colhe plantações maduras e replanta com as sementes obtidas. */
	private final class FarmStep extends Step {
		private final Deque<BlockPos> crops;
		private @Nullable BlockPos current;

		FarmStep(BlockPos center) {
			this.crops = new ArrayDeque<>(matureCrops((ServerLevel) companion.level(), center));
		}

		@Override
		public StepResult tick(TaskContext ctx) {
			ServerLevel level = ctx.level();
			if (current == null) {
				current = crops.poll();
				if (current == null) {
					return StepResult.success();
				}
			}
			BlockState s = level.getBlockState(current);
			if (!(s.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(s)) {
				current = null;
				return StepResult.running();
			}
			Navigator nav = ctx.brain().navigator();
			if (nav.distanceTo(current) > 3.0) {
				nav.setGoal(current, 2.0, 1.0);
				if (nav.tick() == Navigator.Status.FAILED) {
					current = null;
					nav.clear();
				}
				return StepResult.running();
			}
			nav.clear();
			ctx.breaker().begin(current, s);
			if (ctx.breaker().tick(level, companion) == BlockBreaker.Status.BROKEN) {
				BlockBreaker.storeDrops(level, companion, current, ctx.breaker().lastDrops());
				Item seed = s.getBlock().asItem();
				if (seed != Items.AIR && ctx.inventory().countFree(seed) > 0 && s.getBlock().defaultBlockState().canSurvive(level, current)) {
					if (Stations.place(level, companion, current, s.getBlock().defaultBlockState())) {
						ctx.inventory().removeFree(seed, 1);
					}
				}
				current = null;
			}
			return StepResult.running();
		}

		@Override
		public Component describe() {
			return Component.translatable("step.autonomouscompanions.farm");
		}

		@Override
		public CompanionState state() {
			return CompanionState.COLLECTING;
		}
	}

	// ------------------------------------------------------------------ coletora

	private static List<ItemEntity> looseDrops(ServerLevel level, ServerPlayer owner) {
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(owner.blockPosition()).inflate(12),
			e -> e.isAlive() && !e.hasPickUpDelay() && e.getOwner() == null && e.getAge() > 60);
	}

	/** Recolhe drops soltos (não arremessados por jogadores) perto do dono e os entrega a ele. */
	private final class CollectLootStep extends Step {
		private final Map<Item, Integer> collected = new HashMap<>();
		private boolean collecting = true;

		@Override
		public StepResult tick(TaskContext ctx) {
			ServerPlayer owner = companion.ownerInLevel();
			if (owner == null) {
				return StepResult.success();
			}
			if (collecting) {
				List<ItemEntity> drops = looseDrops(ctx.level(), owner);
				if (drops.isEmpty() || ctx.inventory().emptySlots() <= 2) {
					collecting = false;
					return StepResult.running();
				}
				ItemEntity nearest = drops.stream().min((a, b) -> Double.compare(a.distanceToSqr(companion), b.distanceToSqr(companion))).get();
				if (companion.distanceTo(nearest) <= 1.6) {
					ItemStack before = nearest.getItem().copy();
					CollectItemsStep.pickUp(ctx.level(), companion, nearest);
					int taken = before.getCount() - (nearest.isAlive() ? nearest.getItem().getCount() : 0);
					if (taken > 0) {
						collected.merge(before.getItem(), taken, Integer::sum);
					}
				} else {
					companion.getNavigation().moveTo(nearest, 1.1);
				}
				return StepResult.running();
			}
			if (collected.isEmpty()) {
				return StepResult.success();
			}
			if (companion.distanceTo(owner) > 3) {
				companion.getNavigation().moveTo(owner, 1.2);
				return StepResult.running();
			}
			for (var e : List.copyOf(collected.entrySet())) {
				int given = DeliveryService.transfer(companion, e.getKey(), Math.min(e.getValue(), ctx.inventory().countFree(e.getKey())), owner);
				collected.remove(e.getKey());
				if (given < e.getValue()) {
					break;
				}
			}
			return StepResult.success();
		}

		@Override
		public Component describe() {
			return Component.translatable("step.autonomouscompanions.collect_loot");
		}

		@Override
		public CompanionState state() {
			return CompanionState.COLLECTING;
		}
	}

	// ------------------------------------------------------------------ guarda

	/** Patrulha em volta do dono por alguns pontos; o combate proativo fica a cargo do DefendBehavior. */
	private final class PatrolStep extends Step {
		private int points;
		private @Nullable BlockPos target;

		@Override
		public StepResult tick(TaskContext ctx) {
			ServerPlayer owner = companion.ownerInLevel();
			if (owner == null || points >= 4) {
				return StepResult.success();
			}
			if (target == null) {
				double angle = companion.getRandom().nextDouble() * Math.PI * 2;
				int r = 6 + companion.getRandom().nextInt(5);
				BlockPos column = owner.blockPosition().offset((int) (Math.cos(angle) * r), 0, (int) (Math.sin(angle) * r));
				target = dev.autonomouscompanions.nav.SafeSpot.findNear(ctx.level(), column, 0, 2, 3, companion.getType());
				points++;
				if (target == null) {
					return StepResult.running();
				}
			}
			Navigator nav = ctx.brain().navigator();
			nav.setGoal(target, 1.5, 0.9);
			if (nav.tick() != Navigator.Status.MOVING) {
				target = null;
				nav.clear();
			}
			return StepResult.running();
		}

		@Override
		public Component describe() {
			return Component.translatable("step.autonomouscompanions.patrol");
		}

		@Override
		public CompanionState state() {
			return CompanionState.EXPLORING;
		}
	}

	/**
	 * Exploradora: percorre pontos mais distantes ao redor do dono (sempre dentro do limite de exploração ociosa) e
	 * registra os recursos expostos que encontra, o que acelera missões futuras de todas as companheiras.
	 */
	private final class ScoutStep extends Step {
		private int points;
		private int observed;
		private @Nullable BlockPos target;
		private int travelTicks;

		@Override
		public StepResult tick(TaskContext ctx) {
			ServerPlayer owner = companion.ownerInLevel();
			if (owner == null || points >= 3) {
				return StepResult.success();
			}
			if (target == null) {
				int cap = Math.max(16, Math.min(companion.settings().idleRadius, ConfigManager.get().limiteExploracaoOciosa));
				double angle = companion.getRandom().nextDouble() * Math.PI * 2;
				int r = Math.max(12, cap / 2) + companion.getRandom().nextInt(Math.max(1, cap / 2 - 4));
				BlockPos column = owner.blockPosition().offset((int) (Math.cos(angle) * r), 0, (int) (Math.sin(angle) * r));
				points++;
				travelTicks = 0;
				if (!ctx.level().hasChunkAt(column)) {
					return StepResult.running();
				}
				target = dev.autonomouscompanions.nav.SafeSpot.findNear(ctx.level(), column, 0, 6, 4, companion.getType());
				if (target == null) {
					return StepResult.running();
				}
			}
			Navigator nav = ctx.brain().navigator();
			nav.setGoal(target, 2.5, 1.0);
			Navigator.Status status = nav.tick();
			if (++travelTicks % 40 == 0) {
				KnownResources.observeAround(ctx.level(), companion.blockPosition(), 6);
				observed++;
			}
			if (status != Navigator.Status.MOVING || travelTicks > 20 * 60) {
				KnownResources.observeAround(ctx.level(), companion.blockPosition(), 8);
				target = null;
				nav.clear();
			}
			return StepResult.running();
		}

		@Override
		public Component describe() {
			return Component.translatable("step.autonomouscompanions.scout", points, observed);
		}

		@Override
		public CompanionState state() {
			return CompanionState.EXPLORING;
		}
	}

	// ------------------------------------------------------------------ cooperação

	/** Obtém materiais pedidos por outra companheira (ex.: construtora) e os entrega a ela. */
	private final class SupplyStep extends Step {
		private final Cooperation.SupplyRequest request;
		private boolean obtained;

		SupplyStep(Cooperation.SupplyRequest request) {
			this.request = request;
		}

		@Override
		public StepResult tick(TaskContext ctx) {
			Cooperation coop = Cooperation.get(ctx.level().getServer());
			if (!coop.isOpen(request)) {
				return StepResult.success();
			}
			ItemTarget t = ItemTarget.of(request.item());
			if (!obtained) {
				if (t.count(companion, false) >= request.remaining()) {
					obtained = true;
				} else {
					return StepResult.push(new ObtainStep(t, request.remaining(), false, Set.of()));
				}
			}
			CompanionEntity requester = coop.requesterEntity(ctx.level(), request);
			if (requester == null) {
				coop.abandon(request, companion);
				return StepResult.success();
			}
			if (companion.distanceTo(requester) > 3) {
				ctx.brain().navigator().setGoal(requester.blockPosition(), 2.0, 1.2);
				if (ctx.brain().navigator().tick() == Navigator.Status.FAILED) {
					coop.abandon(request, companion);
					return StepResult.success();
				}
				return StepResult.running();
			}
			int moved = coop.handOver(companion, requester, request);
			companion.debug("Entregou " + moved + "x " + request.item() + " para a construção.");
			return StepResult.success();
		}

		@Override
		public void onChildFinished(TaskContext ctx, Step child, StepResult result) {
			if (result.isFailure()) {
				Cooperation.get(ctx.level().getServer()).abandon(request, companion);
			}
		}

		@Override
		public Component describe() {
			return Component.translatable("step.autonomouscompanions.supply", request.remaining(), new ItemStack(request.item()).getHoverName());
		}

		@Override
		public CompanionState state() {
			return CompanionState.COLLECTING;
		}
	}
}
