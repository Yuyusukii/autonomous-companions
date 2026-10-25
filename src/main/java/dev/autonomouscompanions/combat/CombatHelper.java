package dev.autonomouscompanions.combat;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Ataque corpo a corpo com recarga baseada na velocidade de ataque real da arma empunhada, e as técnicas de combate do
 * jogador: golpe crítico (caindo de um pulo: +50% de dano), golpe em corrida (empurrão extra) e varredura da espada
 * (golpe parado acerta também os inimigos colados no alvo).
 */
public final class CombatHelper {
	private static final Identifier CRIT_ID = AutonomousCompanions.id("critical_hit");
	private static final Identifier SPRINT_ID = AutonomousCompanions.id("sprint_hit");

	private CombatHelper() {
	}

	public static double reach(CompanionEntity c, LivingEntity target) {
		double interaction = c.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
		return interaction + target.getBbWidth() * 0.5 + 0.3;
	}

	public static boolean inReach(CompanionEntity c, LivingEntity target) {
		return c.getEyePosition().distanceTo(target.getBoundingBox().getCenter()) <= reach(c, target) && c.hasLineOfSight(target);
	}

	/** Ticks entre golpes: 20 / velocidade de ataque da arma (espada ≈ 12, machado ≈ 20, mão ≈ 5). */
	public static int cooldownTicks(CompanionEntity c) {
		double speed = Gear.attackSpeed(c.mainHandItem());
		return Math.max(5, (int) Math.ceil(20.0 / Math.max(0.5, speed)));
	}

	/** Pode dar golpe crítico agora? (caindo de um pulo, fora d'água, sem escada, sem montaria) */
	public static boolean canCrit(CompanionEntity c) {
		return !c.onGround() && c.getDeltaMovement().y < -0.05 && !c.isInWater() && !c.onClimbable() && !c.isPassenger();
	}

	/** Golpeia se a recarga permitir. Retorna verdadeiro se houve golpe. */
	public static boolean tryAttack(ServerLevel level, CompanionEntity c, LivingEntity target, int[] cooldown) {
		return tryAttack(level, c, target, cooldown, false, false);
	}

	/**
	 * Golpeia se a recarga permitir, com crítico ({@code crit}) e/ou empurrão de corrida ({@code sprint}). O bônus é um
	 * modificador temporário de atributo aplicado só durante o golpe, então armas, encantamentos e armaduras continuam
	 * sendo calculados pelo próprio jogo.
	 */
	public static boolean tryAttack(ServerLevel level, CompanionEntity c, LivingEntity target, int[] cooldown, boolean crit, boolean sprint) {
		c.getLookControl().setLookAt(target, 30.0F, 30.0F);
		if (cooldown[0] > 0) {
			cooldown[0]--;
			return false;
		}
		if (!inReach(c, target)) {
			return false;
		}
		if (c.isUsingItem()) {
			c.stopUsingItem(); // baixa o escudo para golpear
		}
		AttributeInstance damage = c.getAttribute(Attributes.ATTACK_DAMAGE);
		AttributeInstance knockback = c.getAttribute(Attributes.ATTACK_KNOCKBACK);
		if (crit && damage != null) {
			damage.addTransientModifier(new AttributeModifier(CRIT_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		if (sprint && knockback != null) {
			knockback.addTransientModifier(new AttributeModifier(SPRINT_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
		}
		boolean hurt;
		try {
			c.swingForAttack(InteractionHand.MAIN_HAND);
			hurt = c.doHurtTarget(level, target);
		} finally {
			if (damage != null) {
				damage.removeModifier(CRIT_ID);
			}
			if (knockback != null) {
				knockback.removeModifier(SPRINT_ID);
			}
		}
		cooldown[0] = cooldownTicks(c);
		c.needs().addExhaustion(0.1f);
		c.setActivityLevel(3);
		if (hurt) {
			if (crit) {
				level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.5), target.getZ(), 10, 0.3, 0.3, 0.3, 0.2);
				c.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 1.0F);
			} else if (sprint) {
				c.playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F, 1.0F);
			} else if (c.mainHandItem().is(ItemTags.SWORDS) && c.onGround()) {
				sweep(level, c, target);
			}
			if (!target.isAlive()) {
				c.stats().kills++;
			}
		}
		return true;
	}

	/** Varredura da espada: inimigos colados no alvo levam 1 de dano e são empurrados (como no golpe parado do jogador). */
	private static void sweep(ServerLevel level, CompanionEntity c, LivingEntity target) {
		boolean any = false;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(1.0, 0.25, 1.0))) {
			if (e == c || e == target || c.isAlliedTo(e) || !c.brain().threats().isThreat(e) || c.distanceToSqr(e) > 9.0) {
				continue;
			}
			var source = c.damageSources().mobAttack(c);
			e.knockback(0.4, Math.sin(Math.toRadians(c.getYRot())), -Math.cos(Math.toRadians(c.getYRot())), source, 1.0F);
			e.hurtServer(level, source, 1.0F);
			any = true;
		}
		if (any) {
			c.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.0F);
			level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY(0.5), target.getZ(), 1, 0, 0, 0, 0);
		}
	}
}
