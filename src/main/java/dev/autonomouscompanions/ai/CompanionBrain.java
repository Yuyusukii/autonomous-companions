package dev.autonomouscompanions.ai;

import dev.autonomouscompanions.ai.behavior.DefendBehavior;
import dev.autonomouscompanions.ai.behavior.RecallBehavior;
import dev.autonomouscompanions.ai.behavior.UnstuckBehavior;
import dev.autonomouscompanions.ai.behavior.EatBehavior;
import dev.autonomouscompanions.ai.behavior.FleeBehavior;
import dev.autonomouscompanions.ai.behavior.FollowOwnerBehavior;
import dev.autonomouscompanions.ai.behavior.IdleExploreBehavior;
import dev.autonomouscompanions.ai.behavior.OrderBehavior;
import dev.autonomouscompanions.ai.behavior.RoleWorkBehavior;
import dev.autonomouscompanions.ai.behavior.SleepBehavior;
import dev.autonomouscompanions.ai.behavior.StayBehavior;
import dev.autonomouscompanions.ai.behavior.WaitInteractionBehavior;
import dev.autonomouscompanions.combat.ThreatTracker;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.coop.Cooperation;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.ExpeditionChunkLoader;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.nav.SafeSpot;
import dev.autonomouscompanions.task.Order;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Máquina de decisão. Executa no máximo um comportamento por vez; a cada {@code intervaloDecisaoTicks} recalcula as
 * prioridades e troca de comportamento quando outro se torna mais importante (com histerese para evitar oscilação).
 */
public final class CompanionBrain {
	private final CompanionEntity companion;
	private final Navigator navigator;
	private final ThreatTracker threats;
	private final ExpeditionChunkLoader chunkLoader;
	private final List<Behavior> behaviors = new ArrayList<>();
	private final OrderBehavior orderBehavior;
	private @Nullable Behavior active;
	private int activePriority;
	private int decisionTimer;
	private int errorStreak;
	private long recallUntil;
	private boolean returnAfterOrder;
	private String lastErrorKey = "";
	private Vec3 ownerLastPos = Vec3.ZERO;
	private int ownerStillTicks;
	private long noRouteSince = -1;
	private double noRouteBest;
	private long lastDimensionFollow;

	public CompanionBrain(CompanionEntity companion) {
		this.companion = companion;
		this.navigator = new Navigator(companion);
		this.threats = new ThreatTracker(companion);
		this.chunkLoader = new ExpeditionChunkLoader(companion);
		this.orderBehavior = new OrderBehavior(companion, this);
		if (companion.isHelper()) {
			// A ajudante não recebe ordens nem tem função: foge, se defende e faz a segurança das três (nas regras antigas, come,
			// dorme e as abastece); sem nada a fazer, acompanha a dona.
			behaviors.add(new UnstuckBehavior(companion, this));
			behaviors.add(new FleeBehavior(companion, this));
			behaviors.add(new DefendBehavior(companion, this));
			behaviors.add(new dev.autonomouscompanions.ai.behavior.GuardPostBehavior(companion, this));
			behaviors.add(new EatBehavior(companion, this));
			behaviors.add(new SleepBehavior(companion, this));
			behaviors.add(new dev.autonomouscompanions.ai.behavior.SupplierBehavior(companion, this));
			behaviors.add(new dev.autonomouscompanions.ai.behavior.LeaveWaterBehavior(companion, this));
			behaviors.add(new FollowOwnerBehavior(companion, this));
			return;
		}
		behaviors.add(new RecallBehavior(companion, this));
		behaviors.add(new UnstuckBehavior(companion, this));
		behaviors.add(new FleeBehavior(companion, this));
		behaviors.add(new DefendBehavior(companion, this));
		behaviors.add(new EatBehavior(companion, this));
		behaviors.add(new SleepBehavior(companion, this));
		behaviors.add(orderBehavior);
		behaviors.add(new RoleWorkBehavior(companion, this));
		behaviors.add(new StayBehavior(companion, this));
		behaviors.add(new dev.autonomouscompanions.ai.behavior.LeaveWaterBehavior(companion, this));
		behaviors.add(new FollowOwnerBehavior(companion, this));
		behaviors.add(new IdleExploreBehavior(companion, this));
		behaviors.add(new WaitInteractionBehavior(companion, this));
	}

	public CompanionEntity companion() {
		return companion;
	}

	public Navigator navigator() {
		return navigator;
	}

	public ThreatTracker threats() {
		return threats;
	}

	public ExpeditionChunkLoader chunkLoader() {
		return chunkLoader;
	}

	public OrderBehavior orders() {
		return orderBehavior;
	}

	public int ownerStillTicks() {
		return ownerStillTicks;
	}

	private final AgentMetrics metrics = new AgentMetrics();

	public AgentMetrics metrics() {
		return metrics;
	}

	public  Behavior active() {
		return active;
	}

	public void tick(ServerLevel level) {
		trackOwner(level);
		threats.tick(level);
		chunkLoader.tick(level);

		if (--decisionTimer <= 0) {
			decisionTimer = ConfigManager.get().intervaloDecisaoTicks;
			decide(level);
		}
		if (active != null) {
			boolean keep;
			try {
				keep = active.tick(level);
				errorStreak = 0;
			} catch (RuntimeException e) {
				// Registra a pilha completa uma vez; repetições viram uma linha curta. Erros seguidos na execução de uma
				// ordem a marcam como falha para não repetir o mesmo erro indefinidamente.
				String key = active.name() + ":" + e;
				if (!key.equals(lastErrorKey)) {
					lastErrorKey = key;
					errorStreak = 0;
					dev.autonomouscompanions.AutonomousCompanions.LOGGER.error("Erro no comportamento {} de {}", active.name(), companion.getStringUUID(), e);
				} else if (errorStreak % 200 == 0) {
					dev.autonomouscompanions.AutonomousCompanions.LOGGER.error("Erro repetido ({}x) no comportamento {} de {}: {}", errorStreak, active.name(),
						companion.getStringUUID(), e.toString());
				}
				errorStreak++;
				if (active == orderBehavior && errorStreak >= 3) {
					orderBehavior.failActiveWithError(level);
					errorStreak = 0;
				}
				keep = false;
			}
			if (!keep) {
				active.stop(level);
				active = null;
				activePriority = 0;
				decisionTimer = 0;
			}
		}
	}

	// ------------------------------------------------------------------ apito e retorno

	/**
	 * Chamado pelo apito: interrompe a ordem atual (obras são suspensas para não perder o projeto; as demais são
	 * canceladas), suspende as ordens na fila, sai do modo "ficar aqui" e vai até a dona o mais rápido possível.
	 */
	public void recall(ServerPlayer player) {
		if (companion.demon().engaged()) {
			return; // Modo Demônio ou sono depois dele: não atende ao apito
		}
		ServerLevel level = (ServerLevel) companion.level();
		for (dev.autonomouscompanions.task.Order o : new java.util.ArrayList<>(companion.orders().all())) {
			if (o.status().isFinished() || o.status() == dev.autonomouscompanions.task.OrderEnums.OrderStatus.SUSPENDED) {
				continue;
			}
			// Suspende (não cancela): o progresso e os itens já obtidos continuam reservados para o pedido, que pode ser
			// retomado na aba Estado. Antes a ordem atual era cancelada e o que ela tinha juntado deixava de ser do pedido.
			orderBehavior.suspend(level, o);
		}
		companion.settings().stay = false;
		companion.settings().stayPos = null;
		if (companion.isSleeping()) {
			companion.stopSleeping();
		}
		recallUntil = level.getGameTime() + 20 * 60;
		clearRest();
		returnAfterOrder = false;
		companion.debug("Chamada pelo apito.");
		if (companion.level() != player.level()) {
			bringNearOwner(player);
		}
		requestDecision();
	}

	/**
	 * Depois de atender ao apito, ela fica com a dona por 3 minutos: sem explorar à toa, sem trabalho da função e sem sair
	 * para caçar comida (só se a fome for crítica). Uma ordem nova encerra a espera na hora (ordens têm prioridade maior).
	 */
	private long holdUntil;

	/** Descanso pedido (botão Descansar) ou interrompido por perigo: volta a descansar quando o perigo passar. */
	private long restSince = -1;

	public void requestRest(ServerLevel level) {
		if (restSince < 0) {
			restSince = level.getGameTime();
		}
	}

	public boolean restRequested() {
		return restSince >= 0;
	}

	public long restSince() {
		return restSince;
	}

	public void clearRest() {
		restSince = -1;
	}

	public void holdNearOwner(ServerLevel level) {
		holdUntil = level.getGameTime() + 20 * 180;
	}

	public boolean isHoldingNearOwner(ServerLevel level) {
		return holdUntil > level.getGameTime() && companion.orders().active() == null;
	}

	public boolean isRecalled(ServerLevel level) {
		return recallUntil > level.getGameTime();
	}

	public void endRecall() {
		recallUntil = 0;
	}

	/** Leva a companheira a um lugar seguro ao lado da dona (também entre dimensões). */
	public boolean bringNearOwner(ServerPlayer owner) {
		ServerLevel target = (ServerLevel) owner.level();
		BlockPos safe = SafeSpot.findNear(target, owner.blockPosition(), 1, 5, 3, companion.getType());
		if (safe == null) {
			return false;
		}
		navigator.clear();
		if (target != companion.level()) {
			companion.teleportTo(target, safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, java.util.Set.<Relative>of(), companion.getYRot(),
				companion.getXRot(), false);
		} else {
			companion.teleportTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);
		}
		companion.debug("Trazida para perto da dona em " + safe.toShortString());
		return true;
	}

	/** Ao concluir (ou falhar) uma ordem: volta para perto da dona antes de retomar outras atividades. */
	public void markReturnAfterOrder() {
		returnAfterOrder = true;
	}

	public boolean isReturningAfterOrder() {
		return returnAfterOrder;
	}

	public void endReturnAfterOrder() {
		returnAfterOrder = false;
	}

	/** Interrompe o comportamento atual (ex.: o Modo Demônio assume o controle). */
	public void interruptAll(ServerLevel level) {
		if (active != null) {
			active.stop(level);
			active = null;
			activePriority = 0;
		}
		navigator.clear();
		threats.clearTarget();
	}

	/** Teste/depuração: o comportamento deste tipo, se ela tiver um. */
	public <T extends Behavior> @org.jspecify.annotations.Nullable T behavior(Class<T> type) {
		for (Behavior b : behaviors) {
			if (type.isInstance(b)) {
				return type.cast(b);
			}
		}
		return null;
	}

	/** Força uma reavaliação imediata (ex.: nova ordem recebida). */
	public void requestDecision() {
		decisionTimer = 0;
	}

	private void decide(ServerLevel level) {
		Behavior best = null;
		int bestPriority = 0;
		for (Behavior b : behaviors) {
			int p = b == active ? Math.max(b.priority(level), 0) : b.priority(level);
			if (p > bestPriority) {
				bestPriority = p;
				best = b;
			}
		}
		if (best == active) {
			activePriority = bestPriority;
			return;
		}
		if (active != null) {
			int currentPriority = active.priority(level);
			if (currentPriority > 0 && bestPriority <= currentPriority + active.hysteresis()) {
				activePriority = currentPriority;
				return;
			}
			active.stop(level);
			navigator.clear();
		}
		if (active == orderBehavior && best != null && orderBehavior.hasWork()) {
			// A ordem foi interrompida: registra por quem (para o diagnóstico "por que ela parou").
			metrics.orderInterrupted(best.name() + " (prioridade " + bestPriority + ", tick " + level.getGameTime() + ")");
		}
		metrics.behaviorSwitched();
		active = best;
		activePriority = bestPriority;
		if (active != null) {
			companion.debug("Comportamento -> " + active.name() + " (prioridade " + bestPriority + ")");
			active.start(level);
		}
	}

	private void trackOwner(ServerLevel level) {
		ServerPlayer owner = companion.ownerInLevel();
		if (owner != null) {
			companion.markOwnerSeen();
			Vec3 pos = owner.position();
			if (pos.distanceToSqr(ownerLastPos) < 0.04) {
				ownerStillTicks++;
			} else {
				ownerStillTicks = 0;
				ownerLastPos = pos;
			}
			return;
		}
		ownerStillTicks = 0;
		followAcrossDimensions(level);
	}

	/**
	 * Se o dono trocou de dimensão (portal, comando) estando perto e ela estava apenas acompanhando, ela o segue
	 * para a nova dimensão, posicionada em local seguro. Nunca durante missões.
	 */
	private void followAcrossDimensions(ServerLevel level) {
		if (!companion.isNamed() || companion.settings().stay || orderBehavior.hasWork() || companion.isSleeping()) {
			return;
		}
		ServerPlayer owner = companion.ownerAnywhere();
		if (owner == null || owner.level() == level) {
			return;
		}
		long now = level.getGameTime();
		if (now - companion.lastOwnerSeenTick() > 100 || now - lastDimensionFollow < 200) {
			return;
		}
		if (ownerLastPos.distanceTo(companion.position()) > 32) {
			return;
		}
		ServerLevel target = (ServerLevel) owner.level();
		BlockPos safe = SafeSpot.findNear(target, owner.blockPosition(), 1, 4, 3, companion.getType());
		if (safe == null) {
			return;
		}
		lastDimensionFollow = now;
		companion.debug("Seguindo o dono para " + target.dimension().identifier());
		companion.teleportTo(target, safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, java.util.Set.<Relative>of(), companion.getYRot(),
			companion.getXRot(), false);
	}

	/** Recuperação controlada para situações excepcionais (presa sem rota por muito tempo, longe do dono, sem missão). */
	public boolean tickNoRouteRecovery(ServerLevel level, boolean noRoute) {
		ServerPlayer owner = companion.ownerInLevel();
		if (owner == null) {
			noRouteSince = -1;
			return false;
		}
		// Conta o tempo desde a primeira falha de rota, e não as chamadas com falha: o navegador só devolve "falhou" num
		// tick e recomeça a tentar no seguinte, então o contador antigo voltava a zero e a recuperação nunca acontecia.
		long now = level.getGameTime();
		double dist = owner.distanceTo(companion);
		if (noRouteSince < 0) {
			if (noRoute) {
				noRouteSince = now;
				noRouteBest = dist;
			}
			return false;
		}
		// Aproximou-se de verdade da dona: a rota voltou a funcionar.
		if (dist < noRouteBest - 8) {
			noRouteSince = -1;
			return false;
		}
		var cfg = ConfigManager.get();
		if (now - noRouteSince < cfg.segundosAntesDaRecuperacao * 20L || dist < cfg.distanciaRecuperacaoEmergencia) {
			return false;
		}
		BlockPos safe = SafeSpot.findNear(level, owner.blockPosition(), 2, 6, 3, companion.getType());
		if (safe == null) {
			return false;
		}
		noRouteSince = -1;
		navigator.clear();
		companion.teleportTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);
		owner.sendOverlayMessage(Component.translatable("message.autonomouscompanions.recovered", companion.getDisplayName()));
		companion.debug("Recuperação controlada: reposicionada perto do dono após ficar sem rota.");
		return true;
	}

	/** Chegou perto da dona: não está mais sem rota. */
	public void clearNoRoute() {
		noRouteSince = -1;
	}

	public void onHurt(DamageSource source) {
		if (source.getEntity() instanceof LivingEntity attacker && attacker != companion) {
			threats.onAttackedBy(attacker);
		}
		requestDecision();
	}

	public void onMoved(double distance) {
		Order order = companion.orders().active();
		if (order != null && active == orderBehavior) {
			order.progress().distance += distance;
		}
	}

	/** Libera recursos externos (tickets de chunk, reservas de área) quando a entidade sai do mundo. */
	public void release() {
		chunkLoader.release();
		if (companion.level() instanceof ServerLevel level) {
			Cooperation.get(level.getServer()).releaseAll(companion.getUUID());
		}
		if (active != null && companion.level() instanceof ServerLevel level) {
			active.stop(level);
		}
		active = null;
	}

	public void save(ValueOutput out) {
		out.putInt("OwnerStill", ownerStillTicks);
		orderBehavior.save(out.child("OrderRuntime"));
	}

	public void load(ValueInput in) {
		ownerStillTicks = in.getIntOr("OwnerStill", 0);
		in.child("OrderRuntime").ifPresent(orderBehavior::load);
	}
}
