package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.combat.Archery;
import dev.autonomouscompanions.combat.CombatHelper;
import dev.autonomouscompanions.combat.ThreatTracker;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Combate tático. Defende a dona (prioridade alta), revida quando atacada e, para guardas, ataca hostis próximos.
 * <p>
 * <b>Corpo a corpo</b>: chega correndo (golpe de corrida empurra mais), pula para acertar caindo (crítico, +50%),
 * golpeia só com a arma carregada, entre um golpe e outro anda de lado e recua um pouco (os golpes do inimigo erram
 * mais) e levanta o escudo quando o inimigo está colado; contra escudo troca para o machado (que desativa o escudo);
 * espada parada faz varredura nos inimigos colados no alvo.
 * <p>
 * <b>Arco</b>: usado quando faz mais sentido do que a espada — vida baixa (não quer trocar golpes), creepers (nunca
 * chega perto), alvos voando, na água ou fora do alcance a pé, e atiradores distantes. Mantém distância (recua quando o
 * alvo se aproxima, anda de lado para não ser acertada), puxa o arco até a força total e mira antecipando o movimento.
 */
public final class DefendBehavior extends Behavior {
	private final int[] cooldown = {0};
	private int strafeDir = 1;
	private int strafeTimer;
	private int retreatTicks;
	private boolean ranged;
	private int modeTimer;
	private int weaponTimer;
	private boolean unreachable;
	private int reachTimer;
	private boolean sprintHit;
	private int airTicks;
	private int noSightTicks;

	public DefendBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		if (!companion.isNamed()) {
			return 0;
		}
		ThreatTracker t = brain.threats();
		LivingEntity target = t.target();
		if (target == null || target instanceof Warden) {
			return 0;
		}
		int bonus = companion.role().combatPriorityBonus;
		return switch (t.kind()) {
			case OWNER_ATTACKED -> Priorities.DEFEND_OWNER + bonus;
			case SELF_ATTACKED -> Priorities.SELF_DEFENSE + bonus;
			case PROACTIVE -> Priorities.PROACTIVE_DEFENSE + bonus;
			case NONE -> 0;
		};
	}

	@Override
	public void start(ServerLevel level) {
		if (companion.isSleeping()) {
			companion.stopSleeping();
		}
		Gear.equipBestArmor(companion);
		cooldown[0] = 0;
		reachTimer = 0;
		unreachable = false;
		// Decide já na entrada entre arco e arma corpo a corpo (nunca puxa a espada para depois trocar).
		LivingEntity target = brain.threats().target();
		ranged = target != null && shouldUseBow(target) && Archery.equipBow(companion);
		if (!ranged) {
			Gear.equipBestWeapon(companion);
		}
		modeTimer = 10;
		companion.debug("Combate: início com " + (ranged ? "arco" : "arma corpo a corpo") + " (vida " + companion.getHealth() + ", arco pronto " + Archery.ready(companion) + ", alvo " + (target == null ? "nenhum" : target.getType().toShortString()) + ")");
	}

	@Override
	public boolean tick(ServerLevel level) {
		LivingEntity target = brain.threats().target();
		if (target == null) {
			return false;
		}
		ServerPlayer owner = companion.ownerInLevel();
		boolean protecting = brain.threats().kind() == ThreatTracker.Kind.OWNER_ATTACKED;
		companion.setActivityLevel(3);
		// Não persegue para longe da dona.
		if (owner != null && target.distanceToSqr(owner) > 28 * 28 && companion.distanceToSqr(owner) > 20 * 20) {
			brain.threats().clearTarget();
			return false;
		}
		companion.getLookControl().setLookAt(target, 30.0F, 30.0F);
		if (--reachTimer <= 0) {
			reachTimer = 20;
			unreachable = !canWalkTo(target);
		}
		if (--modeTimer <= 0) {
			modeTimer = 10;
			boolean wantRanged = shouldUseBow(target);
			if (wantRanged != ranged) {
				ranged = wantRanged;
				companion.stopUsingItem();
				if (ranged) {
					ranged = Archery.equipBow(companion);
				} else {
					Gear.equipBestWeapon(companion);
				}
				companion.debug(ranged ? "Combate: trocou para o arco." : "Combate: trocou para a arma corpo a corpo.");
			}
		}
		companion.setCompanionState(protecting ? CompanionState.PROTECTING_OWNER : CompanionState.COMBAT,
			Component.translatable(ranged ? "detail.autonomouscompanions.fighting_bow" : "detail.autonomouscompanions.fighting", target.getDisplayName()));
		if (eatCooldown > 0) {
			eatCooldown--;
		}
		if (recovering || shouldRecover(target)) {
			tickRecover(level, target);
			return true;
		}
		if (ranged) {
			tickRanged(level, target);
		} else {
			tickMelee(level, target);
		}
		return true;
	}

	// ------------------------------------------------------------------ recuar e comer

	/** Com pouca vida e comida na bolsa: abre distância e come, em vez de trocar golpes até cair. */
	private boolean recovering;
	private int recoverTicks;
	private int eatCooldown;

	private boolean shouldRecover(LivingEntity target) {
		if (eatCooldown > 0 || companion.getHealth() > companion.getMaxHealth() * 0.35f || target instanceof Creeper creeper && creeper.getSwellDir() > 0) {
			return false;
		}
		return dev.autonomouscompanions.survival.FoodHelper.bestFoodSlot(companion, true) >= 0;
	}

	private void tickRecover(ServerLevel level, LivingEntity target) {
		double dist = companion.distanceTo(target);
		Vec3 away = companion.position().subtract(target.position()).normalize();
		if (away.lengthSqr() < 1.0E-4) {
			away = new Vec3(1, 0, 0);
		}
		Vec3 to = companion.position().add(away.scale(8)).add(away.yRot((float) Math.PI / 2).scale(strafeDir * 2.0));
		if (!recovering) {
			recovering = true;
			startedEating = false;
			recoverTicks = 0;
			companion.stopUsingItem();
			companion.debug("Combate: vida baixa (" + companion.getHealth() + "); recuando para comer.");
		}
		recoverTicks++;
		companion.setCompanionState(CompanionState.EATING, Component.translatable("detail.autonomouscompanions.eating"));
		if (startedEating) {
			if (!companion.isUsingItem() || recoverTicks > 200) {
				finishRecover(); // terminou de comer (ou algo deu errado): volta à luta
				return;
			}
			// Comendo: continua se afastando devagar (como a jogadora andando enquanto come).
			companion.setSprinting(false);
			companion.getNavigation().moveTo(to.x, to.y, to.z, 1.0);
			return;
		}
		// Primeiro abre distância (até ~5 blocos ou 2 s), depois começa a comer.
		if (dist >= 5.0 || recoverTicks > 40) {
			startedEating = dev.autonomouscompanions.survival.FoodHelper.startEating(companion, true);
			if (!startedEating) {
				finishRecover();
			}
			return;
		}
		companion.setSprinting(true);
		companion.getNavigation().moveTo(to.x, to.y, to.z, 1.5);
	}

	private boolean startedEating;

	private void finishRecover() {
		recovering = false;
		startedEating = false;
		eatCooldown = 40;
		Gear.stowMainHand(companion);
		if (ranged) {
			Archery.equipBow(companion);
		} else {
			Gear.equipBestWeapon(companion);
		}
		companion.debug("Combate: comeu (vida " + companion.getHealth() + "); voltando à luta.");
	}

	// ------------------------------------------------------------------ escolha de arma

	private boolean shouldUseBow(LivingEntity target) {
		if (!Archery.ready(companion) || target instanceof Enderman) {
			return false; // flechas não acertam endermans
		}
		float health = companion.getHealth() / companion.getMaxHealth();
		double dist = companion.distanceTo(target);
		if (target instanceof Creeper) {
			return true;
		}
		if (target.isNoGravity() || unreachable || target.isInWater() && !companion.isInWater()) {
			return true;
		}
		// Com pouca vida não troca golpes: mantém distância e atira (fica no arco até se recuperar um pouco).
		if (health <= (ranged ? 0.6f : 0.5f) && !(target instanceof RangedAttackMob && dist < 4)) {
			return true;
		}
		// Atiradores longe e sem escudo para se aproximar com segurança.
		return target instanceof RangedAttackMob && dist > 12 && !companion.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD);
	}

	private boolean canWalkTo(LivingEntity target) {
		if (CombatHelper.inReach(companion, target)) {
			return true;
		}
		Path p = companion.getNavigation().createPath(target, 1);
		return dev.autonomouscompanions.nav.Paths.reaches(p, target.blockPosition(), 2.5);
	}

	// ------------------------------------------------------------------ arco

	private void tickRanged(ServerLevel level, LivingEntity target) {
		if (!companion.mainHandItem().is(Items.BOW) && !Archery.equipBow(companion) || Archery.arrows(companion) <= 0) {
			ranged = false;
			modeTimer = 0;
			companion.stopUsingItem();
			Gear.equipBestWeapon(companion);
			return;
		}
		double dist = companion.distanceTo(target);
		boolean sight = companion.hasLineOfSight(target);
		noSightTicks = sight ? 0 : noSightTicks + 1;
		double min = target instanceof Creeper ? 8 : 6;
		double max = 15;
		swapStrafe();
		if (dist < min) {
			// Recua para manter a distância (o alvo está chegando perto).
			Vec3 away = companion.position().subtract(target.position()).normalize();
			Vec3 to = companion.position().add(away.scale(6)).add(away.yRot((float) Math.PI / 2).scale(strafeDir * 2.0));
			companion.getNavigation().moveTo(to.x, to.y, to.z, 1.4);
			companion.setSprinting(dist < 3.5);
		} else if (dist > max || !sight && noSightTicks > 20) {
			companion.setSprinting(false);
			companion.getNavigation().moveTo(target, 1.15);
		} else {
			// Na faixa certa: para de andar e se move de lado, como um arqueiro experiente.
			companion.setSprinting(false);
			companion.getNavigation().stop();
			companion.getMoveControl().strafe(dist < min + 2 ? -0.4F : 0.0F, strafeDir * 0.5F);
		}
		if (!companion.isUsingItem()) {
			if (sight && dist <= 28) {
				companion.startUsingItem(InteractionHand.MAIN_HAND);
			}
			return;
		}
		if (!sight && noSightTicks > 40) {
			companion.stopUsingItem(); // sem visão por muito tempo: alivia o arco
			return;
		}
		int drawn = companion.getTicksUsingItem();
		// Força total; se o alvo estiver colado, solta antes (mais vale uma flecha fraca que um golpe recebido).
		boolean release = sight && (drawn >= Archery.FULL_DRAW || drawn >= 8 && dist < 3.0);
		if (release) {
			companion.stopUsingItem();
			Archery.shoot(level, companion, target, drawn);
		}
	}

	// ------------------------------------------------------------------ corpo a corpo

	private void tickMelee(ServerLevel level, LivingEntity target) {
		double dist = companion.distanceTo(target);
		if (target instanceof Creeper creeper) {
			if (creeper.getSwellDir() > 0 || retreatTicks > 0) {
				// Recuo: afasta-se do creeper.
				retreatTicks = Math.max(0, retreatTicks - 1);
				lowerShield();
				Vec3 away = companion.position().subtract(creeper.position()).normalize().scale(6).add(companion.position());
				companion.setSprinting(true);
				companion.getNavigation().moveTo(away.x, away.y, away.z, 1.6);
				return;
			}
		}
		if (--weaponTimer <= 0) {
			weaponTimer = 10;
			chooseMeleeWeapon(target);
		}
		swapStrafe();
		if (cooldown[0] > 0) {
			cooldown[0]--;
		}
		if (!CombatHelper.inReach(companion, target)) {
			airTicks = 0;
			// Chega correndo: o primeiro golpe em corrida empurra o inimigo para longe.
			boolean sprint = dist > 3.5 && !companion.isInWater();
			companion.setSprinting(sprint);
			if (sprint) {
				sprintHit = true;
			}
			companion.getNavigation().moveTo(target, dist > 6 ? 1.45 : 1.25);
			if (target instanceof RangedAttackMob && dist > 4) {
				companion.getMoveControl().strafe(0.6f, strafeDir * 0.4f); // zigue-zague contra flechas
			}
			// Pulo de aproximação: chega já caindo para o crítico.
			if (dist < CombatHelper.reach(companion, target) + 1.8 && cooldown[0] <= 4 && companion.onGround() && !companion.isInWater()) {
				companion.getJumpControl().jump();
			}
			raiseShieldIfUseful(target, dist);
			return;
		}
		companion.getNavigation().stop();
		if (cooldown[0] > 0) {
			// Recarregando: anda de lado e recua (os golpes do inimigo erram mais) e se protege com o escudo. Contra outra
			// jogadora, recua mais ("w-tap"): ao recarregar, volta correndo e o próximo golpe também é de corrida (empurra).
			companion.setSprinting(false);
			boolean wtap = target instanceof net.minecraft.world.entity.player.Player && dist < 3.0;
			companion.getMoveControl().strafe(wtap ? -0.7F : dist < 2.0 ? -0.35F : 0.05F, strafeDir * 0.45F);
			if (wtap && cooldown[0] <= 4) {
				sprintHit = true;
				companion.setSprinting(true);
			}
			if (cooldown[0] > 5) {
				raiseShieldIfUseful(target, dist);
			} else {
				lowerShield();
			}
			return;
		}
		// Arma carregada: pula e golpeia caindo (crítico); se não der para pular, golpeia normal.
		boolean canJump = companion.onGround() && !companion.isInWater() && !companion.onClimbable()
			&& level.getBlockState(companion.blockPosition().above(2)).getCollisionShape(level, companion.blockPosition().above(2)).isEmpty();
		if (canJump && airTicks == 0 && !sprintHit) {
			companion.getJumpControl().jump();
			airTicks = 1;
			return;
		}
		if (airTicks > 0 && !companion.onGround() && !CombatHelper.canCrit(companion) && airTicks++ < 12) {
			return; // ainda subindo: espera começar a cair
		}
		boolean crit = CombatHelper.canCrit(companion);
		lowerShield();
		if (CombatHelper.tryAttack(level, companion, target, cooldown, crit, sprintHit && companion.isSprinting())) {
			if (target instanceof Creeper) {
				retreatTicks = 25; // bate e recua
			}
			sprintHit = false;
			companion.setSprinting(false);
		}
		airTicks = 0;
	}

	/** Contra escudo, o machado (desativa o escudo por alguns segundos); senão a melhor arma. */
	private void chooseMeleeWeapon(LivingEntity target) {
		if (target.isBlocking()) {
			ItemStack axe = null;
			double best = -1;
			for (int i = 0; i < companion.inventory().getContainerSize(); i++) {
				ItemStack s = companion.inventory().getItem(i);
				if (s.is(ItemTags.AXES) && Gear.attackDamage(s) > best) {
					best = Gear.attackDamage(s);
					axe = s;
				}
			}
			if (axe != null && !companion.mainHandItem().is(ItemTags.AXES)) {
				Gear.equipMainHand(companion, axe);
				companion.debug("Combate: machado contra o escudo.");
			}
			return;
		}
		Gear.equipBestWeapon(companion);
	}

	private void swapStrafe() {
		if (--strafeTimer <= 0) {
			strafeTimer = 20 + companion.getRandom().nextInt(25);
			strafeDir = -strafeDir;
		}
	}

	private void raiseShieldIfUseful(LivingEntity target, double dist) {
		if (!companion.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD) || target instanceof Creeper) {
			return;
		}
		// Atiradores (mobs e jogadoras com arco/besta/tridente puxados) de longe; corpo a corpo, quando colado.
		boolean drawing = target.isUsingItem() && (target.getUseItem().is(Items.BOW) || target.getUseItem().is(Items.CROSSBOW)
			|| target.getUseItem().is(Items.TRIDENT));
		boolean incoming = target instanceof RangedAttackMob || drawing ? drawing && dist > 3 || !(target instanceof RangedAttackMob) && dist < 3.2 : dist < 3.2;
		if (incoming && !companion.isUsingItem()) {
			companion.startUsingItem(InteractionHand.OFF_HAND);
		} else if (!incoming) {
			lowerShield();
		}
	}

	private void lowerShield() {
		if (companion.isUsingItem() && companion.getUsedItemHand() == InteractionHand.OFF_HAND) {
			companion.stopUsingItem();
		}
	}

	@Override
	public void stop(ServerLevel level) {
		companion.stopUsingItem();
		companion.getNavigation().stop();
		companion.setSprinting(false);
		if (recovering || startedEating) {
			recovering = false;
			startedEating = false;
			Gear.stowMainHand(companion);
		}
		// Mantém a arma atual: se a luta recomeçar logo, a escolha é refeita no início.
		ranged = false;
	}
}
