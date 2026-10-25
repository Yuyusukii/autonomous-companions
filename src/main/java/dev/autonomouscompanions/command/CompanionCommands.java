package dev.autonomouscompanions.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.SafeSpot;
import dev.autonomouscompanions.persist.CompanionWorldData;
import dev.autonomouscompanions.registry.ModEntities;
import dev.autonomouscompanions.spawn.StarterSpawner;
import dev.autonomouscompanions.survival.DeathRecovery;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.OrderService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Comandos de diagnóstico e controle: {@code /companheiras} (atalho {@code /companions}). Não substituem a interface;
 * servem para testes e para destravar situações. Jogadores comuns só agem sobre as próprias companheiras.
 */
public final class CompanionCommands {
	private CompanionCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = build("companheiras");
		dispatcher.register(root);
		dispatcher.register(build("companions"));
	}

	private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, builder) -> {
		List<String> names = new ArrayList<>();
		for (CompanionEntity c : visible(ctx.getSource())) {
			if (c.isNamed()) {
				names.add(c.companionName().contains(" ") ? "\"" + c.companionName() + "\"" : c.companionName());
			}
		}
		for (var r : CompanionWorldData.get(ctx.getSource().getServer()).deadRecords()) {
			names.add(r.name().contains(" ") ? "\"" + r.name() + "\"" : r.name());
		}
		return SharedSuggestionProvider.suggest(names, builder);
	};

	private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
		return Commands.literal(name)
			.then(Commands.literal("versao").executes(ctx -> {
				String v = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(AutonomousCompanions.MOD_ID)
					.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
				ctx.getSource().sendSuccess(() -> Component.literal("Autonomous Companions " + v + " (Minecraft 26.3, Fabric)"), false);
				return 1;
			}))
			.then(Commands.literal("lista").executes(CompanionCommands::list))
			.then(Commands.literal("apito").executes(ctx -> {
				StarterSpawner.giveWhistle(ctx.getSource().getPlayerOrException());
				return 1;
			}))
			.then(Commands.literal("estado").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES).executes(CompanionCommands::state)))
			.then(Commands.literal("missao").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES).executes(CompanionCommands::mission)))
			.then(Commands.literal("progresso").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES).executes(CompanionCommands::mission)))
			.then(Commands.literal("diagnostico").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES).executes(CompanionCommands::diagnose)))
			.then(Commands.literal("percepcao").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES).executes(CompanionCommands::perception)))
			.then(Commands.literal("memoria").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES).executes(CompanionCommands::memory)))
			.then(Commands.literal("base").executes(CompanionCommands::setBase))
			.then(Commands.literal("saber").then(Commands.argument("pergunta", StringArgumentType.greedyString()).executes(ctx -> {
				var src = ctx.getSource();
				for (String line : dev.autonomouscompanions.mind.knowledge.KnowledgeBase.answer(src.getServer(), StringArgumentType.getString(ctx, "pergunta"))) {
					src.sendSuccess(() -> Component.literal(line), false);
				}
				return 1;
			})))
			.then(Commands.literal("habilidades").executes(ctx -> {
				var src = ctx.getSource();
				var all = dev.autonomouscompanions.mind.skills.SkillLibrary.all();
				src.sendSuccess(() -> Component.literal("== Habilidades (" + all.size() + ")"), false);
				for (var sk : all) {
					String line = sk.id() + " — " + sk.name() + (sk.goal().isEmpty() ? "" : ": " + sk.goal()) + (sk.reactive() ? " [reativa]" : "")
						+ (sk.valid() ? "" : " [INVÁLIDA: " + String.join("; ", sk.problems()) + "]");
					src.sendSuccess(() -> Component.literal(line), false);
				}
				return all.size();
			}))
			.then(Commands.literal("habilidade_ver").then(Commands.argument("habilidade", StringArgumentType.word()).suggests(SKILLS).executes(ctx -> {
				var src = ctx.getSource();
				String id = StringArgumentType.getString(ctx, "habilidade");
				var sk = dev.autonomouscompanions.mind.skills.SkillLibrary.get(id);
				if (sk == null) {
					sk = dev.autonomouscompanions.mind.skills.SkillLibrary.find(id);
				}
				if (sk == null) {
					src.sendFailure(Component.literal("Habilidade desconhecida: " + id));
					return 0;
				}
				for (String line : sk.describe()) {
					src.sendSuccess(() -> Component.literal(line), false);
				}
				return 1;
			})))
			.then(Commands.literal("habilidade").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES)
				.then(Commands.argument("habilidade", StringArgumentType.word()).suggests(SKILLS)
					.executes(ctx -> skillOrder(ctx, 0))
					.then(Commands.argument("quantidade", IntegerArgumentType.integer(1, OrderService.MAX_COUNT))
						.executes(ctx -> skillOrder(ctx, IntegerArgumentType.getInteger(ctx, "quantidade")))))))
			.then(Commands.literal("catalogo")
				.then(Commands.literal("exportar").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
					var src = ctx.getSource();
					src.sendSuccess(() -> Component.literal("Gerando o catálogo da versão carregada..."), false);
					dev.autonomouscompanions.mind.knowledge.GameCatalog.export(src.getServer(), true).whenComplete((r, e) -> src.getServer().execute(() -> {
						if (e != null) {
							src.sendFailure(Component.literal("Falha ao gerar o catálogo: " + e.getMessage()));
						} else {
							src.sendSuccess(() -> Component.literal("Catálogo gravado em " + r.folder() + " (" + r.millis() + " ms): " + r.counts()), false);
						}
					}));
					return 1;
				}))
				.then(Commands.literal("ver").then(Commands.argument("texto", StringArgumentType.greedyString()).executes(ctx -> {
					var src = ctx.getSource();
					for (String line : dev.autonomouscompanions.mind.knowledge.Lookup.explain(StringArgumentType.getString(ctx, "texto"))) {
						src.sendSuccess(() -> Component.literal(line), false);
					}
					return 1;
				}))))
			.then(Commands.literal("esquecer_recursos").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
				// Esquece as posições de recursos vistas pela equipe e as hipóteses de área (a memória pessoal fica).
				dev.autonomouscompanions.mind.memory.WorldMemory.get(ctx.getSource().getServer()).clearTeamKnowledge();
				ctx.getSource().sendSuccess(() -> Component.literal("Memória de recursos da equipe esquecida."), true);
				return 1;
			}))
			.then(Commands.literal("inventario").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES)
				.executes(CompanionCommands::inventory)))
			.then(Commands.literal("cancelar").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES).executes(CompanionCommands::cancel)))
			.then(Commands.literal("recuperar").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES).executes(CompanionCommands::recover)))
			.then(Commands.literal("reviver").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES).executes(CompanionCommands::revive)))
			.then(Commands.literal("depurar").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES)
				.then(Commands.argument("ativo", BoolArgumentType.bool()).executes(CompanionCommands::debug))))
			.then(Commands.literal("ordem").then(Commands.argument("nome", StringArgumentType.string()).suggests(NAMES)
				.then(Commands.argument("bloco", StringArgumentType.string())
					.then(Commands.argument("quantidade", IntegerArgumentType.integer(1, OrderService.MAX_COUNT)).executes(CompanionCommands::order)))))
			.then(Commands.literal("config").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("ver").executes(ctx -> {
					ctx.getSource().sendSuccess(() -> Component.literal(ConfigManager.toJson()), false);
					return 1;
				}))
				.then(Commands.literal("recarregar").executes(ctx -> {
					ConfigManager.load();
					ctx.getSource().sendSuccess(() -> Component.translatable("command.autonomouscompanions.config_reloaded"), true);
					return 1;
				}))
				.then(Commands.literal("definir").then(Commands.argument("chave", StringArgumentType.word())
					.then(Commands.argument("valor", StringArgumentType.greedyString()).executes(ctx -> {
						String key = StringArgumentType.getString(ctx, "chave");
						String value = StringArgumentType.getString(ctx, "valor");
						if (ConfigManager.setField(key, value)) {
							ctx.getSource().sendSuccess(() -> Component.translatable("command.autonomouscompanions.config_set", key, value), true);
							return 1;
						}
						ctx.getSource().sendFailure(Component.translatable("command.autonomouscompanions.config_invalid", key));
						return 0;
					})))))
			.then(Commands.literal("criar_ajudante").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
				ServerPlayer player = ctx.getSource().getPlayerOrException();
				if (StarterSpawner.spawnHelper(player) == null) {
					ctx.getSource().sendFailure(Component.translatable("command.autonomouscompanions.no_safe_spot"));
					return 0;
				}
				return 1;
			}))
			.then(Commands.literal("criar_iniciais").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
				ServerPlayer player = ctx.getSource().getPlayerOrException();
				var list = StarterSpawner.spawnStarters(player);
				if (list == null) {
					ctx.getSource().sendFailure(Component.translatable("command.autonomouscompanions.no_safe_spot"));
					return 0;
				}
				CompanionWorldData data = CompanionWorldData.get(ctx.getSource().getServer());
				data.markStartersGiven(player.getUUID());
				list.forEach(data::updateCompanion);
				ctx.getSource().sendSuccess(() -> Component.translatable("command.autonomouscompanions.spawned", list.size()), true);
				return list.size();
			}));
	}

	// ------------------------------------------------------------------ busca

	private static boolean isAdmin(CommandSourceStack src) {
		return Commands.LEVEL_GAMEMASTERS.check(src.permissions());
	}

	private static List<CompanionEntity> visible(CommandSourceStack src) {
		List<CompanionEntity> list = new ArrayList<>();
		ServerPlayer player = src.getPlayer();
		boolean admin = isAdmin(src);
		for (ServerLevel level : src.getServer().getAllLevels()) {
			for (CompanionEntity c : level.getEntities(ModEntities.COMPANION, c -> true)) {
				if (admin || player != null && c.isOwnedBy(player)) {
					list.add(c);
				}
			}
		}
		return list;
	}

	private static @Nullable CompanionEntity find(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "nome");
		for (CompanionEntity c : visible(ctx.getSource())) {
			if (c.companionName().equalsIgnoreCase(name) || c.getStringUUID().equals(name)) {
				return c;
			}
		}
		ctx.getSource().sendFailure(Component.translatable("command.autonomouscompanions.not_found", name));
		return null;
	}

	private static Component line(String key, Object... args) {
		return Component.translatable(key, args);
	}

	// ------------------------------------------------------------------ subcomandos

	private static int list(CommandContext<CommandSourceStack> ctx) {
		List<CompanionEntity> list = visible(ctx.getSource());
		if (list.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("command.autonomouscompanions.none"), false);
		}
		for (CompanionEntity c : list) {
			BlockPos p = c.blockPosition();
			ctx.getSource().sendSuccess(() -> Component.literal("• ").append(c.getDisplayName()).append(" — ")
				.append(Component.translatable(c.companionState().translationKey())).append(String.format(" [%d %d %d] ", p.getX(), p.getY(), p.getZ()))
				.append(Component.translatable(c.role().translationKey())).withStyle(ChatFormatting.GRAY), false);
		}
		for (var r : CompanionWorldData.get(ctx.getSource().getServer()).deadRecords()) {
			ServerPlayer player = ctx.getSource().getPlayer();
			if (isAdmin(ctx.getSource()) || player != null && r.owner().isPresent() && r.owner().get().equals(player.getUUID())) {
				ctx.getSource().sendSuccess(() -> Component.translatable("command.autonomouscompanions.dead_entry", r.name(), r.deaths())
					.withStyle(ChatFormatting.DARK_RED), false);
			}
		}
		return list.size();
	}

	private static int state(CommandContext<CommandSourceStack> ctx) {
		CompanionEntity c = find(ctx);
		if (c == null) {
			return 0;
		}
		var src = ctx.getSource();
		src.sendSuccess(() -> Component.literal("== ").append(c.getDisplayName()).append(" ==").withStyle(ChatFormatting.GOLD), false);
		src.sendSuccess(() -> line("command.autonomouscompanions.state_line", Component.translatable(c.companionState().translationKey()), c.stateDetail()), false);
		src.sendSuccess(() -> line("command.autonomouscompanions.health_line", String.format("%.1f/%.1f", c.getHealth(), c.getMaxHealth()),
			c.needs().hunger(), (int) c.needs().fatigue()), false);
		src.sendSuccess(() -> line("command.autonomouscompanions.role_line", Component.translatable(c.role().translationKey()),
			c.brain().active() == null ? "-" : c.brain().active().name()), false);
		BlockPos p = c.blockPosition();
		src.sendSuccess(() -> line("command.autonomouscompanions.pos_line", p.getX(), p.getY(), p.getZ(), c.level().dimension().identifier().toString(),
			c.ownerName()), false);
		return 1;
	}

	private static int mission(CommandContext<CommandSourceStack> ctx) {
		CompanionEntity c = find(ctx);
		if (c == null) {
			return 0;
		}
		var src = ctx.getSource();
		if (c.orders().all().isEmpty()) {
			src.sendSuccess(() -> Component.translatable("command.autonomouscompanions.no_orders"), false);
		}
		for (Order o : c.orders().all()) {
			Order.Progress pr = o.progress();
			src.sendSuccess(() -> Component.literal("• ").append(Component.translatable(o.type().translationKey())).append(" ")
				.append(o.targetName()).append(String.format(" %d/%d ", pr.delivered, o.requested()))
				.append(Component.translatable(o.status().translationKey()))
				.append(String.format(" | minerados %d, fabricados %d, colocados %d, %.0f blocos percorridos", pr.blocksMined, pr.itemsCrafted,
					pr.blocksPlaced, pr.distance)), false);
			if (o.reason() != null) {
				src.sendSuccess(() -> Component.literal("   ").append(o.reason()).withStyle(ChatFormatting.RED), false);
			}
		}
		for (Component step : c.brain().orders().describeStack()) {
			src.sendSuccess(() -> Component.literal("  → ").append(step).withStyle(ChatFormatting.AQUA), false);
		}
		return 1;
	}

	/**
	 * Diagnóstico completo: o que ela está fazendo e por quê, a missão ativa (identificador, estado, pedido, obtido,
	 * entregue, com ela, reservado), materiais em uso, a pilha de etapas, tempo sem progresso, replanejamentos, a última
	 * interrupção e o custo da IA (média e pior tick, cálculos de caminho, tempo em cada estado).
	 */
	private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> SKILLS = (ctx, builder) ->
		net.minecraft.commands.SharedSuggestionProvider.suggest(dev.autonomouscompanions.mind.skills.SkillLibrary.all().stream()
			.filter(dev.autonomouscompanions.mind.skills.Skill::orderable).map(dev.autonomouscompanions.mind.skills.Skill::id), builder);

	private static int skillOrder(CommandContext<CommandSourceStack> ctx, int count) {
		CompanionEntity c = find(ctx);
		if (c == null) {
			return 0;
		}
		Component refusal = OrderService.createSkillOrder(ctx.getSource().getLevel(), c, StringArgumentType.getString(ctx, "habilidade"), count);
		if (refusal != null) {
			ctx.getSource().sendFailure(refusal);
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal(c.companionName() + " vai executar a habilidade."), false);
		return 1;
	}

	/** O que ela percebe agora (estado real do jogo em volta dela). */
	private static int perception(CommandContext<CommandSourceStack> ctx) {
		CompanionEntity c = find(ctx);
		if (c == null) {
			return 0;
		}
		var src = ctx.getSource();
		src.sendSuccess(() -> Component.literal("== Percepção de " + c.companionName()), false);
		for (String line : dev.autonomouscompanions.mind.perception.Perception.of(c).describe()) {
			src.sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}

	/** O que ela lembra: base, recursos vistos pela equipe, hipóteses, experiências e o que aprendeu com elas. */
	private static int memory(CommandContext<CommandSourceStack> ctx) {
		CompanionEntity c = find(ctx);
		if (c == null) {
			return 0;
		}
		var src = ctx.getSource();
		for (String line : dev.autonomouscompanions.mind.memory.MemoryReport.lines(c)) {
			src.sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}

	/** Marca a posição da jogadora como a base (fato) para todas as companheiras dela neste mundo. */
	private static int setBase(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		var player = ctx.getSource().getPlayerOrException();
		var level = ctx.getSource().getLevel();
		var memory = dev.autonomouscompanions.mind.memory.WorldMemory.get(level.getServer());
		int n = 0;
		for (var r : dev.autonomouscompanions.persist.CompanionWorldData.get(level.getServer()).records()) {
			if (r.owner().isPresent() && r.owner().get().equals(player.getUUID())) {
				memory.setPlace(r.id(), "base", level.dimension(), player.blockPosition(), dev.autonomouscompanions.mind.memory.WorldMemory.Certainty.FACT,
					level.getGameTime());
				n++;
			}
		}
		int count = n;
		ctx.getSource().sendSuccess(() -> Component.literal("Base marcada em " + player.blockPosition().toShortString() + " para " + count + " companheira(s)."),
			false);
		return n;
	}

	private static int diagnose(CommandContext<CommandSourceStack> ctx) {
		CompanionEntity c = find(ctx);
		if (c == null) {
			return 0;
		}
		var src = ctx.getSource();
		var brain = c.brain();
		var ob = brain.orders();
		java.util.function.Consumer<String> line = t -> src.sendSuccess(() -> Component.literal(t), false);
		var active = brain.active();
		line.accept("== " + c.companionName() + ": estado " + c.companionState() + " (" + c.stateDetail().getString() + ")");
		line.accept("Comportamento: " + (active == null ? "nenhum" : active.name()) + " | vida " + (int) c.getHealth() + "/" + (int) c.getMaxHealth()
			+ " | fome " + c.needs().hunger() + " | cansaço " + (int) c.needs().fatigue() + " | mão: " + c.mainHandItem().getHoverName().getString());
		var owner = c.ownerInLevel();
		line.accept("Dona: " + (owner == null ? "fora do mundo ou em outra dimensão" : owner.getGameProfile().name() + " a " + (int) c.distanceTo(owner) + " blocos"));
		Order o = c.orders().active();
		if (o == null) {
			line.accept("Missão ativa: nenhuma (" + c.orders().all().size() + " na fila/histórico)");
		} else {
			Order.Progress p = o.progress();
			line.accept("Missão " + o.id().toString().substring(0, 8) + ": " + o.type() + " " + o.targetName().getString() + " | estado " + o.status()
				+ " | pedido " + o.requested() + ", obtido " + p.obtained + ", entregue " + p.delivered + ", com ela " + o.countIn(c));
			line.accept("Progresso: minerados " + p.blocksMined + ", fabricados " + p.itemsCrafted + ", colocados " + p.blocksPlaced + ", " + (int) p.distance
				+ " blocos andados | sem progresso há " + ob.ticksWithoutProgress() / 20 + " s | replanejamentos " + ob.stallRestarts());
		}
		var working = c.orders().workingItems();
		if (!working.isEmpty()) {
			StringBuilder b = new StringBuilder("Materiais em uso pela missão: ");
			working.forEach((item, n) -> b.append(new ItemStack(item).getHoverName().getString()).append(" (até ").append(n).append(") "));
			line.accept(b.toString());
		}
		for (Component step : ob.describeStack()) {
			src.sendSuccess(() -> Component.literal("  → ").append(step).withStyle(ChatFormatting.AQUA), false);
		}
		if (o != null && o.reason() != null) {
			src.sendSuccess(() -> Component.literal("Motivo registrado: ").append(o.reason()).withStyle(ChatFormatting.RED), false);
		}
		var m = brain.metrics();
		line.accept(String.format("IA: %d ticks, média %.1f µs, pior %.1f µs | caminhos calculados %d | trocas de comportamento %d | ordem interrompida %d vez(es)",
			m.ticks(), m.averageMicros(), m.maxMicros(), m.pathComputations(), m.behaviorSwitches(), m.orderInterruptions()));
		line.accept("Última interrupção da ordem: " + m.lastInterruption());
		StringBuilder st = new StringBuilder("Tempo por estado (s): ");
		m.stateTicks().entrySet().stream().sorted((a, b2) -> Long.compare(b2.getValue(), a.getValue())).limit(6)
			.forEach(e -> st.append(e.getKey()).append(' ').append(e.getValue() / 20).append("  "));
		line.accept(st.toString());
		return 1;
	}

	private static int inventory(CommandContext<CommandSourceStack> ctx) {
		CompanionEntity c = find(ctx);
		if (c == null) {
			return 0;
		}
		var src = ctx.getSource();
		int n = 0;
		for (int i = 0; i < c.inventory().getContainerSize(); i++) {
			ItemStack s = c.inventory().getItem(i);
			if (!s.isEmpty()) {
				int reserved = c.inventory().reserved(s.getItem());
				final int slot = i;
				src.sendSuccess(() -> Component.literal(String.format("[%02d] %dx ", slot, s.getCount())).append(s.getHoverName())
					.append(reserved > 0 ? Component.translatable("command.autonomouscompanions.reserved", reserved) : Component.empty()), false);
				n++;
			}
		}
		src.sendSuccess(() -> Component.literal("Mão: ").append(c.mainHandItem().getHoverName()), false);
		return n;
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) {
		CompanionEntity c = find(ctx);
		if (c == null) {
			return 0;
		}
		Order o = c.orders().active();
		if (o == null) {
			ctx.getSource().sendFailure(Component.translatable("command.autonomouscompanions.no_orders"));
			return 0;
		}
		c.brain().orders().cancel((ServerLevel) c.level(), o);
		ctx.getSource().sendSuccess(() -> Component.translatable("command.autonomouscompanions.cancelled", c.getDisplayName()), true);
		return 1;
	}

	private static int recover(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "nome");
		for (CompanionEntity c : visible(ctx.getSource())) {
			if (c.companionName().equalsIgnoreCase(name)) {
				ServerLevel level = (ServerLevel) player.level();
				BlockPos spot = SafeSpot.findNear(level, player.blockPosition(), 1, 6, 4, c.getType());
				if (spot == null) {
					ctx.getSource().sendFailure(Component.translatable("command.autonomouscompanions.no_safe_spot"));
					return 0;
				}
				c.brain().navigator().clear();
				c.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, java.util.Set.of(), player.getYRot(), 0, false);
				ctx.getSource().sendSuccess(() -> Component.translatable("command.autonomouscompanions.recovered", name), true);
				return 1;
			}
		}
		// Não carregada: informa a última posição conhecida (sem forçar carregamento de chunks) ou ressuscita se morta.
		for (var r : CompanionWorldData.get(ctx.getSource().getServer()).records()) {
			if (r.name().equalsIgnoreCase(name) && (isAdmin(ctx.getSource()) || r.owner().map(player.getUUID()::equals).orElse(false))) {
				if (r.dead()) {
					return revive(ctx);
				}
				ctx.getSource().sendSuccess(() -> Component.translatable("command.autonomouscompanions.last_known", name, r.lastPos().getX(), r.lastPos().getY(),
					r.lastPos().getZ(), r.dimension().identifier().toString()), false);
				return 1;
			}
		}
		ctx.getSource().sendFailure(Component.translatable("command.autonomouscompanions.not_found", name));
		return 0;
	}

	private static int revive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "nome");
		boolean admin = isAdmin(ctx.getSource());
		String mode = ConfigManager.get().comportamentoMorte;
		for (var r : CompanionWorldData.get(ctx.getSource().getServer()).deadRecords()) {
			if (!r.name().equalsIgnoreCase(name)) {
				continue;
			}
			boolean owner = r.owner().map(player.getUUID()::equals).orElse(false);
			if (!admin && (!owner || mode.equals("PERMANENTE"))) {
				ctx.getSource().sendFailure(Component.translatable("command.autonomouscompanions.revive_denied"));
				return 0;
			}
			CompanionEntity c = DeathRecovery.revive(ctx.getSource().getServer(), r.id(), player);
			if (c == null) {
				ctx.getSource().sendFailure(Component.translatable("command.autonomouscompanions.revive_failed"));
				return 0;
			}
			return 1;
		}
		ctx.getSource().sendFailure(Component.translatable("command.autonomouscompanions.not_dead", name));
		return 0;
	}

	private static int debug(CommandContext<CommandSourceStack> ctx) {
		CompanionEntity c = find(ctx);
		if (c == null) {
			return 0;
		}
		boolean on = BoolArgumentType.getBool(ctx, "ativo");
		c.settings().debug = on;
		ctx.getSource().sendSuccess(() -> Component.translatable("command.autonomouscompanions.debug", c.getDisplayName(), Component.translatable(on ? "gui.autonomouscompanions.on" : "gui.autonomouscompanions.off")), false);
		return 1;
	}

	private static int order(CommandContext<CommandSourceStack> ctx) {
		CompanionEntity c = find(ctx);
		if (c == null) {
			return 0;
		}
		String block = StringArgumentType.getString(ctx, "bloco");
		if (!block.contains(":")) {
			block = "minecraft:" + block;
		}
		int count = IntegerArgumentType.getInteger(ctx, "quantidade");
		GatherMode mode = OrderService.block(block) != null ? GatherMode.DROPS : GatherMode.ITEM;
		Component refusal = mode == GatherMode.DROPS
			? OrderService.createGather((ServerLevel) c.level(), c, block, mode, count, SearchProfile.AUTO, 0, false)
			: OrderService.createItemOrder((ServerLevel) c.level(), c, block, count);
		if (refusal != null) {
			ctx.getSource().sendFailure(refusal);
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("message.autonomouscompanions.order_accepted", c.getDisplayName()), false);
		return 1;
	}
}
