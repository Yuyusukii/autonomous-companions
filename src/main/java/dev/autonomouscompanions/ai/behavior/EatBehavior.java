package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.survival.CompanionNeeds;
import dev.autonomouscompanions.survival.FoodHelper;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.task.exec.TaskRunner;
import dev.autonomouscompanions.task.exec.steps.ObtainStep;
import java.util.ArrayList;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Alimentação autônoma. Come a melhor comida livre do inventário (a da encomenda do dono fica reservada) usando o
 * fluxo real de consumo do item. Sem comida e com fome crítica, busca alimento por conta própria: planejador escolhe
 * entre colher, caçar e cozinhar. Fome leve não interrompe missões a cada oscilação: só quando há pausa segura.
 */
public final class EatBehavior extends Behavior {
	private boolean eating;
	private @Nullable TaskRunner foodRunner;
	private @Nullable TaskContext foodContext;
	private long retryAfter;

	public EatBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		if (!companion.isNamed()) {
			return 0;
		}
		CompanionNeeds needs = companion.needs();
		boolean critical = needs.isCriticallyHungry();
		boolean healing = FoodHelper.needsHealing(companion);
		if (brain.active() == this && (eating || foodRunner != null)) {
			return critical ? Priorities.CRITICAL_FOOD : healing ? Priorities.HEAL_EAT : Priorities.EAT;
		}
		if (brain.threats().target() != null && !critical) {
			return 0;
		}
		boolean hasFood = FoodHelper.bestFoodSlot(companion, critical) >= 0;
		if (critical && (hasFood || level.getGameTime() >= retryAfter)) {
			return Priorities.CRITICAL_FOOD;
		}
		// Ferida: comer é obrigatório (acima das ordens). Sem comida e sem regeneração possível (fome < 18), vai buscar.
		if (healing && hasFood) {
			return Priorities.HEAL_EAT;
		}
		// Sair para caçar comida só sem perigo por perto (antes ela saía com a espada na mão com um husk vindo na direção dela).
		boolean hostilesAround = brain.threats().hostilesNearSelf() > 0 || brain.threats().target() != null;
		if (healing && needs.hunger() < 18 && level.getGameTime() >= retryAfter && !brain.isHoldingNearOwner(level) && !hostilesAround) {
			return Priorities.HEAL_EAT;
		}
		if (needs.isHungry() && hasFood) {
			return Priorities.EAT;
		}
		return 0;
	}

	@Override
	public void start(ServerLevel level) {
		eating = false;
	}

	@Override
	public boolean tick(ServerLevel level) {
		CompanionNeeds needs = companion.needs();
		if (eating) {
			companion.setCompanionState(CompanionState.EATING, Component.translatable("detail.autonomouscompanions.eating"));
			companion.getNavigation().stop();
			if (companion.isUsingItem()) {
				return true;
			}
			eating = false;
			Gear.stowMainHand(companion);
		}
		boolean critical = needs.isCriticallyHungry();
		boolean healing = FoodHelper.needsHealing(companion);
		// Ferida: come até encher a fome (com a fome cheia a vida volta rápido); senão, até ficar satisfeita.
		boolean satisfied = healing ? needs.hunger() >= CompanionNeeds.MAX_HUNGER
			: needs.hunger() >= 18 || !critical && needs.hunger() > CompanionNeeds.HUNGRY + 3;
		if (satisfied) {
			return false;
		}
		if (FoodHelper.bestFoodSlot(companion, critical) >= 0) {
			foodRunner = null;
			eating = FoodHelper.startEating(companion, critical);
			return eating;
		}
		if (!critical && !(healing && needs.hunger() < 18)) {
			return false;
		}
		// Sem comida (fome crítica, ou ferida sem poder regenerar): busca alimento (caça, colheita, cozinha).
		companion.setCompanionState(CompanionState.SEEKING_FOOD, Component.translatable("detail.autonomouscompanions.seeking_food"));
		if (foodRunner == null) {
			Set<net.minecraft.world.item.Item> foods = FoodHelper.acceptableFoods();
			if (foods.isEmpty()) {
				return false;
			}
			foodContext = new TaskContext(companion, brain, null, new ArrayList<>());
			// Com animais por perto, carne é o caminho mais rápido (caçar e comer na hora); senão, qualquer comida.
			boolean animalsNear = !level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, companion.getBoundingBox().inflate(32, 8, 32),
				a -> a.isAlive() && !a.isBaby()).isEmpty();
			ItemTarget target = ItemTarget.anyOf(animalsNear ? SupplierBehavior.MEATS : foods);
			companion.debug("Buscando comida: " + (animalsNear ? "carne (há animais por perto)" : "qualquer comida"));
			foodRunner = new TaskRunner(new ObtainStep(target, target.count(companion, false) + 3, false, Set.of()));
		}
		foodContext.refresh(level);
		StepResult r = foodRunner.tick(foodContext);
		if (r.isFailure()) {
			companion.debug("Não conseguiu comida: " + (r.reason() != null ? r.reason().getString() : "?"));
			foodRunner = null;
			retryAfter = level.getGameTime() + 20 * 60;
			return false;
		}
		if (r.isSuccess()) {
			foodRunner = null;
		}
		return true;
	}

	@Override
	public void stop(ServerLevel level) {
		if (eating && companion.isUsingItem()) {
			companion.stopUsingItem();
		}
		if (eating) {
			Gear.stowMainHand(companion);
		}
		eating = false;
		if (foodRunner != null && foodContext != null) {
			foodRunner.pause(foodContext);
		}
	}
}
