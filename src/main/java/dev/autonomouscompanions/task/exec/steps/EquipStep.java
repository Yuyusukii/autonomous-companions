package dev.autonomouscompanions.task.exec.steps;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.crafting.CostModel;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Preparação de equipamento proporcional ao risco: escolhe arma, armaduras e escudo entre todos os itens registrados,
 * comparando o ganho real (dano/proteção pelos atributos do item) com o custo de produção estimado pelo planejador.
 * Nunca fabrica peças redundantes se já possuir equipamento igual ou melhor.
 */
public final class EquipStep extends CompositeStep {
	public enum Risk {
		LIGHT(14, 0, 0),
		MODERATE(45, 60, 40),
		HEAVY(90, 110, 55);

		final double weaponBudget;
		final double armorBudget;
		final double shieldBudget;

		Risk(double weaponBudget, double armorBudget, double shieldBudget) {
			this.weaponBudget = weaponBudget;
			this.armorBudget = armorBudget;
			this.shieldBudget = shieldBudget;
		}
	}

	/** Flechas que ela procura manter quando se prepara para lutar. */
	private static final int ARROWS = 16;

	private final Risk risk;
	private final Deque<Item> queue = new ArrayDeque<>();
	private boolean planned;
	private @Nullable Item pending;

	public EquipStep(Risk risk) {
		this.risk = risk;
	}

	@Override
	protected boolean isOptional(Step child) {
		return true; // cada peça é opcional: falhar uma não impede as outras
	}

	private void plan(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		CostModel costs = ctx.costs();
		// Cada escolha guarda o custo estimado: as peças baratas (ex.: com material já no inventário) são feitas primeiro e
		// as caras (ex.: que exigem procurar diamantes) por último, para que ela fique protegida o quanto antes.
		List<Map.Entry<Item, Double>> picks = new ArrayList<>();
		// Arma: uma melhoria acessível agora e, se o risco justificar, a melhor dentro do orçamento.
		double currentWeapon = Gear.weaponScore(c.mainHandItem());
		for (int i = 0; i < c.inventory().getContainerSize(); i++) {
			currentWeapon = Math.max(currentWeapon, Gear.weaponScore(c.inventory().getItem(i)));
		}
		Item cheapWeapon = null;
		double cheapScore = currentWeapon + 0.75;
		Item bestWeapon = null;
		double bestScore = currentWeapon + 0.75;
		double cheapCost = 0;
		double bestCost = 0;
		for (Item item : BuiltInRegistries.ITEM) {
			ItemStack s = new ItemStack(item);
			if (!s.has(DataComponents.WEAPON) && !s.has(DataComponents.TOOL)) {
				continue;
			}
			double score = Gear.weaponScore(s);
			if (score <= Math.min(cheapScore, bestScore)) {
				continue;
			}
			double cost = costs.unitCost(item);
			if (cost > risk.weaponBudget) {
				continue;
			}
			if (cost <= risk.weaponBudget * 0.35 && score > cheapScore) {
				cheapScore = score;
				cheapWeapon = item;
				cheapCost = cost;
			}
			if (score > bestScore) {
				bestScore = score;
				bestWeapon = item;
				bestCost = cost;
			}
		}
		if (cheapWeapon != null) {
			picks.add(Map.entry(cheapWeapon, cheapCost));
		}
		if (bestWeapon != null && bestWeapon != cheapWeapon && bestScore > cheapScore + 0.75) {
			picks.add(Map.entry(bestWeapon, bestCost));
		}
		// Armaduras (peitoral primeiro: maior proteção por material).
		if (risk.armorBudget > 0) {
			for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.HEAD, EquipmentSlot.FEET}) {
				double current = Gear.armorValue(c.getItemBySlot(slot), slot);
				for (int i = 0; i < c.inventory().getContainerSize(); i++) {
					ItemStack s = c.inventory().getItem(i);
					if (Gear.armorSlot(s) == slot) {
						current = Math.max(current, Gear.armorValue(s, slot));
					}
				}
				Item best = null;
				double bestArmor = current + 0.5;
				double bestArmorCost = Double.MAX_VALUE;
				for (Item item : BuiltInRegistries.ITEM) {
					ItemStack s = new ItemStack(item);
					if (Gear.armorSlot(s) != slot) {
						continue;
					}
					double value = Gear.armorValue(s, slot);
					if (value < bestArmor) {
						continue;
					}
					double cost = costs.unitCost(item);
					if (cost > risk.armorBudget) {
						continue;
					}
					if (value > bestArmor + 0.01 || cost < bestArmorCost) {
						bestArmor = value;
						bestArmorCost = cost;
						best = item;
					}
				}
				if (best != null) {
					picks.add(Map.entry(best, bestArmorCost));
				}
			}
		}
		// Escudo.
		if (risk.shieldBudget > 0 && Gear.countOwned(c, s -> s.is(Items.SHIELD)) == 0) {
			double cost = costs.unitCost(Items.SHIELD);
			if (cost <= risk.shieldBudget) {
				picks.add(Map.entry(Items.SHIELD, cost));
			}
		}
		// Arco e flechas: para lutar à distância quando ferida, contra creepers e contra alvos fora do alcance a pé.
		if (risk.shieldBudget > 0) {
			if (Gear.countOwned(c, s -> s.is(Items.BOW)) == 0) {
				double cost = costs.unitCost(Items.BOW);
				if (cost <= risk.shieldBudget * 1.5) {
					picks.add(Map.entry(Items.BOW, cost));
				}
			}
			if (dev.autonomouscompanions.combat.Archery.arrows(c) < ARROWS) {
				double cost = costs.unitCost(Items.ARROW);
				if (cost * ARROWS / 4.0 <= risk.shieldBudget * 1.5) {
					picks.add(Map.entry(Items.ARROW, cost + 1));
				}
			}
		}
		picks.sort(Map.Entry.comparingByValue());
		for (var e : picks) {
			queue.add(e.getKey());
		}
		c.debug("Plano de equipamento (" + risk + "): " + picks);
	}

	@Override
	protected StepResult run(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		if (!planned) {
			planned = true;
			plan(ctx);
		}
		while (!queue.isEmpty()) {
			Item next = queue.peek();
			if (pending == next) {
				// Já tentou obter: segue em frente (com ou sem sucesso).
				queue.poll();
				pending = null;
				// Veste/empunha cada peça assim que fica pronta (proteção progressiva).
				Gear.equipBestArmor(c);
				Gear.equipBestWeapon(c);
				continue;
			}
			pending = next;
			int want = next == Items.ARROW ? Math.max(ARROWS, c.inventory().countFree(next) + 4) : c.inventory().countFree(next) + 1;
			return StepResult.push(new ObtainStep(ItemTarget.of(next), want, false, Set.of(), true));
		}
		Gear.equipBestArmor(c);
		Gear.equipBestWeapon(c);
		return StepResult.success();
	}

	@Override
	public Component describe() {
		return Component.translatable("step.autonomouscompanions.equip");
	}

	@Override
	public CompanionState state() {
		return CompanionState.CRAFTING_EQUIPMENT;
	}
}
