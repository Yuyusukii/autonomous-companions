package dev.autonomouscompanions.combat;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.Gear;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Arco e flecha "profissional": mesma física das flechas do jogador (força total = velocidade 3, flecha crítica),
 * mira antecipando o movimento do alvo e compensando a queda da flecha, consome flechas reais do inventário e gasta a
 * durabilidade do arco.
 */
public final class Archery {
	/** Ticks para carregar o arco por completo (como o jogador). */
	public static final int FULL_DRAW = 20;
	/** Velocidade da flecha com força total (igual à do jogador). */
	private static final float FULL_SPEED = 3.0F;
	/** Imprecisão (o jogador usa 1,0; esqueletos no difícil usam 2,0 e no fácil 10,0). */
	private static final float INACCURACY = 1.0F;

	private Archery() {
	}

	public static boolean isAmmo(ItemStack s) {
		return !s.isEmpty() && s.getItem() instanceof ArrowItem;
	}

	public static int arrows(CompanionEntity c) {
		return c.inventory().count(Archery::isAmmo);
	}

	public static @Nullable ItemStack bow(CompanionEntity c) {
		if (c.mainHandItem().is(Items.BOW)) {
			return c.mainHandItem();
		}
		int slot = c.inventory().findSlot(s -> s.is(Items.BOW));
		return slot >= 0 ? c.inventory().getItem(slot) : null;
	}

	/** Tem arco e flechas? */
	public static boolean ready(CompanionEntity c) {
		return bow(c) != null && arrows(c) > 0;
	}

	/** Empunha o arco (a arma corpo a corpo volta para o inventário). */
	public static boolean equipBow(CompanionEntity c) {
		ItemStack bow = bow(c);
		if (bow == null) {
			return false;
		}
		Gear.equipMainHand(c, bow);
		return c.mainHandItem().is(Items.BOW);
	}

	/**
	 * Dispara uma flecha em {@code target} com a força correspondente ao tempo em que o arco foi puxado. Retorna
	 * verdadeiro se disparou.
	 */
	public static boolean shoot(ServerLevel level, CompanionEntity c, LivingEntity target, int drawTicks) {
		ItemStack bow = c.mainHandItem();
		if (!bow.is(Items.BOW)) {
			return false;
		}
		int slot = c.inventory().findSlot(Archery::isAmmo);
		if (slot < 0) {
			return false;
		}
		ItemStack ammoStack = c.inventory().getItem(slot);
		ItemStack ammo = ammoStack.copyWithCount(1);
		float power = BowItem.getPowerForTime(drawTicks);
		if (power < 0.1F) {
			return false;
		}
		AbstractArrow arrow = ProjectileUtil.getMobArrow(c, ammo, power, bow);
		arrow.setCritArrow(power >= 1.0F);
		arrow.pickup = AbstractArrow.Pickup.ALLOWED;
		float speed = power * FULL_SPEED;
		// Antecipa o movimento do alvo pelo tempo de voo e compensa a gravidade da flecha (0,05 por tick).
		Vec3 aim = aimPoint(c, target, speed);
		double xd = aim.x - c.getX();
		double zd = aim.z - c.getZ();
		double horizontal = Math.sqrt(xd * xd + zd * zd);
		double flight = horizontal / Math.max(0.5, speed * 0.97);
		double yd = aim.y - arrow.getY() + 0.025 * flight * flight;
		Projectile.spawnProjectileUsingShoot(arrow, level, ammo, xd, yd, zd, speed, INACCURACY);
		ammoStack.shrink(1);
		if (ammoStack.isEmpty()) {
			c.inventory().setItem(slot, ItemStack.EMPTY);
		}
		bow.hurtAndBreak(1, c, EquipmentSlot.MAINHAND);
		c.playSound(SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (c.getRandom().nextFloat() * 0.4F + 1.2F) + power * 0.5F);
		c.needs().addExhaustion(0.1f);
		c.setActivityLevel(3);
		return true;
	}

	/** Ponto previsto do alvo (meio do corpo) quando a flecha chegar. */
	static Vec3 aimPoint(CompanionEntity c, LivingEntity target, float speed) {
		Vec3 pos = new Vec3(target.getX(), target.getY(0.5), target.getZ());
		Vec3 vel = target.getDeltaMovement();
		// Duas iterações bastam: o tempo de voo depende da distância até o ponto previsto.
		Vec3 aim = pos;
		for (int i = 0; i < 2; i++) {
			double dx = aim.x - c.getX();
			double dz = aim.z - c.getZ();
			double t = Math.sqrt(dx * dx + dz * dz) / Math.max(0.5, speed * 0.97);
			double vy = target.onGround() || target.isInWater() ? 0 : vel.y;
			aim = pos.add(vel.x * t, vy * t * 0.5, vel.z * t);
		}
		return aim;
	}
}
