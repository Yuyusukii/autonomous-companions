package dev.autonomouscompanions.network;

import dev.autonomouscompanions.build.BuildPlanner;
import dev.autonomouscompanions.build.BuildProject;
import dev.autonomouscompanions.build.HouseSize;
import dev.autonomouscompanions.build.HouseStyle;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionRole;
import dev.autonomouscompanions.inventory.DeliveryService;
import dev.autonomouscompanions.inventory.Gear;
import dev.autonomouscompanions.mining.DropIndex;
import dev.autonomouscompanions.persist.CompanionWorldData;
import dev.autonomouscompanions.survival.FoodHelper;
import dev.autonomouscompanions.task.Order;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.OrderType;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.task.OrderService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Registro de pacotes e tratamento autoritativo no servidor (propriedade validada em toda ordem recebida). */
public final class ServerNetworking {
	private static final Map<UUID, Set<Integer>> SUBSCRIPTIONS = new HashMap<>();
	private static final Map<UUID, Marking> MARKING = new HashMap<>();

	private record Marking(int entityId, int style, int size, long seed, boolean prepare, long expires, int kind, int area, int team) {
	}

	private ServerNetworking() {
	}

	public static void registerPayloadTypes() {
		var c2s = PayloadTypeRegistry.serverboundPlay();
		c2s.register(Payloads.Rename.TYPE, Payloads.Rename.CODEC);
		c2s.register(Payloads.Subscribe.TYPE, Payloads.Subscribe.CODEC);
		c2s.register(Payloads.AnalyzeGather.TYPE, Payloads.AnalyzeGather.CODEC);
		c2s.register(Payloads.StartGather.TYPE, Payloads.StartGather.CODEC);
		c2s.register(Payloads.StartItemOrder.TYPE, Payloads.StartItemOrder.CODEC);
		c2s.register(Payloads.OrderControl.TYPE, Payloads.OrderControl.CODEC);
		c2s.register(Payloads.SetRole.TYPE, Payloads.SetRole.CODEC);
		c2s.register(Payloads.UpdateSettings.TYPE, Payloads.UpdateSettings.CODEC);
		c2s.register(Payloads.Deliver.TYPE, Payloads.Deliver.CODEC);
		c2s.register(Payloads.GiveItems.TYPE, Payloads.GiveItems.CODEC);
		c2s.register(Payloads.SimpleCommand.TYPE, Payloads.SimpleCommand.CODEC);
		c2s.register(Payloads.BuildMark.TYPE, Payloads.BuildMark.CODEC);
		c2s.register(Payloads.BuildDecision.TYPE, Payloads.BuildDecision.CODEC);
		c2s.register(Payloads.BuildControl.TYPE, Payloads.BuildControl.CODEC);
		c2s.register(Payloads.WhistleCall.TYPE, Payloads.WhistleCall.CODEC);
		var s2c = PayloadTypeRegistry.clientboundPlay();
		s2c.register(Payloads.OpenNaming.TYPE, Payloads.OpenNaming.CODEC);
		s2c.registerLarge(Payloads.Snapshot.TYPE, Payloads.Snapshot.CODEC, 1 << 20);
		s2c.register(Payloads.Analysis.TYPE, Payloads.Analysis.CODEC);
		s2c.registerLarge(Payloads.BuildPreview.TYPE, Payloads.BuildPreview.CODEC, 1 << 18);
		s2c.register(Payloads.MarkingMode.TYPE, Payloads.MarkingMode.CODEC);
		s2c.register(Payloads.WhistleMenu.TYPE, Payloads.WhistleMenu.CODEC);
	}

	public static void registerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Rename.TYPE, (p, ctx) -> handleRename(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Subscribe.TYPE, (p, ctx) -> handleSubscribe(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.AnalyzeGather.TYPE, (p, ctx) -> handleAnalyze(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.StartGather.TYPE, (p, ctx) -> handleStartGather(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.StartItemOrder.TYPE, (p, ctx) -> handleItemOrder(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.OrderControl.TYPE, (p, ctx) -> handleOrderControl(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.SetRole.TYPE, (p, ctx) -> handleRole(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.UpdateSettings.TYPE, (p, ctx) -> handleSettings(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Deliver.TYPE, (p, ctx) -> handleDeliver(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.GiveItems.TYPE, (p, ctx) -> handleGiveItems(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.SimpleCommand.TYPE, (p, ctx) -> handleCommand(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.BuildMark.TYPE, (p, ctx) -> handleBuildMark(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.BuildDecision.TYPE, (p, ctx) -> handleBuildDecision(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.BuildControl.TYPE, (p, ctx) -> handleBuildControl(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.WhistleCall.TYPE,
			(p, ctx) -> dev.autonomouscompanions.item.WhistleItem.handleCall(ctx.player(), p.all(), p.target()));
	}

	// ------------------------------------------------------------------ envio

	public static void openNaming(ServerPlayer player, CompanionEntity c) {
		ServerPlayNetworking.send(player, new Payloads.OpenNaming(c.getId(), c.variant().ordinal()));
	}

	public static void openPanel(ServerPlayer player, CompanionEntity c) {
		SUBSCRIPTIONS.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(c.getId());
		ServerPlayNetworking.send(player, new Payloads.Snapshot(snapshot(c, true), true));
	}

	/** Atualiza as interfaces abertas (a cada 10 ticks, só para quem está inscrito). */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) {
			return;
		}
		for (var it = SUBSCRIPTIONS.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null) {
				it.remove();
				continue;
			}
			for (Integer id : e.getValue()) {
				Entity entity = player.level().getEntity(id);
				if (entity instanceof CompanionEntity c && c.isOwnedBy(player)) {
					ServerPlayNetworking.send(player, new Payloads.Snapshot(snapshot(c, false), false));
				}
			}
		}
		// Contorno da área marcada/prévia expira.
		long now = server.getTickCount();
		MARKING.entrySet().removeIf(e -> e.getValue().expires < now);
	}

	public static CompanionSnapshot snapshot(CompanionEntity c, boolean full) {
		List<CompanionSnapshot.OrderView> orders = new ArrayList<>();
		for (Order o : c.orders().all()) {
			Order.Progress p = o.progress();
			// "Coletado" = entregue + o que já está no inventário para este pedido (limitado ao pedido).
			int collected = o.reservesItems() ? Math.min(o.requested(), o.countIn(c) + p.delivered) : p.obtained;
			orders.add(new CompanionSnapshot.OrderView(o.id(), o.type().ordinal(), o.status().ordinal(), o.targetId(), o.sourceBlock(), o.mode().ordinal(),
				o.requested(), p.delivered, collected, p.distance, p.blocksMined, p.itemsCrafted, p.blocksPlaced, Optional.ofNullable(o.reason()),
				o.urgent()));
		}
		List<ItemStack> eq = new ArrayList<>();
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
			EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			eq.add(c.getItemBySlot(slot).copy());
		}
		List<Boolean> reserved = new ArrayList<>();
		for (int i = 0; i < c.inventory().getContainerSize(); i++) {
			ItemStack s = c.inventory().getItem(i);
			reserved.add(!s.isEmpty() && c.inventory().reserved(s.getItem()) > 0);
		}
		var st = c.stats();
		Optional<CompanionSnapshot.BuildView> build = Optional.empty();
		Order buildOrder = c.orders().firstOfType(OrderType.BUILD);
		if (buildOrder != null && buildOrder.projectId() != null && c.level() instanceof ServerLevel sl) {
			BuildProject p = CompanionWorldData.get(sl.getServer()).project(buildOrder.projectId());
			if (p != null) {
				build = Optional.of(new CompanionSnapshot.BuildView(p.id(), p.style().ordinal(), p.size().ordinal(), p.status().ordinal(),
					p.progressPercent(), p.placed, p.total, p.origin(), p.isTerrain() ? 1 : 0, p.area(), p.workers().size(), Math.max(0, p.workerIndex(c.getUUID()))));
			}
		}
		List<String> huntables = new ArrayList<>();
		// Sempre enviada (lista curta): as atualizações periódicas não podem apagar as opções de carne da tela.
		if (DropIndex.isReady()) {
			for (Item item : DropIndex.huntableItems()) {
				if (FoodHelper.isEdible(new ItemStack(item))) {
					// Formato "cru>cozido" (cozido vazio se não houver receita de fornalha).
					var cooked = dev.autonomouscompanions.crafting.RecipeIndex.smeltingFrom(item);
					String cookedId = cooked.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(cooked.getFirst().output()).toString();
					huntables.add(BuiltInRegistries.ITEM.getKey(item) + ">" + cookedId);
				}
			}
			huntables.sort(String::compareTo);
		}
		return new CompanionSnapshot(c.getId(), c.getUUID(), c.companionName(), c.variant().ordinal(), c.ownerName(), c.companionState().ordinal(),
			c.stateDetail(), c.getHealth(), c.getMaxHealth(), c.needs().hunger(), c.needs().saturation(), c.needs().fatigue(), c.role().ordinal(),
			c.settings(), orders, c.brain().orders().describeStack(), c.inventory().snapshot(), eq,
			new CompanionSnapshot.Stats(st.distanceTraveled, st.blocksMined, st.itemsCrafted, st.blocksPlaced, st.kills, st.deaths, st.meals,
				st.itemsDelivered), c.blockPosition(), c.level().dimension().identifier().toString(), build, huntables, reserved);
	}

	private static void refresh(ServerPlayer player, CompanionEntity c) {
		ServerPlayNetworking.send(player, new Payloads.Snapshot(snapshot(c, false), false));
	}

	// ------------------------------------------------------------------ validação

	/** Companheira do jogador (dona) carregada na mesma dimensão, ou nulo. */
	private static @Nullable CompanionEntity owned(ServerPlayer player, int entityId) {
		Entity e = player.level().getEntity(entityId);
		// A ajudante não recebe ordens nem tem painel: os pacotes da tela nunca valem para ela.
		if (e instanceof CompanionEntity c && c.isAlive() && c.isOwnedBy(player) && !c.isHelper()) {
			return c;
		}
		return null;
	}

	// ------------------------------------------------------------------ tratadores

	public static void handleRename(ServerPlayer player, Payloads.Rename p) {
		Entity e = player.level().getEntity(p.entityId());
		if (!(e instanceof CompanionEntity c) || !c.isAlive() || c.isHelper()) {
			return;
		}
		if (c.hasOwner() && !c.isOwnedBy(player)) {
			player.sendSystemMessage(Component.translatable("message.autonomouscompanions.not_owner", c.getDisplayName()), true);
			return;
		}
		if (c.distanceTo(player) > 16) {
			return;
		}
		String name = sanitizeName(p.name());
		if (name.isEmpty()) {
			player.sendSystemMessage(Component.translatable("message.autonomouscompanions.invalid_name"), true);
			return;
		}
		c.setCompanionName(name);
		if (!c.hasOwner()) {
			c.setOwner(player);
		}
		c.brain().requestDecision();
		CompanionWorldData.get(player.level().getServer()).updateCompanion(c);
		// Abre o painel imediatamente após confirmar o nome.
		openPanel(player, c);
	}

	public static String sanitizeName(String raw) {
		StringBuilder sb = new StringBuilder();
		for (char ch : raw.trim().toCharArray()) {
			if (Character.isLetterOrDigit(ch) || ch == ' ' || ch == '-' || ch == '_' || ch == '\'' || ch == '.') {
				sb.append(ch);
			}
		}
		String s = sb.toString().replaceAll("\\s+", " ").trim();
		return s.length() > 24 ? s.substring(0, 24).trim() : s;
	}

	public static void handleSubscribe(ServerPlayer player, Payloads.Subscribe p) {
		if (!p.subscribe()) {
			Set<Integer> set = SUBSCRIPTIONS.get(player.getUUID());
			if (set != null) {
				set.remove(p.entityId());
			}
			return;
		}
		CompanionEntity c = owned(player, p.entityId());
		if (c != null) {
			openPanel(player, c);
		}
	}

	public static void handleAnalyze(ServerPlayer player, Payloads.AnalyzeGather p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null) {
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		var block = OrderService.block(p.blockId());
		if (block == null) {
			return;
		}
		GatherMode mode = GatherMode.values()[Math.floorMod(p.mode(), GatherMode.values().length)];
		var a = OrderService.analyzeBlock(level, c, block, mode, Math.max(1, Math.min(p.count(), OrderService.MAX_COUNT)));
		ServerPlayNetworking.send(player, new Payloads.Analysis(c.getId(), p.blockId(), p.mode(), a.feasible(),
			BuiltInRegistries.ITEM.getKey(a.target()).toString(), a.profile().ordinal(), a.range(), a.lines()));
	}

	public static void handleStartGather(ServerPlayer player, Payloads.StartGather p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null) {
			return;
		}
		GatherMode mode = GatherMode.values()[Math.floorMod(p.mode(), GatherMode.values().length)];
		SearchProfile profile = SearchProfile.byIndex(p.profile());
		int max = Math.max(0, Math.min(p.maxDistance(), ConfigManager.get().alcanceBuscaMaximoAbsoluto));
		Component refusal = OrderService.createGather((ServerLevel) player.level(), c, p.blockId(), mode, p.count(), profile, max, p.urgent());
		player.sendSystemMessage(refusal == null ? Component.translatable("message.autonomouscompanions.order_accepted", c.getDisplayName())
			: Component.translatable("message.autonomouscompanions.order_refused", c.getDisplayName(), refusal), true);
		refresh(player, c);
	}

	public static void handleItemOrder(ServerPlayer player, Payloads.StartItemOrder p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null) {
			return;
		}
		String itemId = p.itemId();
		Component refusal;
		if (itemId.equals(OrderService.NEAREST_MEAT) || itemId.equals(OrderService.NEAREST_MEAT_COOKED)) {
			// "Mais próxima": qualquer carne, caçando sempre o animal mais perto (antes ela escolhia uma espécie só, a do
			// animal mais próximo naquele instante, e ia atrás dela pelo mapa mesmo com outros animais ao lado).
			boolean cooked = itemId.equals(OrderService.NEAREST_MEAT_COOKED);
			refusal = OrderService.createMeatOrder((ServerLevel) player.level(), c, cooked, p.count());
			if (refusal == null) {
				player.sendSystemMessage(Component.translatable("message.autonomouscompanions.nearest_meat", c.getDisplayName(),
					Component.translatable(cooked ? "order.autonomouscompanions.group.any_cooked_meat" : "order.autonomouscompanions.group.any_meat")));
			}
		} else {
			refusal = OrderService.createItemOrder((ServerLevel) player.level(), c, itemId, p.count());
		}
		player.sendSystemMessage(refusal == null ? Component.translatable("message.autonomouscompanions.order_accepted", c.getDisplayName())
			: Component.translatable("message.autonomouscompanions.order_refused", c.getDisplayName(), refusal), true);
		refresh(player, c);
	}

	public static void handleOrderControl(ServerPlayer player, Payloads.OrderControl p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null) {
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		if (p.action() == Payloads.OrderControl.CLEAR_FINISHED) {
			c.orders().clearFinished();
			refresh(player, c);
			return;
		}
		Order o = c.orders().byId(p.orderId());
		if (o == null) {
			return;
		}
		switch (p.action()) {
			case Payloads.OrderControl.CANCEL -> {
				c.brain().orders().cancel(level, o);
				cancelBuildProject(level, o);
			}
			case Payloads.OrderControl.SUSPEND -> c.brain().orders().suspend(level, o);
			case Payloads.OrderControl.RESUME, Payloads.OrderControl.RETRY -> {
				if (o.status() == OrderStatus.SUSPENDED || o.status() == OrderStatus.FAILED) {
					o.setReason(null);
					c.orders().setStatus(o, OrderStatus.PENDING);
					c.brain().requestDecision();
				}
			}
			case Payloads.OrderControl.URGENT -> {
				o.setUrgent(!o.urgent());
				c.orders().touch();
				c.brain().requestDecision();
			}
			default -> {
			}
		}
		refresh(player, c);
	}

	private static void cancelBuildProject(ServerLevel level, Order o) {
		if (o.type() == OrderType.BUILD && o.projectId() != null) {
			CompanionWorldData data = CompanionWorldData.get(level.getServer());
			BuildProject project = data.project(o.projectId());
			if (project != null) {
				project.setStatus(BuildProject.Status.CANCELLED);
				data.setDirty();
			}
		}
	}

	public static void handleRole(ServerPlayer player, Payloads.SetRole p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null) {
			return;
		}
		c.setRole(CompanionRole.byIndex(p.role()));
		c.brain().requestDecision();
		refresh(player, c);
	}

	public static void handleSettings(ServerPlayer player, Payloads.UpdateSettings p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null) {
			return;
		}
		c.settings().applyFromClient(p.settings());
		c.brain().requestDecision();
		refresh(player, c);
	}

	public static void handleDeliver(ServerPlayer player, Payloads.Deliver p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null || c.distanceTo(player) > 8) {
			if (c != null) {
				player.sendSystemMessage(Component.translatable("message.autonomouscompanions.too_far"), true);
			}
			return;
		}
		int given = 0;
		switch (p.mode()) {
			case Payloads.Deliver.MISSION -> {
				for (Order o : c.orders().all()) {
					if (o.reservesItems() && o.remainingToDeliver() > 0) {
						DeliveryService.Result r = DeliveryService.deliverOrder(c, o, player, false);
						given += r.delivered();
						if (o.remainingToDeliver() <= 0 && o.status() != OrderStatus.COMPLETED) {
							c.orders().setStatus(o, OrderStatus.COMPLETED);
						}
					}
				}
			}
			case Payloads.Deliver.SLOT -> {
				if (p.slot() >= 0 && p.slot() < c.inventory().getContainerSize()) {
					ItemStack s = c.inventory().getItem(p.slot());
					if (!s.isEmpty()) {
						// Itens de missão só via entrega da missão (mantém a contabilidade correta).
						int free = c.inventory().countFree(s.getItem());
						given += DeliveryService.transfer(c, s.getItem(), Math.min(free, s.getCount()), player);
					}
				}
			}
			case Payloads.Deliver.MATERIALS -> {
				Set<Item> seen = new HashSet<>();
				for (int i = 0; i < c.inventory().getContainerSize(); i++) {
					ItemStack s = c.inventory().getItem(i);
					if (s.isEmpty() || Gear.isTool(s) || Gear.armorSlot(s) != null || FoodHelper.isEdible(s) || s.isDamageableItem()
						|| !seen.add(s.getItem())) {
						continue;
					}
					given += DeliveryService.transfer(c, s.getItem(), c.inventory().countFree(s.getItem()), player);
				}
			}
			default -> {
			}
		}
		player.sendSystemMessage(Component.translatable("message.autonomouscompanions.delivered_now", c.getDisplayName(), given), true);
		refresh(player, c);
	}

	public static void handleGiveItems(ServerPlayer player, Payloads.GiveItems p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null || c.distanceTo(player) > 8) {
			if (c != null) {
				player.sendSystemMessage(Component.translatable("message.autonomouscompanions.too_far"), true);
			}
			return;
		}
		giveFromPlayer(player, c, p.slot(), p.count());
		refresh(player, c);
	}

	/**
	 * Move até {@code count} itens de um espaço do inventário da jogadora para a companheira. Primeiro insere nela e só
	 * então retira da jogadora exatamente o que entrou (nunca duplica nem perde). Retorna a quantidade entregue.
	 */
	public static int giveFromPlayer(ServerPlayer player, CompanionEntity c, int slot, int count) {
		var inv = player.getInventory();
		if (slot < 0 || slot >= inv.getNonEquipmentItems().size() && slot != net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND || count <= 0) {
			return 0;
		}
		ItemStack stack = inv.getItem(slot);
		if (stack.isEmpty()) {
			return 0;
		}
		int k = Math.min(Math.min(count, stack.getCount()), c.inventory().spaceFor(stack));
		if (k <= 0) {
			player.sendSystemMessage(Component.translatable("message.autonomouscompanions.companion_full", c.getDisplayName()), true);
			return 0;
		}
		ItemStack rest = c.inventory().insert(stack.copyWithCount(k));
		int moved = k - rest.getCount();
		if (moved <= 0) {
			return 0;
		}
		Component name = stack.getHoverName();
		stack.shrink(moved);
		if (stack.isEmpty()) {
			inv.setItem(slot, ItemStack.EMPTY);
		}
		inv.setChanged();
		player.containerMenu.broadcastChanges();
		player.sendSystemMessage(Component.translatable("message.autonomouscompanions.received", c.getDisplayName(), moved, name), true);
		c.debug("Recebeu " + moved + "x " + name.getString() + " da dona.");
		return moved;
	}

	public static void handleCommand(ServerPlayer player, Payloads.SimpleCommand p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null) {
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		switch (p.command()) {
			case Payloads.SimpleCommand.FOLLOW -> {
				c.settings().stay = false;
				c.settings().stayPos = null;
			}
			case Payloads.SimpleCommand.STAY -> {
				c.settings().stay = true;
				c.settings().stayPos = c.blockPosition();
			}
			case Payloads.SimpleCommand.COME_BACK -> {
				c.settings().stay = false;
				if (c.orders().canAdd()) {
					c.orders().add(Order.returnToOwner(level.getGameTime()));
				}
			}
			case Payloads.SimpleCommand.EQUIP -> {
				if (c.orders().canAdd()) {
					c.orders().add(Order.equip(level.getGameTime()));
				}
			}
			case Payloads.SimpleCommand.EAT -> c.needs().setHunger(Math.min(c.needs().hunger(), dev.autonomouscompanions.survival.CompanionNeeds.HUNGRY));
			case Payloads.SimpleCommand.REST -> c.brain().requestRest(level);
			default -> {
			}
		}
		c.brain().requestDecision();
		refresh(player, c);
	}

	// ------------------------------------------------------------------ construção

	public static void handleBuildMark(ServerPlayer player, Payloads.BuildMark p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null) {
			return;
		}
		if (!ConfigManager.get().construcaoHabilitada) {
			player.sendSystemMessage(Component.translatable("build.autonomouscompanions.disabled"), true);
			return;
		}
		boolean prepare = p.prepareTerrain() && ConfigManager.get().permitirPrepararTerreno;
		MARKING.put(player.getUUID(), new Marking(c.getId(), p.style(), p.size(), p.seed(), prepare, player.level().getServer().getTickCount() + 20 * 120,
			p.kind(), dev.autonomouscompanions.build.TerrainPlanner.clampSize(p.area()), Math.max(1, Math.min(3, p.team()))));
		ServerPlayNetworking.send(player, new Payloads.MarkingMode(true, c.getId()));
		player.sendSystemMessage(Component.translatable("build.autonomouscompanions.marking_hint"), true);
	}

	/** Chamado pelo evento de uso de bloco: se o jogador estiver marcando, gera a prévia e cancela o uso normal. */
	public static boolean onUseBlock(ServerPlayer player, BlockPos clicked) {
		Marking m = MARKING.remove(player.getUUID());
		if (m == null) {
			return false;
		}
		ServerPlayNetworking.send(player, new Payloads.MarkingMode(false, m.entityId()));
		if (m.kind() == Payloads.BuildMark.TERRAIN) {
			sendTerrainPreview(player, m.entityId(), clicked, m.area(), m.team());
			return true;
		}
		Direction facing = player.getDirection().getOpposite();
		sendPreview(player, m.entityId(), clicked, facing, m.style(), m.size(), m.seed(), m.prepare(), m.team());
		return true;
	}

	public static boolean isMarking(ServerPlayer player) {
		return MARKING.containsKey(player.getUUID());
	}

	private static void sendPreview(ServerPlayer player, int entityId, BlockPos clicked, Direction facing, int style, int size, long seed, boolean prepare,
		int team) {
		CompanionEntity c = owned(player, entityId);
		if (c == null) {
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		HouseStyle hs = HouseStyle.byIndex(style);
		HouseSize sz = HouseSize.byIndex(size);
		BuildPlanner.Preview pv = BuildPlanner.preview(level, hs, sz, seed, clicked, facing, prepare, null);
		List<Payloads.MaterialLine> mats = new ArrayList<>();
		for (var e : pv.materials().entrySet()) {
			mats.add(new Payloads.MaterialLine(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), e.getValue(), c.inventory().count(e.getKey())));
		}
		ServerPlayNetworking.send(player, new Payloads.BuildPreview(entityId, pv.valid(), clicked, pv.origin(), facing.get2DDataValue(), style, size, seed,
			prepare, pv.wood(), pv.width(), pv.depth(), pv.height(), pv.blockCount(), pv.problems(), pv.warnings(), mats, Payloads.BuildMark.HOUSE, team, 0, 0));
		showOutline(level, player, hs, sz, seed, pv.origin(), facing, pv.wood());
	}

	/** Prévia da limpeza de terreno: área N×N centrada no bloco clicado, nivelada na altura dele. */
	private static void sendTerrainPreview(ServerPlayer player, int entityId, BlockPos clicked, int area, int team) {
		CompanionEntity c = owned(player, entityId);
		if (c == null) {
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		BuildProject probe = BuildProject.terrain(UUID.randomUUID(), player.getUUID(), List.of(c.getUUID()), clicked, area, level.dimension());
		var pv = dev.autonomouscompanions.build.TerrainPlanner.preview(level, probe);
		List<Payloads.MaterialLine> mats = new ArrayList<>();
		if (pv.fillCount() > 0) {
			mats.add(new Payloads.MaterialLine("minecraft:dirt", pv.fillCount(), c.inventory().count(net.minecraft.world.item.Items.DIRT)));
		}
		List<Component> problems = new ArrayList<>(pv.problems());
		if (dev.autonomouscompanions.coop.Cooperation.get(level.getServer()).overlappingProject(level, probe.bounds(), null) != null) {
			problems.add(Component.translatable("build.autonomouscompanions.problem.overlap"));
		}
		ServerPlayNetworking.send(player, new Payloads.BuildPreview(entityId, problems.isEmpty(), clicked, clicked, 0, 0, 0, 0L, true, "oak", area, area, 0,
			pv.clearCount(), problems, pv.warnings(), mats, Payloads.BuildMark.TERRAIN, team, pv.treeCount(), pv.fillCount()));
		AABB box = probe.bounds();
		double y = clicked.getY() + 1.1;
		for (double x = box.minX; x <= box.maxX; x += 0.5) {
			level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, x, y, box.minZ, 1, 0, 0, 0, 0);
			level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, x, y, box.maxZ, 1, 0, 0, 0, 0);
		}
		for (double z = box.minZ; z <= box.maxZ; z += 0.5) {
			level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, box.minX, y, z, 1, 0, 0, 0, 0);
			level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, box.maxX, y, z, 1, 0, 0, 0, 0);
		}
	}

	/**
	 * Equipe da obra: a companheira escolhida (líder) e, se pedido, as mais próximas da mesma dona que estão livres
	 * (com nome, vivas, sem outra obra). A ajudante nunca entra.
	 */
	public static List<CompanionEntity> pickTeam(ServerPlayer player, CompanionEntity leader, int size) {
		List<CompanionEntity> team = new ArrayList<>();
		team.add(leader);
		if (size <= 1) {
			return team;
		}
		ServerLevel level = (ServerLevel) leader.level();
		List<CompanionEntity> free = new ArrayList<>(level.getEntities(dev.autonomouscompanions.registry.ModEntities.COMPANION, e -> e != leader && e.isAlive()
			&& e.isOwnedBy(player) && e.isNamed() && e.orders().firstOfType(OrderType.BUILD) == null && e.orders().canAdd()));
		free.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(leader)));
		for (CompanionEntity e : free) {
			if (team.size() >= size) {
				break;
			}
			team.add(e);
		}
		return team;
	}

	private static Component teamNames(List<CompanionEntity> team) {
		StringBuilder sb = new StringBuilder();
		for (CompanionEntity e : team) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(e.getDisplayName().getString());
		}
		return Component.literal(sb.toString());
	}

	/** Confirma (ou recalcula) a limpeza de terreno e distribui a ordem para toda a equipe. */
	private static void handleTerrainDecision(ServerPlayer player, CompanionEntity c, Payloads.BuildDecision p) {
		int area = dev.autonomouscompanions.build.TerrainPlanner.clampSize(p.area());
		int size = Math.max(1, Math.min(3, p.team()));
		if (!p.confirm()) {
			sendTerrainPreview(player, c.getId(), p.clicked(), area, size);
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		if (c.orders().firstOfType(OrderType.BUILD) != null) {
			player.sendSystemMessage(Component.translatable("build.autonomouscompanions.already_building", c.getDisplayName()), true);
			return;
		}
		if (!c.orders().canAdd()) {
			player.sendSystemMessage(Component.translatable("order.autonomouscompanions.queue_full"), true);
			return;
		}
		List<CompanionEntity> team = pickTeam(player, c, size);
		List<UUID> ids = new ArrayList<>();
		for (CompanionEntity e : team) {
			ids.add(e.getUUID());
		}
		BuildProject project = BuildProject.terrain(UUID.randomUUID(), player.getUUID(), ids, p.clicked(), area, level.dimension());
		var pv = dev.autonomouscompanions.build.TerrainPlanner.preview(level, project);
		if (!pv.valid() || dev.autonomouscompanions.coop.Cooperation.get(level.getServer()).overlappingProject(level, project.bounds(), null) != null) {
			player.sendSystemMessage(Component.translatable("build.autonomouscompanions.invalid_site"), true);
			sendTerrainPreview(player, c.getId(), p.clicked(), area, size);
			return;
		}
		CompanionWorldData.get(level.getServer()).putProject(project);
		for (CompanionEntity e : team) {
			e.orders().add(Order.build(project.id(), level.getGameTime()));
			e.brain().requestDecision();
		}
		player.sendSystemMessage(Component.translatable("build.autonomouscompanions.terrain.approved", area, area, teamNames(team)));
		if (team.size() < size) {
			player.sendSystemMessage(Component.translatable("build.autonomouscompanions.team_short", team.size(), size), true);
		}
		refresh(player, c);
	}

	/** Contorno da área com partículas visíveis apenas para o dono. */
	private static void showOutline(ServerLevel level, ServerPlayer player, HouseStyle style, HouseSize size, long seed, BlockPos origin, Direction facing,
		String wood) {
		var bp = dev.autonomouscompanions.build.BlueprintGenerator.generate(style, size, seed, dev.autonomouscompanions.build.WoodPalette.byId(wood));
		AABB box = bp.worldBounds(origin, facing);
		double y = origin.getY() + 1.1;
		for (double x = box.minX; x <= box.maxX; x += 0.5) {
			level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, x, y, box.minZ, 1, 0, 0, 0, 0);
			level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, x, y, box.maxZ, 1, 0, 0, 0, 0);
		}
		for (double z = box.minZ; z <= box.maxZ; z += 0.5) {
			level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, box.minX, y, z, 1, 0, 0, 0, 0);
			level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, false, box.maxX, y, z, 1, 0, 0, 0, 0);
		}
	}

	public static void handleBuildDecision(ServerPlayer player, Payloads.BuildDecision p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null) {
			return;
		}
		if (p.kind() == Payloads.BuildMark.TERRAIN) {
			handleTerrainDecision(player, c, p);
			return;
		}
		int teamSize = Math.max(1, Math.min(3, p.team()));
		Direction facing = Direction.from2DDataValue(p.facing());
		boolean prepare = p.prepareTerrain() && ConfigManager.get().permitirPrepararTerreno;
		if (!p.confirm()) {
			sendPreview(player, p.entityId(), p.clicked(), facing, p.style(), p.size(), p.seed(), prepare, teamSize);
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		HouseStyle style = HouseStyle.byIndex(p.style());
		HouseSize size = HouseSize.byIndex(p.size());
		BuildPlanner.Preview pv = BuildPlanner.preview(level, style, size, p.seed(), p.clicked(), facing, prepare, null);
		if (!pv.valid()) {
			player.sendSystemMessage(Component.translatable("build.autonomouscompanions.invalid_site"), true);
			sendPreview(player, p.entityId(), p.clicked(), facing, p.style(), p.size(), p.seed(), prepare, teamSize);
			return;
		}
		if (c.orders().firstOfType(OrderType.BUILD) != null) {
			player.sendSystemMessage(Component.translatable("build.autonomouscompanions.already_building", c.getDisplayName()), true);
			return;
		}
		if (!c.orders().canAdd()) {
			player.sendSystemMessage(Component.translatable("order.autonomouscompanions.queue_full"), true);
			return;
		}
		BuildProject project = new BuildProject(UUID.randomUUID(), player.getUUID(), c.getUUID(), style, p.seed(), size, pv.origin(), facing,
			level.dimension(), BuildProject.Status.APPROVED, prepare, pv.wood(), 0, pv.blockCount(), 0);
		// Equipe: a líder constrói; as outras buscam a parte delas dos materiais e entregam a ela.
		List<CompanionEntity> team = pickTeam(player, c, teamSize);
		List<UUID> ids = new ArrayList<>();
		for (CompanionEntity e : team) {
			ids.add(e.getUUID());
		}
		project.setWorkers(ids);
		CompanionWorldData.get(level.getServer()).putProject(project);
		for (CompanionEntity e : team) {
			e.orders().add(Order.build(project.id(), level.getGameTime()));
			e.brain().requestDecision();
		}
		if (team.size() > 1) {
			player.sendSystemMessage(Component.translatable("build.autonomouscompanions.team", teamNames(team)));
		}
		if (team.size() < teamSize) {
			player.sendSystemMessage(Component.translatable("build.autonomouscompanions.team_short", team.size(), teamSize), true);
		}
		player.sendSystemMessage(Component.translatable("build.autonomouscompanions.approved", c.getDisplayName(),
			Component.translatable(style.translationKey())));
		refresh(player, c);
	}

	public static void handleBuildControl(ServerPlayer player, Payloads.BuildControl p) {
		CompanionEntity c = owned(player, p.entityId());
		if (c == null) {
			return;
		}
		ServerLevel level = (ServerLevel) player.level();
		Order o = c.orders().firstOfType(OrderType.BUILD);
		if (o == null) {
			return;
		}
		switch (p.action()) {
			case Payloads.BuildControl.SUSPEND -> c.brain().orders().suspend(level, o);
			case Payloads.BuildControl.RESUME -> {
				o.setReason(null);
				c.orders().setStatus(o, OrderStatus.PENDING);
			}
			case Payloads.BuildControl.CANCEL -> {
				c.brain().orders().cancel(level, o);
				cancelBuildProject(level, o);
			}
			default -> {
			}
		}
		c.brain().requestDecision();
		refresh(player, c);
	}
}
