package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.registry.ModEntities;
import dev.autonomouscompanions.survival.FoodHelper;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.task.exec.TaskRunner;
import dev.autonomouscompanions.task.exec.steps.ObtainStep;
import dev.autonomouscompanions.task.exec.steps.SupplyCompanionStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Trabalho da ajudante: servir as três companheiras da mesma dona.
 * <ol>
 *   <li><b>Comida urgente</b>: quem está com fome ou ferida e sem comida recebe carne o quanto antes (ela caça na
 *       hora se não tiver estoque);</li>
 *   <li><b>Itens de pedra</b>: quem não tem picareta, machado, pá ou espada de pedra (ou melhor) — ou está com a
 *       ferramenta quase quebrando — recebe uma de pedra fabricada por ela;</li>
 *   <li><b>Reposição</b>: quem tem pouca comida recebe mais;</li>
 *   <li><b>Estoque</b>: sem pedidos, ela mantém carne guardada para a próxima vez.</li>
 * </ol>
 * Cada entrega que falha espera um tempo antes de ser tentada de novo, para não repetir o mesmo erro sem parar.
 */
public final class SupplierBehavior extends Behavior {
	/** Carnes que ela caça (cruas ou assadas); frango cru fica de fora por causar fome. */
	public static final Set<Item> MEATS = Set.of(Items.BEEF, Items.COOKED_BEEF, Items.PORKCHOP, Items.COOKED_PORKCHOP, Items.MUTTON, Items.COOKED_MUTTON,
		Items.COOKED_CHICKEN, Items.RABBIT, Items.COOKED_RABBIT);
	/** Abaixo disto, a companheira recebe reposição de comida. */
	public static final int FOOD_LOW = 4;
	/** Quanto a ajudante mantém guardado de carne quando não há pedidos. */
	public static final int STOCK = 8;
	/** Ferramenta com menos que esta fração de durabilidade é trocada. */
	private static final double WORN = 0.12;
	/** Só começa um serviço a até esta distância da dona. */
	static final int START_RADIUS = 40;
	/** Larga o serviço e volta se passar desta distância da dona. */
	static final int WORK_RADIUS = 80;

	/** Tipos de ferramenta/arma que ela garante a cada companheira, com a versão de pedra correspondente. */
	private record Kit(TagKey<Item> tag, Item stone, Item wooden, Item golden) {
	}

	private static final List<Kit> KITS = List.of(
		new Kit(ItemTags.PICKAXES, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE, Items.GOLDEN_PICKAXE),
		new Kit(ItemTags.SWORDS, Items.STONE_SWORD, Items.WOODEN_SWORD, Items.GOLDEN_SWORD),
		new Kit(ItemTags.AXES, Items.STONE_AXE, Items.WOODEN_AXE, Items.GOLDEN_AXE),
		new Kit(ItemTags.SHOVELS, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL, Items.GOLDEN_SHOVEL));

	private record Job(String key, @Nullable CompanionEntity target, ItemTarget items, int count, int priority) {
	}

	private final Map<String, Long> failedUntil = new HashMap<>();
	private @Nullable Job pending;
	private @Nullable Job current;
	private @Nullable TaskRunner runner;
	private @Nullable TaskContext context;
	private long nextScan;

	public SupplierBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		if (companion.guardsAllies()) {
			return 0; // como segurança das outras, ela não abastece mais ninguém ("é só isso que ela faz")
		}
		if (current != null && runner != null) {
			return current.priority();
		}
		long now = level.getGameTime();
		if (now < nextScan) {
			return pending != null ? pending.priority() : 0;
		}
		nextScan = now + 30;
		pending = pickJob(level, now);
		return pending != null ? pending.priority() : 0;
	}

	/** As três companheiras da mesma dona, nesta dimensão. */
	public static List<CompanionEntity> served(ServerLevel level, CompanionEntity helper) {
		if (helper.ownerUUID() == null) {
			return List.of();
		}
		return List.copyOf(level.getEntities(ModEntities.COMPANION, c -> c.isAlive() && helper.ownerUUID().equals(c.ownerUUID())));
	}

	private @Nullable Job pickJob(ServerLevel level, long now) {
		// Longe da dona: primeiro volta (o acompanhamento a traz); só pega serviço perto dela.
		if (ownerDistance() > START_RADIUS) {
			return null;
		}
		List<CompanionEntity> list = new ArrayList<>(served(level, companion));
		// Atende quem está por perto da dona (quem saiu em expedição longe é atendida quando voltar).
		var owner = companion.ownerInLevel();
		list.removeIf(c -> owner == null || Math.sqrt(c.distanceToSqr(owner.getX(), c.getY(), owner.getZ())) > WORK_RADIUS - 10);
		// Quem está pior primeiro: menos vida, depois mais fome.
		list.sort(Comparator.comparingDouble((CompanionEntity c) -> c.getHealth() / c.getMaxHealth()).thenComparingInt(c -> c.needs().hunger()));
		ItemTarget foods = ItemTarget.anyOf(deliverableFoods());
		// 1) Comida urgente.
		for (CompanionEntity c : list) {
			boolean needy = c.needs().isHungry() || c.getHealth() < c.getMaxHealth() * 0.6f;
			if (needy && FoodHelper.freeFoodCount(c) == 0) {
				// Urgente: o suficiente para comer já (3); a reposição completa vem depois, sem deixá-la esperando.
				Job job = new Job("food:" + c.getUUID(), c, foods, 3, Priorities.SUPPLY_URGENT);
				if (allowed(job, now)) {
					return job;
				}
			}
		}
		// 2) Itens de pedra que faltam.
		for (CompanionEntity c : list) {
			for (Kit kit : KITS) {
				if (!hasGood(c, kit)) {
					Job job = new Job("tool:" + kit.stone() + ":" + c.getUUID(), c, ItemTarget.of(kit.stone()), 1, Priorities.SUPPLY);
					if (allowed(job, now)) {
						return job;
					}
				}
			}
		}
		// 3) Reposição de comida.
		for (CompanionEntity c : list) {
			int have = FoodHelper.freeFoodCount(c);
			if (have < FOOD_LOW) {
				Job job = new Job("food:" + c.getUUID(), c, foods, 6 - have, Priorities.SUPPLY);
				if (allowed(job, now)) {
					return job;
				}
			}
		}
		// 4) Estoque próprio de carne.
		ItemTarget meats = ItemTarget.anyOf(MEATS);
		if (meats.count(companion, false) < STOCK) {
			Job job = new Job("stock", null, meats, STOCK, Priorities.SUPPLY_STOCK);
			if (allowed(job, now)) {
				return job;
			}
		}
		return null;
	}

	/** Comidas que ela pode entregar: as carnes e qualquer comida comum que tiver (nunca as raras ou nocivas). */
	private Set<Item> deliverableFoods() {
		Set<Item> set = new LinkedHashSet<>(MEATS);
		for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
			ItemStack s = companion.inventory().getItem(i);
			if (FoodHelper.score(s, false) > 0) {
				set.add(s.getItem());
			}
		}
		return set;
	}

	/** Tem uma ferramenta deste tipo de pedra ou melhor (não de madeira nem de ouro) e ainda em bom estado? */
	public static boolean hasGood(CompanionEntity c, TagKey<Item> tag, Item wooden, Item golden) {
		List<ItemStack> owned = new ArrayList<>();
		owned.add(c.mainHandItem());
		owned.add(c.getItemBySlot(EquipmentSlot.OFFHAND));
		for (int i = 0; i < c.inventory().getContainerSize(); i++) {
			owned.add(c.inventory().getItem(i));
		}
		for (ItemStack s : owned) {
			if (s.isEmpty() || !s.is(tag) || s.is(wooden) || s.is(golden)) {
				continue;
			}
			int max = s.getOrDefault(DataComponents.MAX_DAMAGE, 0);
			if (max <= 0 || (max - s.getDamageValue()) >= max * WORN) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasGood(CompanionEntity c, Kit kit) {
		return hasGood(c, kit.tag(), kit.wooden(), kit.golden());
	}

	/** Distância horizontal até a dona (infinita se ela não estiver nesta dimensão). */
	private double ownerDistance() {
		var owner = companion.ownerInLevel();
		if (owner == null) {
			return Double.MAX_VALUE;
		}
		return Math.sqrt(companion.distanceToSqr(owner.getX(), companion.getY(), owner.getZ()));
	}

	private boolean allowed(Job job, long now) {
		Long until = failedUntil.get(job.key());
		return until == null || now >= until;
	}

	@Override
	public void start(ServerLevel level) {
		current = pending;
		pending = null;
		if (current == null) {
			return;
		}
		// Estoque: só procura perto (não some pelo mundo); entregas urgentes podem ir um pouco mais longe.
		context = new TaskContext(companion, brain, null, new ArrayList<>()).withRangeCap(current.target() == null ? 40 : 64);
		runner = new TaskRunner(current.target() == null
			? new ObtainStep(current.items(), current.count(), false, Set.of())
			: new SupplyCompanionStep(current.target(), current.items(), current.count()));
		companion.debug("Ajudante: " + current.key() + " (" + current.count() + ")");
	}

	@Override
	public boolean tick(ServerLevel level) {
		if (runner == null || context == null || current == null) {
			return false;
		}
		// Enquanto trabalha, mantém o entorno ativo (sem isso ela congelava ao sair da área ativa ao redor da dona).
		brain.chunkLoader().keepActive();
		if (ownerDistance() > WORK_RADIUS) {
			companion.debug("Ajudante: longe demais da dona (" + (int) ownerDistance() + " blocos); largando " + current.key() + " e voltando.");
			failedUntil.put(current.key(), level.getGameTime() + 20 * 20);
			if (runner != null && context != null) {
				runner.abort(context);
			}
			finish();
			return false;
		}
		context.refresh(level);
		StepResult r = runner.tick(context);
		if (r.isFailure()) {
			companion.debug("Ajudante: não conseguiu " + current.key() + ": " + (r.reason() != null ? r.reason().getString() : "?"));
			failedUntil.put(current.key(), level.getGameTime() + 20 * 45);
			finish();
			return false;
		}
		if (r.isSuccess()) {
			finish();
			nextScan = 0;
			return false;
		}
		return true;
	}

	private void finish() {
		runner = null;
		context = null;
		current = null;
		Gear.stowMainHand(companion);
	}

	@Override
	public void stop(ServerLevel level) {
		if (runner != null && context != null) {
			runner.abort(context);
		}
		runner = null;
		context = null;
		current = null;
	}
}
