package dev.autonomouscompanions.combat;

import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Detecção de ameaças com frequência limitada (a cada {@code intervaloAmeacasTicks}): quem atacou o dono, quem o tem
 * como alvo, quem atacou a companheira e, para guardas, hostis próximos ao dono. Nunca considera ameaça o dono,
 * aliados, animais de estimação do dono, aldeões ou animais passivos que não estejam atacando.
 */
public final class ThreatTracker {
	public enum Kind {
		NONE,
		OWNER_ATTACKED,
		SELF_ATTACKED,
		PROACTIVE
	}

	private final CompanionEntity companion;
	private @Nullable LivingEntity selfAttacker;
	private int selfAttackTick;
	private @Nullable LivingEntity target;
	private Kind kind = Kind.NONE;
	private int timer;
	private int hostilesNearSelf;
	private boolean creeperDanger;
	private boolean overwhelming;
	private @Nullable LivingEntity nearestThreat;

	public ThreatTracker(CompanionEntity companion) {
		this.companion = companion;
	}

	public @Nullable LivingEntity target() {
		return target != null && target.isAlive() ? target : null;
	}

	public Kind kind() {
		return target() == null ? Kind.NONE : kind;
	}

	public int hostilesNearSelf() {
		return hostilesNearSelf;
	}

	public boolean creeperDanger() {
		return creeperDanger;
	}

	public boolean overwhelming() {
		return overwhelming;
	}

	public @Nullable LivingEntity nearestThreat() {
		return nearestThreat != null && nearestThreat.isAlive() ? nearestThreat : null;
	}

	public void onAttackedBy(LivingEntity attacker) {
		if (companion.isAlliedTo(attacker)) {
			return;
		}
		selfAttacker = attacker;
		selfAttackTick = companion.tickCount;
		timer = 0;
	}

	public void clearTarget() {
		target = null;
		kind = Kind.NONE;
	}

	public boolean isThreat(LivingEntity e) {
		if (e == companion || !e.isAlive() || companion.isAlliedTo(e) || companion.isOwnedBy(e)) {
			return false;
		}
		if (e instanceof AbstractVillager) {
			return false;
		}
		if (e instanceof OwnableEntity pet && pet.getOwnerReference() != null && companion.ownerUUID() != null
			&& pet.getOwnerReference().getUUID().equals(companion.ownerUUID())) {
			return false;
		}
		if (e instanceof Player p) {
			return ConfigManager.get().defenderContraJogadores && !p.isCreative() && !p.isSpectator();
		}
		return true;
	}

	public void tick(ServerLevel level) {
		if (++timer < ConfigManager.get().intervaloAmeacasTicks) {
			return;
		}
		timer = 0;
		ServerPlayer owner = companion.ownerInLevel();
		LivingEntity best = null;
		Kind bestKind = Kind.NONE;
		double bestDist = Double.MAX_VALUE;

		// Ameaças ao redor da própria companheira (para fuga/avaliação).
		List<Mob> nearSelf = level.getEntitiesOfClass(Mob.class, companion.getBoundingBox().inflate(10), m -> m instanceof Enemy && m.isAlive());
		hostilesNearSelf = nearSelf.size();
		creeperDanger = false;
		nearestThreat = null;
		double nearest = Double.MAX_VALUE;
		boolean warden = false;
		for (Mob m : nearSelf) {
			double d = m.distanceToSqr(companion);
			if (d < nearest) {
				nearest = d;
				nearestThreat = m;
			}
			if (m instanceof Creeper creeper && creeper.getSwellDir() > 0 && d < 36) {
				creeperDanger = true;
			}
			if (m instanceof Warden) {
				warden = true;
			}
		}
		overwhelming = warden || hostilesNearSelf >= 5;

		if (owner != null && companion.settings().protectOwner) {
			// 1) Quem feriu o dono recentemente.
			LivingEntity attacker = owner.getLastHurtByMob();
			if (attacker != null && attacker.isAlive() && owner.tickCount - owner.getLastHurtByMobTimestamp() < 100 && isThreat(attacker)
				&& attacker.distanceToSqr(owner) < 24 * 24) {
				best = attacker;
				bestKind = Kind.OWNER_ATTACKED;
				bestDist = attacker.distanceToSqr(companion);
			}
			// 2) Quem está mirando o dono.
			for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(owner.blockPosition()).inflate(16), m -> m.getTarget() == owner && m.isAlive())) {
				if (!isThreat(m)) {
					continue;
				}
				double d = m.distanceToSqr(companion);
				if (bestKind != Kind.OWNER_ATTACKED || d < bestDist) {
					best = m;
					bestKind = Kind.OWNER_ATTACKED;
					bestDist = d;
				}
			}
		}
		// 2b) Quem a dona acabou de atacar (hostil, ou jogador se a defesa contra jogadores estiver ligada): ela entra na luta
		// junto, no mesmo alvo — como uma parceira de PvP, em vez de ficar olhando a dona lutar sozinha.
		if (best == null && owner != null && companion.settings().protectOwner) {
			LivingEntity hit = owner.getLastHurtMob();
			if (hit != null && hit.isAlive() && owner.tickCount - owner.getLastHurtMobTimestamp() < 100 && isThreat(hit)
				&& (hit instanceof Enemy || hit instanceof Player || hit instanceof Mob m && m.getTarget() == owner) && hit.distanceToSqr(owner) < 20 * 20) {
				best = hit;
				bestKind = Kind.OWNER_ATTACKED;
				bestDist = hit.distanceToSqr(companion);
			}
		}
		// 3) Quem atacou a companheira (ou está mirando nela).
		if (best == null) {
			if (selfAttacker != null && selfAttacker.isAlive() && companion.tickCount - selfAttackTick < 200 && isThreat(selfAttacker)
				&& selfAttacker.distanceToSqr(companion) < 20 * 20) {
				best = selfAttacker;
				bestKind = Kind.SELF_ATTACKED;
			} else {
				// Quem está vindo atrás dela (mirando nela) é percebido de mais longe que os hostis à toa: 16 blocos.
				for (Mob m : level.getEntitiesOfClass(Mob.class, companion.getBoundingBox().inflate(16, 8, 16), x -> x.isAlive() && x.getTarget() == companion)) {
					if (isThreat(m)) {
						best = m;
						bestKind = Kind.SELF_ATTACKED;
						break;
					}
				}
			}
		}
		// 3b) Equipe: hostil atacando outra companheira da mesma dona por perto — vai ajudar (o mais perto dela primeiro).
		if (best == null && companion.ownerUUID() != null) {
			for (Mob m : level.getEntitiesOfClass(Mob.class, companion.getBoundingBox().inflate(14), m -> m.isAlive() && m.getTarget() instanceof CompanionEntity ally
				&& ally != companion && companion.ownerUUID().equals(ally.ownerUUID()))) {
				if (!isThreat(m)) {
					continue;
				}
				double d = m.distanceToSqr(companion);
				if (d < bestDist) {
					best = m;
					bestKind = Kind.SELF_ATTACKED;
					bestDist = d;
				}
			}
		}
		// 3c) Ajudante como segurança: monstro hostil perto de qualquer companheira da mesma dona, ou mirando uma delas.
		if (best == null && companion.guardsAllies() && companion.ownerUUID() != null) {
			LivingEntity guard = guardTarget(level);
			if (guard != null) {
				best = guard;
				bestKind = Kind.PROACTIVE;
				bestDist = guard.distanceToSqr(companion);
			}
		}
		// 4) Defesa proativa (guardas ou configuração): hostis perto do dono.
		if (best == null && owner != null && (companion.settings().proactiveDefense || companion.role().proactiveDefense)) {
			for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(owner.blockPosition()).inflate(12), m -> m instanceof Enemy && m.isAlive())) {
				if (!isThreat(m) || m instanceof Creeper && companion.getHealth() < companion.getMaxHealth() * 0.7f) {
					continue;
				}
				double d = m.distanceToSqr(owner);
				if (d < bestDist) {
					best = m;
					bestKind = Kind.PROACTIVE;
					bestDist = d;
				}
			}
		}
		target = best;
		kind = bestKind;
	}

	/**
	 * Segurança (ajudante): o monstro mais urgente perto de uma companheira da mesma dona — hostil a até
	 * {@code raioSegurancaAjudante} blocos dela, ou mirando nela —, olhando companheiras a até 128 blocos. Animais, aldeões,
	 * animais domesticados da dona e aliadas nunca entram (mesma regra de {@link #isThreat}).
	 */
	private @Nullable LivingEntity guardTarget(ServerLevel level) {
		int r = ConfigManager.get().raioSegurancaAjudante;
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		for (CompanionEntity ally : level.getEntitiesOfClass(CompanionEntity.class, companion.getBoundingBox().inflate(128, 48, 128),
			a -> a != companion && a.isAlive() && !a.isHelper() && companion.ownerUUID().equals(a.ownerUUID()))) {
			for (Mob m : level.getEntitiesOfClass(Mob.class, ally.getBoundingBox().inflate(r, 8, r), m -> m.isAlive() && (m instanceof Enemy || m.getTarget() == ally))) {
				if (!isThreat(m)) {
					continue;
				}
				double score = m.distanceToSqr(ally) + 0.25 * m.distanceToSqr(companion);
				if (score < bestScore) {
					bestScore = score;
					best = m;
				}
			}
		}
		return best;
	}

	public List<LivingEntity> hostilesAround(ServerLevel level, double radius) {
		return new ArrayList<>(level.getEntitiesOfClass(Mob.class, companion.getBoundingBox().inflate(radius), m -> m instanceof Enemy && m.isAlive()));
	}
}
