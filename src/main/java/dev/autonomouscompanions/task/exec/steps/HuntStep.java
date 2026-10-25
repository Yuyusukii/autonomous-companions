package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.combat.CombatHelper;
import dev.autonomouscompanions.coop.Cooperation;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.mining.DropIndex;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.nav.SafeSpot;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Caça animais para obter itens (carne, couro, lã...). A quantidade-alvo é de itens, não de animais. Não ataca
 * filhotes, animais nomeados, presos por guia, domesticados ou mantidos em cercados (prováveis criações do jogador),
 * e evita matar os últimos animais de um grupo quando há alternativas.
 */
public final class HuntStep extends CompositeStep {
	private final Set<EntityType<?>> types = new HashSet<>();
	private final ItemTarget target;
	private final int count;
	private final boolean missionItem;
	private final Set<Item> chain;
	private @Nullable LivingEntity prey;
	private final int[] cooldown = {0};
	private int searchLegs;
	private @Nullable BlockPos wanderTarget;
	private @Nullable BlockPos lastKill;
	private boolean weaponTried;
	private int chaseTicks;
	private int repath;
	private int groundCollects;
	/** Ticks até a próxima busca de presa enquanto explora sem nenhuma à vista. */
	private int searchCooldown;
	/** Itens caídos sem caminho a pé: não são tentados de novo nesta caçada. */
	private final Set<java.util.UUID> unreachableDrops = new HashSet<>();
	private boolean noPreyReported;
	private  BlockPos plannedAt;
	/**
	 * Presas sem caminho a pé (atrás de cercas, muros, água funda...) e até quando ficam ignoradas: 30 s, porque animais
	 * andam — antes a marca valia a caçada inteira e ela descartava, uma a uma, ovelhas que logo ficavam alcançáveis.
	 */
	private final java.util.Map<java.util.UUID, Long> unreachablePrey = new java.util.HashMap<>();

	public HuntStep(List<DropIndex.EntitySource> sources, ItemTarget target, int count, boolean missionItem, Set<Item> chain) {
		for (DropIndex.EntitySource s : sources) {
			types.add(s.type());
		}
		this.target = target;
		this.count = count;
		this.missionItem = missionItem;
		this.chain = chain;
	}

	@Override
	protected boolean isOptional(dev.autonomouscompanions.task.exec.Step child) {
		return child instanceof ObtainStep;
	}

	public static boolean isProtectedLivestock(ServerLevel level, LivingEntity e) {
		if (e.hasCustomName() || e.isBaby()) {
			return true;
		}
		if (e instanceof net.minecraft.world.entity.Mob mob && mob.isLeashed()) {
			return true;
		}
		if (e instanceof OwnableEntity own && own.getOwnerReference() != null) {
			return true;
		}
		if (Cooperation.get(level.getServer()).isInsideProtectedArea(level, e.blockPosition())) {
			return true;
		}
		// Cercados: muitos blocos de cerca/muro ao redor indicam criação do jogador.
		BlockPos c = e.blockPosition();
		int fences = 0;
		for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, -1, -4), c.offset(4, 1, 4))) {
			var s = level.getBlockState(p);
			if (s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WALLS)) {
				if (++fences >= 4) {
					return true;
				}
			}
		}
		return false;
	}

	/** Presas já escolhidas por alguma companheira (presa → caçadora, até quando), para duas não correrem atrás do mesmo bicho. */
	private static final java.util.Map<java.util.UUID, java.util.Map.Entry<java.util.UUID, Long>> CLAIMS = new java.util.HashMap<>();

	private static boolean claimedByOther(ServerLevel level, java.util.UUID prey, java.util.UUID hunter) {
		var e = CLAIMS.get(prey);
		return e != null && !e.getKey().equals(hunter) && e.getValue() > level.getGameTime();
	}

	private static void claim(ServerLevel level, LivingEntity prey, java.util.UUID hunter) {
		if (CLAIMS.size() > 512) {
			CLAIMS.entrySet().removeIf(x -> x.getValue().getValue() <= level.getGameTime());
		}
		CLAIMS.put(prey.getUUID(), java.util.Map.entry(hunter, level.getGameTime() + 100));
	}

	private @Nullable LivingEntity findPrey(TaskContext ctx, double radius) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		List<Animal> animals = level.getEntitiesOfClass(Animal.class, new AABB(c.blockPosition()).inflate(radius, 16, radius),
			a -> a.isAlive() && types.contains(a.getType()) && DropIndex.isHuntable(a));
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		for (Animal a : animals) {
			if (isProtectedLivestock(level, a) || unreachablePrey.getOrDefault(a.getUUID(), 0L) > level.getGameTime()) {
				continue;
			}
			// Lã: só ovelhas da cor pedida e com lã (antes ela matava ovelhas marrons atrás de lã branca e nunca juntava nada).
			if (a instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep && wantsWool()
				&& (sheep.isSheared() || !target.items().contains(net.minecraft.world.item.Items.WOOL.pick(sheep.getColor())))) {
				continue;
			}
			long sameKind = animals.stream().filter(o -> o.getType() == a.getType() && o.distanceToSqr(a) < 24 * 24).count();
			// Prefere grupos maiores para não extinguir a população local; o animal que outra companheira já persegue fica por
			// último (elas se dividem em vez de correr as duas atrás do mesmo).
			double score = a.distanceToSqr(c) + (sameKind <= 2 ? 900 : 0) + (claimedByOther(level, a.getUUID(), c.getUUID()) ? 10_000 : 0);
			if (score < bestScore) {
				bestScore = score;
				best = a;
			}
		}
		return best;
	}

	private boolean wantsWool() {
		for (Item i : target.items()) {
			if (new net.minecraft.world.item.ItemStack(i).is(net.minecraft.tags.ItemTags.WOOL)) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected StepResult run(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		if (target.count(c, missionItem) >= count) {
			return StepResult.success();
		}
		if (!weaponTried) {
			weaponTried = true;
			Gear.equipBestWeapon(c);
			// Só se já tiver madeira: procurar árvores antes de caçar atrasa muito a carne (e animais não revidam).
			boolean hasWood = c.inventory().count(s -> s.is(net.minecraft.tags.ItemTags.LOGS) || s.is(net.minecraft.tags.ItemTags.PLANKS)) > 0;
			if (hasWood && Gear.attackDamage(c.mainHandItem()) < 4 && ctx.costs().unitCost(net.minecraft.world.item.Items.WOODEN_SWORD) < 10
				&& !chain.contains(net.minecraft.world.item.Items.WOODEN_SWORD)) {
				// Uma espada simples acelera muito a caça; opcional.
				return StepResult.push(new ObtainStep(ItemTarget.of(net.minecraft.world.item.Items.WOODEN_SWORD),
					c.inventory().countFree(net.minecraft.world.item.Items.WOODEN_SWORD) + 1, false, chain, false));
			}
		}
		if (lastKill != null) {
			BlockPos at = lastKill;
			lastKill = null;
			return StepResult.push(new CollectItemsStep(at, 5, 80, target::test, unreachableDrops));
		}
		if (missionItem || ctx.order() != null) {
			ctx.brain().chunkLoader().keepActive();
		}
		if (prey == null || !prey.isAlive()) {
			prey = null;
			// Sem presa à vista ela vai explorando e procura de novo a cada segundo: refazer a busca (até 96 blocos) e o
			// registro a cada tick custava processamento e enchia o log com milhares de linhas iguais.
			if (searchCooldown > 0) {
				searchCooldown--;
				return wander(ctx);
			}
			// Primeiro perto; se não houver, olha toda a área ativa (até 96) e vai até lá, em vez de vagar a esmo.
			prey = findPrey(ctx, 40);
			if (prey == null) {
				prey = findPrey(ctx, 96);
			}
			if (prey == null && groundCollects < 4) {
				// Antes de dar a caça por encerrada: itens do pedido caídos por perto (ex.: lã de uma ovelha morta pelo golpe de
				// varredura, que não era o alvo) são recolhidos.
				var drop = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, c.getBoundingBox().inflate(16, 6, 16),
					e -> e.isAlive() && !unreachableDrops.contains(e.getUUID()) && target.test(e.getItem())).stream().min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(c))).orElse(null);
				if (drop != null) {
					groundCollects++;
					c.debug("Caça: recolhendo " + drop.getItem().getHoverName().getString() + " caído a " + (int) c.distanceTo(drop) + " blocos.");
					return StepResult.push(new CollectItemsStep(drop.blockPosition(), 4, 120, target::test, unreachableDrops));
				}
			}
			if (prey == null) {
				searchCooldown = 20;
				if (!noPreyReported) {
					noPreyReported = true;
					c.debug("Caça (" + target.describe().getString() + "): presa nenhuma por perto, tenho " + target.count(c, missionItem) + "/" + count);
				}
				return wander(ctx);
			}
			noPreyReported = false;
			c.debug("Caça (" + target.describe().getString() + "): presa " + prey.getType().toShortString() + " a " + (int) c.distanceTo(prey) + " blocos, tenho " + target.count(c, missionItem) + "/" + count);
			repath = 0;
			chaseTicks = 0;
			// Só confere o caminho de perto (o A* tem limite de nós; de longe ela vai se aproximando e confere depois).
			if (!CombatHelper.inReach(c, prey) && c.distanceTo(prey) < 32) {
				net.minecraft.world.level.pathfinder.Path path = c.getNavigation().createPath(prey, 1);
				if (!dev.autonomouscompanions.nav.Paths.reaches(path, prey.blockPosition(), 2.5)) {
					// Não dá para chegar a pé: escolhe outra presa em vez de insistir.
					unreachablePrey.put(prey.getUUID(), level.getGameTime() + 600);
					prey = null;
					return StepResult.running();
				}
			}
			Gear.equipBestWeapon(c);
		}
		c.setCompanionState(CompanionState.HUNTING, Component.translatable("detail.autonomouscompanions.hunting", target.describe(), target.count(c, missionItem), count));
		if (++chaseTicks > 20 * 20) {
			unreachablePrey.put(prey.getUUID(), level.getGameTime() + 600); // 20 s sem alcançar: deixa esta de lado por um tempo
			prey = null;
			return StepResult.running();
		}
		Navigator nav = ctx.brain().navigator();
		claim(level, prey, c.getUUID());
		if (!CombatHelper.inReach(c, prey)) {
			// Persegue correndo quando a presa está longe (caça rápida). O caminho é refeito a cada meio segundo ou quando a
			// presa se afasta do ponto planejado — refazer a cada tick deixava ela indo e voltando sem sair do lugar.
			boolean far = c.distanceTo(prey) > 5 && !c.isInWater();
			c.setSprinting(far);
			if (--repath <= 0 || c.getNavigation().isDone() || plannedAt == null || plannedAt.distSqr(prey.blockPosition()) > 4) {
				c.getNavigation().moveTo(prey, far ? 1.45 : 1.25);
				plannedAt = prey.blockPosition();
				repath = 10;
			}
		} else {
			nav.clear();
		}
		boolean wasAlive = prey.isAlive();
		CombatHelper.tryAttack(level, c, prey, cooldown);
		if (wasAlive && !prey.isAlive()) {
			c.debug("Caça: abateu " + prey.getType().toShortString());
			lastKill = prey.blockPosition();
			prey = null;
		}
		return StepResult.running();
	}

	private StepResult wander(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		c.setCompanionState(CompanionState.SEARCHING, Component.translatable("detail.autonomouscompanions.searching_animals", target.describe()));
		Navigator nav = ctx.brain().navigator();
		if (wanderTarget == null) {
			if (++searchLegs > 12) {
				c.debug("Caça: nenhuma presa encontrada depois de 12 voltas; desistindo.");
				return StepResult.fail(StepResult.FailureCode.NOT_FOUND, Component.translatable("reason.autonomouscompanions.no_animals", target.describe()));
			}
			double angle = c.getRandom().nextDouble() * Math.PI * 2;
			double dist = 32 + searchLegs * 24;
			if (ctx.rangeCap() > 0) {
				dist = Math.min(dist, ctx.rangeCap());
			}
			// Só para terra firme: nunca escolhe um ponto dentro de rio, lago ou mar (animais de carne vivem em terra).
			wanderTarget = SafeSpot.landTarget(level, c.blockPosition(), angle, dist, c.getType());
			if (wanderTarget == null) {
				return StepResult.running();
			}
		}
		nav.setGoal(wanderTarget, 3.0, 1.15);
		Navigator.Status st = nav.tick();
		if (st != Navigator.Status.MOVING || c.tickCount % 40 == 0 && findPrey(ctx, 40) != null) {
			wanderTarget = null;
			searchCooldown = 0;
			nav.clear();
		}
		return StepResult.running();
	}

	@Override
	public void stop(TaskContext ctx) {
		ctx.brain().navigator().clear();
		ctx.companion().getNavigation().stop();
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.hunt", count, target.describe());
	}

	@Override
	public CompanionState state() {
		return CompanionState.HUNTING;
	}

	static double horizontal(BlockPos a, double x, double z) {
		return SafeSpot.horizontalDistance(a, x, z);
	}
}
