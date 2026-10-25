package dev.autonomouscompanions.mind.skills;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.InventoryManager;
import dev.autonomouscompanions.mind.memory.WorldMemory;
import dev.autonomouscompanions.mind.perception.Perception;
import dev.autonomouscompanions.nav.Navigator;
import dev.autonomouscompanions.task.exec.ItemTarget;
import dev.autonomouscompanions.task.exec.Step;
import dev.autonomouscompanions.task.exec.StepResult;
import dev.autonomouscompanions.task.exec.TaskContext;
import dev.autonomouscompanions.task.exec.steps.CompositeStep;
import dev.autonomouscompanions.task.exec.steps.EnsureToolStep;
import dev.autonomouscompanions.task.exec.steps.ObtainStep;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Execução de uma {@link Skill}: confere os pré-requisitos antes de cada ação de obtenção, executa cada ação com as etapas
 * reais do mod (o planejador decide minerar, fabricar, caçar ou fundir), aplica a recuperação prevista quando uma etapa
 * falha (até 3 vezes), confere a condição de sucesso no inventário (refaz o procedimento uma vez se não bater) e registra
 * a experiência. Antes de começar, consulta a memória: se a mesma habilidade falhou há menos de 30 min por não achar o
 * recurso, já começa procurando mais longe — uma falha recente muda a estratégia, mas não vira regra para sempre.
 */
public final class SkillStep extends CompositeStep {
	private static final int MAX_RECOVERIES = 3;
	private static final long RECENT = 20 * 60 * 30;
	private final Skill skill;
	private final int quantity;
	private int index;
	private int recoveries;
	private int rounds;
	private int waitTicks;
	private int safetyWaits;
	private int healthWaits;
	private long startedAt = -1;
	private boolean toolTried;
	private boolean roomTried;

	public SkillStep(Skill skill, int quantity) {
		this.skill = skill;
		this.quantity = Math.max(1, quantity);
	}

	public Skill skill() {
		return skill;
	}

	@Override
	protected boolean isOptional(Step child) {
		return child instanceof EnsureToolStep; // pré-requisito: se não der, a própria checagem decide (não derruba a etapa)
	}

	@Override
	public void onChildFinished(TaskContext ctx, Step child, StepResult result) {
		super.onChildFinished(ctx, child, result);
		if (result.isSuccess() && !(child instanceof EnsureToolStep)) {
			index++;
		}
	}

	private static int count(TaskContext ctx, ItemTarget target, boolean includeReserved) {
		return target.count(ctx.companion(), includeReserved);
	}

	@Override
	protected StepResult run(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		ServerLevel level = ctx.level();
		if (startedAt < 0) {
			startedAt = level.getGameTime();
			c.debug("Habilidade \"" + skill.name() + "\" (" + quantity + "): começando.");
			learnFromPast(ctx);
		}
		if (waitTicks > 0) {
			waitTicks--;
			return StepResult.running();
		}
		if (index >= skill.procedure().size()) {
			return finish(ctx);
		}
		Skill.Action a = skill.procedure().get(index);
		if (a.type().equals("obter") || a.type().equals("fabricar") || a.type().equals("fundir")) {
			StepResult pre = requirements(ctx);
			if (pre != null) {
				return pre;
			}
		}
		int q = Skill.resolve(a.quantity(), quantity);
		ItemTarget item = Skill.target(a.item());
		boolean mission = a.item() != null && skill.success() != null && a.item().equals(skill.success().item());
		switch (a.type()) {
			case "avaliar" -> {
				Perception.Snapshot s = Perception.of(c);
				c.debug("Habilidade \"" + skill.name() + "\": avaliando — vida " + (int) s.health() + ", ferramentas " + s.bestTools() + ", espaços livres "
					+ s.freeSlots() + (s.dangers().isEmpty() ? "" : ", perigos " + s.dangers()) + ".");
				index++;
				return StepResult.running();
			}
			case "verificar" -> {
				if (item != null && count(ctx, item, mission) >= q) {
					index++;
					return StepResult.running();
				}
				return recover(ctx, StepResult.fail(StepResult.FailureCode.ITEMS_MISSING, Component.literal("faltam itens na verificação")));
			}
			case "obter", "fabricar", "fundir" -> {
				if (item == null || count(ctx, item, mission) >= q) {
					index++;
					return StepResult.running();
				}
				c.setCompanionState(CompanionState.PLANNING, Component.literal(skill.name() + ": " + a.description()));
				c.debug("Habilidade \"" + skill.name() + "\", etapa " + (index + 1) + ": " + a.description() + " (" + q + "× " + a.item() + ").");
				return StepResult.push(new ObtainStep(item, q, mission, Set.of()));
			}
			case "ir_para_base" -> {
				return goToBase(ctx);
			}
			default -> {
				index++;
				return StepResult.running();
			}
		}
	}

	private StepResult goToBase(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		var base = WorldMemory.get(ctx.level().getServer()).base(c.getUUID()).filter(p -> p.dimension().equals(ctx.level().dimension()));
		if (base.isEmpty()) {
			c.debug("Habilidade \"" + skill.name() + "\": nenhuma base marcada; etapa pulada.");
			index++;
			return StepResult.running();
		}
		BlockPos target = base.get().pos();
		Navigator nav = ctx.brain().navigator();
		if (nav.distanceTo(target) <= 4) {
			nav.clear();
			index++;
			return StepResult.running();
		}
		nav.setGoal(target, 3.0, 1.3);
		if (nav.tick() == Navigator.Status.FAILED) {
			nav.clear();
			return recover(ctx, StepResult.fail(StepResult.FailureCode.NO_ROUTE, Component.literal("sem caminho até a base")));
		}
		return StepResult.running();
	}

	/** Pré-requisitos: devolve uma etapa a executar antes, uma espera, uma falha — ou nulo se estiver tudo certo. */
	private @Nullable StepResult requirements(TaskContext ctx) {
		CompanionEntity c = ctx.companion();
		for (Skill.Requirement r : skill.requirements()) {
			switch (r.type()) {
				case "ferramenta_para" -> {
					Block b = Skill.block(r);
					if (b != null && !EnsureToolStep.ownsSuitable(ctx, b.defaultBlockState(), true)) {
						if (!toolTried) {
							toolTried = true;
							c.debug("Habilidade \"" + skill.name() + "\": falta o pré-requisito \"" + r.description() + "\"; fazendo ou obtendo a ferramenta.");
							return StepResult.push(new EnsureToolStep(b.defaultBlockState(), true, Set.of()));
						}
						return end(ctx, false, StepResult.FailureCode.IMPOSSIBLE, "pré-requisito não atendido: " + r.description());
					}
				}
				case "espaco_livre" -> {
					if (c.inventory().emptySlots() < r.minimum() && !roomTried) {
						roomTried = true;
						boolean freed = InventoryManager.makeRoom(ctx);
						c.debug("Habilidade \"" + skill.name() + "\": pouco espaço na bolsa; " + (freed ? "descartou itens permitidos." : "nada que possa descartar."));
					}
				}
				case "seguranca" -> {
					if (ctx.brain().threats().hostilesNearSelf() > 0 && safetyWaits < 30) {
						safetyWaits++;
						waitTicks = 20;
						c.debug("Habilidade \"" + skill.name() + "\": monstros por perto; esperando a situação ficar segura.");
						return StepResult.running();
					}
				}
				case "vida_minima" -> {
					if (c.getHealth() < r.minimum() && healthWaits < 30) {
						healthWaits++;
						waitTicks = 20;
						return StepResult.running();
					}
				}
				default -> {
				}
			}
		}
		return null;
	}

	@Override
	protected StepResult onFailure(TaskContext ctx, StepResult failure) {
		return recover(ctx, failure);
	}

	/** Etapa falhou: aplica a recuperação prevista para o motivo (e repete a etapa), ou encerra com o motivo. */
	private StepResult recover(TaskContext ctx, StepResult failure) {
		CompanionEntity c = ctx.companion();
		Skill.Action a = index < skill.procedure().size() ? skill.procedure().get(index) : null;
		Skill.Recovery r = skill.recoveryFor(failure.code());
		String why = failure.reason() != null ? failure.reason().getString() : String.valueOf(failure.code());
		if (r != null && !r.action().equals("desistir") && recoveries < MAX_RECOVERIES) {
			recoveries++;
			c.brain().metrics().skillRecovered();
			c.debug("Habilidade \"" + skill.name() + "\", etapa " + (index + 1) + " falhou (" + why + "). Recuperação: " + r.description());
			switch (r.action()) {
				case "ampliar_busca" -> ctx.widenSearch();
				case "liberar_espaco" -> InventoryManager.makeRoom(ctx);
				case "aguardar_seguranca" -> waitTicks = 100;
				default -> {
				}
			}
			return StepResult.running(); // repete a mesma etapa
		}
		if (a != null && a.optional()) {
			c.debug("Habilidade \"" + skill.name() + "\": etapa opcional " + (index + 1) + " falhou (" + why + "); seguindo.");
			index++;
			return StepResult.running();
		}
		return end(ctx, false, failure.code() != null ? failure.code() : StepResult.FailureCode.IMPOSSIBLE,
			"etapa " + (index + 1) + (a == null ? "" : " (" + a.description() + ")") + ": " + why);
	}

	private StepResult finish(TaskContext ctx) {
		Skill.Success s = skill.success();
		ItemTarget item = skill.successTarget();
		int need = s == null ? 0 : Skill.resolve(s.minimum(), quantity);
		if (s == null || item == null || count(ctx, item, true) >= need) {
			return end(ctx, true, null, "");
		}
		if (++rounds < 2) {
			ctx.companion().debug("Habilidade \"" + skill.name() + "\": condição de sucesso não atingida; refazendo o procedimento.");
			index = 0;
			return StepResult.running();
		}
		return end(ctx, false, StepResult.FailureCode.ITEMS_MISSING, "condição de sucesso não atingida (" + count(ctx, item, true) + "/" + need + ")");
	}

	private StepResult end(TaskContext ctx, boolean success, StepResult.@Nullable FailureCode code, String reason) {
		CompanionEntity c = ctx.companion();
		long now = ctx.level().getGameTime();
		String full = success ? "" : (code == null ? "" : code.name() + ": ") + reason;
		WorldMemory.get(ctx.level().getServer()).recordExperience(c.getUUID(), new WorldMemory.Experience(now, "HABILIDADE", skill.id(), success, full,
			c.blockPosition(), (int) Math.max(0, now - startedAt)));
		c.debug("Habilidade \"" + skill.name() + "\": " + (success ? "concluída." : "falhou — " + full));
		return success ? StepResult.success() : StepResult.fail(code == null ? StepResult.FailureCode.IMPOSSIBLE : code,
			Component.literal("Habilidade " + skill.name() + ": " + reason));
	}

	/** Experiência recente da mesma habilidade muda o começo (sem virar regra: só vale por 30 min). */
	private void learnFromPast(TaskContext ctx) {
		var stat = WorldMemory.get(ctx.level().getServer()).stat(ctx.companion().getUUID(), "HABILIDADE|" + skill.id());
		long now = ctx.level().getGameTime();
		if (stat != null && stat.lastFailure() >= 0 && now - stat.lastFailure() < RECENT && stat.lastFailure() > stat.lastSuccess()
			&& stat.lastReason().startsWith("NOT_FOUND")) {
			ctx.widenSearch();
			ctx.companion().brain().metrics().learnedAdjustment();
			ctx.companion().debug(String.format("Habilidade \"%s\": da última vez (há %.1f min) não achei o recurso; já começo procurando mais longe.",
				skill.name(), (now - stat.lastFailure()) / 1200.0));
		}
	}

	@Override
	public Component describe() {
		return Component.literal("Habilidade: " + skill.name());
	}

	@Override
	public CompanionState state() {
		return CompanionState.PLANNING;
	}
}
