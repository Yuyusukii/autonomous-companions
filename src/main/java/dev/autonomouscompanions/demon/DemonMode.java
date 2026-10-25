package dev.autonomouscompanions.demon;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionVariant;
import dev.autonomouscompanions.nav.SafeSpot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Modo Demônio — habilidade exclusiva da companheira de cabelo vermelho (variante {@link CompanionVariant#RUBI}).
 * <p>
 * Máquina de estados autoritativa no servidor, com o tempo medido pelo relógio do mundo (não depende de a entidade
 * estar visível nem de quadros do cliente; ao recarregar, ela continua do ponto certo ou avança os estados vencidos):
 * <pre>
 * NORMAL → TRANSFORMAÇÃO (2,5 s) → DEMÔNIO ATIVO (até completar 20 s) → EXAUSTÃO (descida) → DORMINDO (200 s)
 *        → RECUPERAÇÃO (2,5 s) → NORMAL
 * </pre>
 * A ativação acontece em combate, quando a vida chega a 1 coração, ou quando um golpe a mataria: a morte é
 * interceptada antes de acontecer ({@link #preventDeath}).
 * <p>
 * <b>Toda noite</b> (padrão desde 18/10/2026, pedido da jogadora, com vida infinita): ao anoitecer ela desperta sozinha
 * e caça os monstros num raio de {@code raioModoDemonioNoturno} (1000 blocos) até amanhecer; ao amanhecer desce e volta
 * ao normal, sem exaustão e sem sono:
 * <pre>
 * NORMAL → TRANSFORMAÇÃO (2,5 s) → DEMÔNIO ATIVO (até amanhecer) → DESCIDA → NORMAL
 * </pre>
 */
public final class DemonMode {
	public static final int TRANSFORM_TICKS = 50;
	/** Duração total do modo (transformação incluída): exatamente 20 s. */
	public static final int DURATION_TICKS = 400;
	public static final int MAX_DESCENT_TICKS = 80;
	/** Sono obrigatório: exatamente 200 s. */
	public static final int SLEEP_TICKS = 4000;
	public static final int RECOVER_TICKS = 50;
	public static final double RADIUS = 100.0;
	/** 100 corações. */
	public static final float DEMON_HEALTH = 200.0F;
	/** Vida em que a habilidade desperta: 1 coração. */
	public static final float TRIGGER_HEALTH = 2.0F;
	private static final Identifier HEALTH_ID = AutonomousCompanions.id("demon_health");
	private static final int STRIKE_INTERVAL = 3;

	private final CompanionEntity c;
	private DemonState state = DemonState.NORMAL;
	private long activatedAt;
	private long stateStart;
	private double hoverBase;
	private float savedHealth = DEMON_HEALTH;
	// Clima anterior (restaurado ao fim dos 20 s; a chuva também tem duração própria e termina sozinha).
	private boolean weatherChanged;
	private boolean prevRaining;
	private boolean prevThundering;
	private int prevClear;
	private int prevRain;
	// Caçada (só durante o modo; limpa ao terminar).
	private final Deque<UUID> queue = new ArrayDeque<>();
	private final Set<UUID> executed = new HashSet<>();
	private long nextStrike;
	private long nextRefresh;
	private int kills;
	private int distinctKills;
	/** Ativação da noite (dura até amanhecer, raio noturno, sem sono depois). */
	private boolean nightly;
	private int idleTicks;

	public DemonMode(CompanionEntity c) {
		this.c = c;
	}

	// ------------------------------------------------------------------ consultas

	public DemonState state() {
		return state;
	}

	public boolean engaged() {
		return state.engaged();
	}

	/** Só a companheira de cabelo vermelho tem a habilidade (nunca a ajudante nem as outras). */
	public static boolean canHave(CompanionEntity c) {
		return !c.isHelper() && c.variant() == CompanionVariant.RUBI;
	}

	/** A tempestade desta ativação ainda está valendo (restaurada ao fim dos 20 s). */
	public boolean weatherActive() {
		return weatherChanged;
	}

	public int kills() {
		return kills;
	}

	/** Alvos distintos já executados nesta ativação (igual a {@link #kills()} quando ninguém é executado duas vezes). */
	public int executedCount() {
		return distinctKills;
	}

	public long activatedAt() {
		return activatedAt;
	}

	/** A ativação atual é a da noite (e não a do golpe fatal). */
	public boolean nightly() {
		return nightly;
	}

	/** Noite de verdade pelo relógio do mundo (13000–23000 do ciclo de 24000); uma tempestade de dia não conta. */
	public static boolean isNight(ServerLevel level) {
		if (level.dimensionType().hasFixedTime() || level.dimensionType().defaultClock().isEmpty()) {
			return false; // Nether e End: sem noite
		}
		long t = Math.floorMod(level.getDefaultClockTime(), 24000L);
		return t >= 13000 && t < 23000;
	}

	/** Toda noite (padrão): desperta sozinha ao anoitecer, sem precisar de combate nem de golpe fatal. */
	public void checkNight(ServerLevel level) {
		if (state != DemonState.NORMAL || c.tickCount % 20 != 0 || !canHave(c) || !c.demonEveryNight() || !c.isNamed() || !isNight(level)) {
			return;
		}
		activate(level, "anoiteceu", true);
	}

	public long stateStart() {
		return stateStart;
	}

	/** Imune a este dano agora? (dano que ignora invulnerabilidade, como /kill e o vazio, continua valendo). */
	public boolean blocks(DamageSource source) {
		return state.protectedState() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
	}

	/** Efeitos negativos não a atingem enquanto está protegida (nada incapacita a habilidade ou o sono). */
	public boolean blocksEffect(MobEffectInstance effect) {
		return state.protectedState() && !effect.getEffect().value().isBeneficial();
	}

	/** Em combate de verdade: atacada agora por uma ameaça, com alvo de combate, ou ferida recentemente em luta. */
	private boolean inCombat(@Nullable DamageSource source) {
		if (source != null && source.getEntity() instanceof LivingEntity attacker && attacker != c && c.brain().threats().isThreat(attacker)) {
			return true;
		}
		if (c.brain().threats().target() != null) {
			return true;
		}
		LivingEntity last = c.getLastHurtByMob();
		return last != null && last.isAlive() && c.tickCount - c.lastAttackedByTick() < 100 && c.brain().threats().isThreat(last);
	}

	private boolean canActivate(@Nullable DamageSource source) {
		return canHave(c) && state == DemonState.NORMAL && c.level() instanceof ServerLevel && inCombat(source);
	}

	// ------------------------------------------------------------------ ativação

	/**
	 * Chamado no lugar da morte (antes de ela acontecer). Se o golpe fatal veio de um combate, impede a morte e desperta
	 * o modo. Retorna verdadeiro se a morte foi evitada.
	 */
	public boolean preventDeath(DamageSource source) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || !canActivate(source)) {
			return false;
		}
		c.setHealth(TRIGGER_HEALTH);
		activate((ServerLevel) c.level(), "golpe fatal interceptado", false);
		return true;
	}

	/** Depois de um dano não fatal: chegou a 1 coração em combate? */
	public void afterHurt(DamageSource source) {
		if (c.isAlive() && c.getHealth() <= TRIGGER_HEALTH && canActivate(source)) {
			activate((ServerLevel) c.level(), "1 coração em combate", false);
		}
	}

	private void activate(ServerLevel level, String why, boolean night) {
		long now = level.getGameTime();
		nightly = night;
		idleTicks = 0;
		activatedAt = now;
		executed.clear();
		queue.clear();
		kills = 0;
		distinctKills = 0;
		maxTickNanos = 0;
		secondTickNanos = 0;
		steadyTickNanos = 0;
		maxTickWhere = "";
		c.brain().interruptAll(level);
		c.stopUsingItem();
		c.getNavigation().stop();
		c.setSprinting(false);
		if (c.isSleeping()) {
			c.stopSleeping();
		}
		c.clearFire();
		for (MobEffectInstance e : List.copyOf(c.getActiveEffects())) {
			if (!e.getEffect().value().isBeneficial()) {
				c.removeEffect(e.getEffect());
			}
		}
		applyHealthBoost();
		c.setHealth(DEMON_HEALTH);
		hoverBase = c.getY();
		c.setNoGravity(true);
		c.fallDistance = 0;
		c.setDeltaMovement(Vec3.ZERO);
		startStorm(level);
		setState(DemonState.TRANSFORMING, now);
		level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 2.0f, 0.6f);
		level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.2f, 0.5f);
		ServerPlayer owner = c.ownerAnywhere();
		if (owner != null) {
			owner.sendSystemMessage(Component.translatable(night ? "message.autonomouscompanions.demon_night" : "message.autonomouscompanions.demon_awaken",
				c.getDisplayName()));
		}
		c.debug("Modo Demônio despertou (" + why + ").");
	}

	private void applyHealthBoost() {
		AttributeInstance max = c.getAttribute(Attributes.MAX_HEALTH);
		if (max == null) {
			return;
		}
		max.removeModifier(HEALTH_ID);
		// Modificador temporário (não é salvo): ao recarregar, o estado o reaplica; ao acordar, ele sai.
		max.addTransientModifier(new AttributeModifier(HEALTH_ID, DEMON_HEALTH - max.getBaseValue(), AttributeModifier.Operation.ADD_VALUE));
	}

	private void removeHealthBoost() {
		AttributeInstance max = c.getAttribute(Attributes.MAX_HEALTH);
		if (max != null) {
			max.removeModifier(HEALTH_ID);
		}
		if (c.getHealth() > c.getMaxHealth()) {
			c.setHealth(c.getMaxHealth());
		}
	}

	private void setState(DemonState s, long now) {
		state = s;
		stateStart = now;
		c.setDemonStateSynced(s);
	}

	// ------------------------------------------------------------------ tick

	/** Executa um tick da máquina de estados (no lugar da IA normal, enquanto o modo estiver engajado). */
	private long maxTickNanos;

	/** Maior custo de um tick do modo nesta ativação (desempenho). */
	public long maxTickNanos() {
		return maxTickNanos;
	}

	/** Onde ocorreu o tick mais caro (estado e idade nele), para diagnóstico. */
	private String maxTickWhere = "";

	public String maxTickWhere() {
		return maxTickWhere;
	}

	/** Segundo maior custo de um tick nesta ativação (o maior costuma ser a primeira execução a frio de um efeito). */
	private long secondTickNanos;

	public long secondTickNanos() {
		return secondTickNanos;
	}

	/** Maior custo de um tick da caçada em regime (estado ACTIVE depois do primeiro segundo). */
	private long steadyTickNanos;

	public long steadyTickNanos() {
		return steadyTickNanos;
	}

	public void tick(ServerLevel level) {
		long t0 = System.nanoTime();
		DemonState before = state;
		long age = level.getGameTime() - stateStart;
		try {
			tickInner(level);
		} finally {
			long spent = System.nanoTime() - t0;
			if (spent > maxTickNanos) {
				secondTickNanos = maxTickNanos;
				maxTickNanos = spent;
				maxTickWhere = before + "@" + age;
			} else if (spent > secondTickNanos) {
				secondTickNanos = spent;
			}
			if (before == DemonState.ACTIVE && age > 20) {
				steadyTickNanos = Math.max(steadyTickNanos, spent);
			}
		}
	}

	private void tickInner(ServerLevel level) {
		long now = level.getGameTime();
		long sinceActivation = now - activatedAt;
		long age = now - stateStart;
		switch (state) {
			case NORMAL -> {
				return;
			}
			case TRANSFORMING -> {
				if (!nightly && sinceActivation >= DURATION_TICKS) {
					enterExhausted(level, now);
				} else if (sinceActivation >= TRANSFORM_TICKS) {
					enterActive(level, now);
				} else {
					tickTransform(level, (int) age);
				}
			}
			case ACTIVE -> {
				if (nightly ? !isNight(level) : sinceActivation >= DURATION_TICKS) {
					enterExhausted(level, now);
				} else {
					if (nightly && weatherChanged && sinceActivation >= DURATION_TICKS) {
						restoreWeather(level); // à noite, a tempestade fica só nos primeiros 20 s (a transformação)
					}
					tickActive(level, now, (int) age);
				}
			}
			case EXHAUSTED -> tickExhausted(level, now, (int) age);
			case SLEEPING -> {
				if (age >= SLEEP_TICKS) {
					enterRecovering(level, now);
				} else {
					tickSleeping(level, (int) age);
				}
			}
			case RECOVERING -> {
				if (age >= RECOVER_TICKS) {
					finish(level);
				} else {
					tickRecovering((int) age);
				}
			}
		}
	}

	// ---- transformação

	private void tickTransform(ServerLevel level, int age) {
		c.setCompanionState(CompanionState.COMBAT, Component.translatable("detail.autonomouscompanions.demon_transforming"));
		c.getNavigation().stop();
		// Para por um instante (0–0,7 s), depois começa a subir devagar até ~1,6 bloco.
		double rise = age < 15 ? 0 : 1.6 * smooth((age - 15) / 35.0);
		hover(hoverBase + rise, 0.2);
		if (age == 18 || age == 32) {
			DemonFx.groundPulse(level, c, age == 18 ? 0.8 : 1.4);
			level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.6f, 0.7f);
		}
		if (age == 40) {
			// Trovão distante quando a aura chega ao máximo, com relâmpagos só visuais por perto.
			level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 3.0f, 0.7f);
			for (int i = 0; i < 2; i++) {
				visualLightning(level, i);
			}
		}
	}

	private void visualLightning(ServerLevel level, int i) {
		double a = level.getRandom().nextDouble() * Math.PI * 2;
		double d = 6 + level.getRandom().nextDouble() * 6;
		BlockPos at = BlockPos.containing(c.getX() + Math.cos(a) * d, c.getY(), c.getZ() + Math.sin(a) * d);
		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt != null) {
			bolt.setVisualOnly(true); // sem dano e sem fogo
			bolt.snapTo(Vec3.atBottomCenterOf(at));
			level.addFreshEntity(bolt);
		}
	}

	private void enterActive(ServerLevel level, long now) {
		setState(DemonState.ACTIVE, now);
		hoverBase = c.getY();
		DemonFx.climax(level, c);
		level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.0f, 1.3f);
		level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 1.5f, 0.8f);
		nextStrike = now + 4;
		nextRefresh = now;
	}

	// ---- caçada

	private void tickActive(ServerLevel level, long now, int age) {
		c.setCompanionState(CompanionState.COMBAT, Component.translatable("detail.autonomouscompanions.demon_hunting", kills));
		c.getNavigation().stop();
		hover(hoverBase + Math.sin(age / 8.0) * 0.12, 0.25);
		if (now >= nextRefresh) {
			nextRefresh = now + 20;
			refreshTargets(level);
		}
		if (now >= nextStrike) {
			// Muitos alvos: duas execuções por ciclo (ainda limitado, para o servidor seguir estável).
			int strikes = queue.size() > 40 ? 2 : 1;
			for (int i = 0; i < strikes; i++) {
				strikeNext(level);
			}
			nextStrike = now + STRIKE_INTERVAL;
		}
		Mob next = peekTarget(level);
		if (next != null) {
			face(next.position());
			idleTicks = 0;
		} else if (nightly && ++idleTicks > 40) {
			// Noite sem alvos: volta a pairar perto da dona (em vez de ficar onde caiu o último monstro, a centenas de blocos).
			idleTicks = 0;
			returnNearOwner(level);
		}
	}

	/** Teletransporta para perto da dona (mesma dimensão), com os efeitos de partida e chegada. */
	private void returnNearOwner(ServerLevel level) {
		ServerPlayer owner = c.ownerInLevel();
		if (owner == null || c.distanceToSqr(owner) < 24 * 24) {
			return;
		}
		BlockPos spot = SafeSpot.findNear(level, owner.blockPosition(), 2, 5, 3, c.getType());
		if (spot == null) {
			return;
		}
		Vec3 from = c.position();
		Vec3 to = new Vec3(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5);
		DemonFx.depart(level, c, from, to);
		c.teleportTo(to.x, to.y, to.z);
		hoverBase = to.y;
		DemonFx.arrive(level, c, c.position(), SoundEvents.ENDERMAN_TELEPORT);
	}

	/**
	 * Alvos elegíveis, nesta dimensão, do mais próximo ao mais distante: num raio 3D de 100 blocos (golpe fatal) ou, à
	 * noite, num raio horizontal de {@code raioModoDemonioNoturno} (1000 blocos), só onde o jogo está simulando entidades
	 * (num chunk parado ela também pararia).
	 */
	private void refreshTargets(ServerLevel level) {
		double radius = nightly ? dev.autonomouscompanions.config.ConfigManager.get().raioModoDemonioNoturno : RADIUS;
		List<Mob> found = new ArrayList<>(nightly
			? level.getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(radius, level.getHeight(), radius), m -> isEligible(m)
				&& horizontalDistSqr(m) <= radius * radius && level.isPositionEntityTicking(m.blockPosition()))
			: level.getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(RADIUS), m -> isEligible(m) && m.distanceToSqr(c) <= RADIUS * RADIUS));
		found.sort(Comparator.comparingDouble(m -> m.distanceToSqr(c)));
		queue.clear();
		for (Mob m : found) {
			if (!executed.contains(m.getUUID())) {
				queue.add(m.getUUID());
			}
		}
		c.debug("[demonio] alvos atualizados: " + found.size() + " encontrados, fila=" + queue.size() + " executados=" + executed.size());
	}

	/**
	 * Hostis (naturalmente), criaturas que a atacam ou estão perseguindo ela, a dona ou as aliadas, e neutras que ficaram
	 * hostis contra ela. Nunca animais passivos, aldeões, companheiras, animais domesticados, jogadores ou aliados.
	 */
	public boolean isEligible(Mob m) {
		if (!m.isAlive() || m.isRemoved() || m instanceof CompanionEntity || m instanceof AbstractVillager || c.isAlliedTo(m)) {
			return false;
		}
		if (m instanceof OwnableEntity pet && pet.getOwnerReference() != null) {
			return false;
		}
		if (m instanceof Enemy) {
			return true;
		}
		LivingEntity t = m.getTarget();
		if (t == null) {
			return false;
		}
		return t == c || c.isOwnedBy(t) || t instanceof CompanionEntity other && c.isAlliedTo(other);
	}

	private double horizontalDistSqr(Entity e) {
		double dx = e.getX() - c.getX();
		double dz = e.getZ() - c.getZ();
		return dx * dx + dz * dz;
	}

	private @Nullable Mob peekTarget(ServerLevel level) {
		while (!queue.isEmpty()) {
			Entity e = level.getEntity(queue.peek());
			if (e instanceof Mob m && isEligible(m)) {
				return m;
			}
			queue.poll(); // morreu, sumiu ou deixou de ser válido
		}
		return null;
	}

	private void strikeNext(ServerLevel level) {
		Mob target = peekTarget(level);
		if (target == null) {
			if (c.tickCount % 20 == 0) {
				c.debug("[demonio] sem alvo; fila=" + queue.size() + " executados=" + executed.size());
			}
			return;
		}
		queue.poll();
		if (executed.add(target.getUUID())) {
			distinctKills++;
		}
		Vec3 from = c.position();
		Vec3 to = arrivalPoint(level, target);
		DemonFx.depart(level, c, from, to);
		if (to.distanceToSqr(from) > 0.01) {
			c.teleportTo(to.x, to.y, to.z);
			hoverBase = to.y;
		}
		face(target.position());
		DemonFx.arrive(level, c, c.position(), SoundEvents.ENDERMAN_TELEPORT);
		c.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT);
		// Eliminação instantânea, pelo fluxo normal de dano (créditos, saque e eventos de morte do jogo); se algo a
		// impedir (chefes com fases), a morte genérica garante o resultado.
		target.hurtServer(level, c.damageSources().mobAttack(c), Float.MAX_VALUE);
		if (target.isAlive()) {
			target.kill(level);
		}
		DemonFx.execute(level, target, SoundEvents.WARDEN_ATTACK_IMPACT);
		kills++;
		c.stats().kills++;
	}

	/** Ponto seguro perto do alvo (sem sufocar, sem lava, sem vazio); se não houver, o mais próximo livre; senão fica onde está. */
	private Vec3 arrivalPoint(ServerLevel level, Mob target) {
		BlockPos spot = SafeSpot.findNear(level, target.blockPosition(), 1, 3, 3, c.getType());
		if (spot == null) {
			spot = SafeSpot.findNear(level, target.blockPosition(), 1, 8, 6, c.getType());
		}
		if (spot == null) {
			return c.position();
		}
		// Flutua meio bloco acima do chão quando há espaço sobre a cabeça.
		BlockPos head = spot.above(2);
		boolean room = level.getBlockState(head).getCollisionShape(level, head).isEmpty();
		return new Vec3(spot.getX() + 0.5, spot.getY() + (room ? 0.5 : 0.0), spot.getZ() + 0.5);
	}

	// ---- exaustão, sono e recuperação

	private void enterExhausted(ServerLevel level, long now) {
		setState(DemonState.EXHAUSTED, now);
		queue.clear();
		executed.clear();
		restoreWeather(level);
		level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.5f, 0.6f);
		if (nightly) {
			c.debug("Modo Demônio: amanheceu (" + kills + " eliminações nesta noite). Descendo e voltando ao normal, sem dormir.");
			returnNearOwner(level);
		} else {
			c.debug("Modo Demônio: fim dos 20 s (" + kills + " eliminações). Descendo para descansar.");
		}
	}

	private void tickExhausted(ServerLevel level, long now, int age) {
		c.setCompanionState(nightly ? CompanionState.FOLLOWING : CompanionState.SLEEPING,
			Component.translatable(nightly ? "detail.autonomouscompanions.demon_dawn" : "detail.autonomouscompanions.demon_exhausted"));
		c.getNavigation().stop();
		c.fallDistance = 0;
		// A flutuação falha aos poucos: desce devagar até tocar o chão (sem dano de queda).
		c.setDeltaMovement(0, -0.05 - Math.min(0.1, age * 0.002), 0);
		if (c.onGround() || c.isInWater() || age >= MAX_DESCENT_TICKS) {
			if (nightly) {
				finish(level); // à noite ela não se cansa: nada de sono depois
			} else {
				enterSleeping(level, now);
			}
		}
	}

	private void enterSleeping(ServerLevel level, long now) {
		c.setNoGravity(false);
		c.fallDistance = 0;
		c.setDeltaMovement(Vec3.ZERO);
		c.setPose(Pose.SLEEPING);
		setState(DemonState.SLEEPING, now);
	}

	private void tickSleeping(ServerLevel level, int age) {
		c.setCompanionState(CompanionState.SLEEPING, Component.translatable("detail.autonomouscompanions.demon_sleeping", (SLEEP_TICKS - age + 19) / 20));
		c.getNavigation().stop();
		if (c.getPose() != Pose.SLEEPING) {
			c.setPose(Pose.SLEEPING);
		}
		Vec3 v = c.getDeltaMovement();
		c.setDeltaMovement(0, Math.min(0, v.y), 0); // deitada: não anda nem é arrastada
		c.fallDistance = 0;
	}

	private void enterRecovering(ServerLevel level, long now) {
		c.setPose(Pose.STANDING);
		removeHealthBoost();
		c.setHealth(c.getMaxHealth());
		setState(DemonState.RECOVERING, now);
		level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1.0f, 0.8f);
	}

	private void tickRecovering(int age) {
		c.setCompanionState(CompanionState.WAITING_INTERACTION, Component.translatable("detail.autonomouscompanions.demon_waking"));
		c.getNavigation().stop();
	}

	private void finish(ServerLevel level) {
		c.setNoGravity(false);
		if (c.getPose() == Pose.SLEEPING) {
			c.setPose(Pose.STANDING);
		}
		removeHealthBoost();
		queue.clear();
		executed.clear();
		setState(DemonState.NORMAL, level.getGameTime());
		c.brain().requestDecision();
		ServerPlayer owner = c.ownerAnywhere();
		if (owner != null) {
			owner.sendSystemMessage(nightly ? Component.translatable("message.autonomouscompanions.demon_dawn", c.getDisplayName(), kills)
				: Component.translatable("message.autonomouscompanions.demon_recovered", c.getDisplayName()));
		}
		c.debug(nightly ? "Modo Demônio: amanheceu e voltou ao normal." : "Modo Demônio: acordou e voltou ao normal.");
		nightly = false;
	}

	// ------------------------------------------------------------------ movimento

	/** Mantém a altura desejada sem gravidade (flutuação suave). */
	private void hover(double targetY, double gain) {
		c.fallDistance = 0;
		if (!c.isNoGravity()) {
			c.setNoGravity(true);
		}
		c.setDeltaMovement(0, Mth.clamp((targetY - c.getY()) * gain, -0.2, 0.2), 0);
	}

	private void face(Vec3 at) {
		double dx = at.x - c.getX();
		double dz = at.z - c.getZ();
		float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		c.setYRot(yaw);
		c.setYHeadRot(yaw);
		c.yBodyRot = yaw;
	}

	private static double smooth(double t) {
		t = Mth.clamp(t, 0.0, 1.0);
		return t * t * (3 - 2 * t);
	}

	// ------------------------------------------------------------------ clima

	/** Chuva com trovoada uma única vez por ativação (só onde há céu); dura o próprio modo e acaba sozinha. */
	private void startStorm(ServerLevel level) {
		weatherChanged = false;
		if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) {
			return; // Nether/End: só a atmosfera de partículas, trovões e relâmpagos visuais
		}
		WeatherData w = level.getServer().getWeatherData();
		prevRaining = w.isRaining();
		prevThundering = w.isThundering();
		prevClear = w.getClearWeatherTime();
		prevRain = w.getRainTime();
		level.getServer().setWeatherParameters(0, DURATION_TICKS + 40, true, true);
		weatherChanged = true;
	}

	private void restoreWeather(ServerLevel level) {
		if (!weatherChanged) {
			return;
		}
		weatherChanged = false;
		// Só desfaz a tempestade se ela ainda for a desta ativação (ao recarregar muito depois, o clima já seguiu sozinho).
		WeatherData w = level.getServer().getWeatherData();
		if (!w.isThundering() || w.getRainTime() > DURATION_TICKS + 40) {
			return;
		}
		if (prevRaining) {
			level.getServer().setWeatherParameters(0, Math.max(prevRain, 200), true, prevThundering);
		} else {
			level.getServer().setWeatherParameters(Math.max(prevClear, 6000), 0, false, false);
		}
	}

	// ------------------------------------------------------------------ persistência

	public void save(ValueOutput out) {
		out.putInt("DemonState", state.ordinal());
		out.putLong("DemonActivated", activatedAt);
		out.putLong("DemonStateStart", stateStart);
		out.putDouble("DemonHover", hoverBase);
		out.putFloat("DemonHealth", c.getHealth());
		out.putBoolean("DemonWeather", weatherChanged);
		out.putBoolean("DemonPrevRain", prevRaining);
		out.putBoolean("DemonPrevThunder", prevThundering);
		out.putInt("DemonPrevClear", prevClear);
		out.putInt("DemonPrevRainTime", prevRain);
		out.putInt("DemonKills", kills);
		out.putBoolean("DemonNightly", nightly);
	}

	/** Recupera um estado válido: reaplica a vida temporária; estados vencidos avançam no próximo tick. */
	public void load(ValueInput in) {
		state = DemonState.byIndex(in.getIntOr("DemonState", 0));
		if (!canHave(c)) {
			state = DemonState.NORMAL;
		}
		activatedAt = in.getLongOr("DemonActivated", 0L);
		stateStart = in.getLongOr("DemonStateStart", 0L);
		hoverBase = in.getDoubleOr("DemonHover", c.getY());
		savedHealth = in.getFloatOr("DemonHealth", DEMON_HEALTH);
		weatherChanged = in.getBooleanOr("DemonWeather", false);
		prevRaining = in.getBooleanOr("DemonPrevRain", false);
		prevThundering = in.getBooleanOr("DemonPrevThunder", false);
		prevClear = in.getIntOr("DemonPrevClear", 0);
		prevRain = in.getIntOr("DemonPrevRainTime", 0);
		kills = in.getIntOr("DemonKills", 0);
		nightly = in.getBooleanOr("DemonNightly", false);
		if (state.boosted()) {
			applyHealthBoost();
			c.setHealth(Math.max(1.0F, savedHealth));
		} else {
			removeHealthBoost();
		}
		c.setNoGravity(state == DemonState.TRANSFORMING || state == DemonState.ACTIVE || state == DemonState.EXHAUSTED);
		if (state == DemonState.SLEEPING) {
			c.setPose(Pose.SLEEPING);
		}
		c.setDemonStateSynced(state);
	}

	/** Lista e conjunto da caçada (para verificar que nada cresce sem controle). */
	public int trackedTargets() {
		return queue.size() + executed.size();
	}

	/** Teste/depuração: tempo restante do estado atual em ticks (estimado). */
	public long remaining(long now) {
		return switch (state) {
			case TRANSFORMING, ACTIVE -> DURATION_TICKS - (now - activatedAt);
			case SLEEPING -> SLEEP_TICKS - (now - stateStart);
			case RECOVERING -> RECOVER_TICKS - (now - stateStart);
			default -> 0;
		};
	}
}
