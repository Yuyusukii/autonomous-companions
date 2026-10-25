package dev.autonomouscompanions.client.screen;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.build.BuildProject;
import dev.autonomouscompanions.build.HouseSize;
import dev.autonomouscompanions.build.HouseStyle;
import dev.autonomouscompanions.client.BlockCatalog;
import dev.autonomouscompanions.client.ClientState;
import dev.autonomouscompanions.entity.CompanionRole;
import dev.autonomouscompanions.entity.CompanionSettings;
import dev.autonomouscompanions.network.CompanionSnapshot;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.OrderType;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Painel principal de uma companheira, organizado em 8 categorias: coleta/mineração, construção, combate, alimentação,
 * funções, inventário/entregas, estado/necessidades e configurações. Todos os botões enviam ordens reais ao servidor,
 * que valida e executa; a tela é atualizada pelos retratos periódicos do servidor.
 */
public class CompanionScreen extends ScaledPanelScreen {
	private static final int W = 368;
	private static final int H = 262;
	private static final String[] TABS = {"collect", "build", "combat", "food", "roles", "inventory", "status", "settings"};
	private static int lastTab = 6;

	private final int entityId;
	private int tab = lastTab;
	private int x0;
	private int y0;
	private String layoutSignature = "";

	// Coleta.
	private EditBox search;
	private EditBox quantityBox;
	private EditBox distanceBox;
	private List<BlockCatalog.Entry> results = new ArrayList<>();
	private int scroll;
	private BlockCatalog.@Nullable Entry selected;
	private static String lastSearch = "";
	private static int quantity = 64;
	private static int mode = GatherMode.DROPS.ordinal();
	private static int profile = SearchProfile.AUTO.ordinal();
	private static boolean urgent;
	// Construção.
	private static int style;
	private static int size = 1;
	private static long seed = System.currentTimeMillis() & 0xFFFF;
	private static boolean prepareTerrain = true;
	/** Tamanho da equipe da obra (1 = só ela; 2 ou 3 = com as mais próximas). */
	private static int team = 1;
	/** Lado da área de limpeza de terreno. */
	private static int terrainArea = 12;
	// Alimentação.
	private static int meatIndex;
	private static boolean cooked = true;
	private static int meatQuantity = 8;
	// Inventário.
	private int selectedSlot = -1;
	private static boolean giveMode;
	private static int giveSlot = -1;
	private static int giveQuantity = 1;
	private EditBox giveBox;

	public CompanionScreen(int entityId) {
		super(Component.translatable("gui.autonomouscompanions.panel.title"));
		this.entityId = entityId;
	}

	public int entityId() {
		return entityId;
	}

	private @Nullable CompanionSnapshot snap() {
		return ClientState.get(entityId);
	}

	@Override
	protected int panelWidth() {
		return W;
	}

	@Override
	protected int panelHeight() {
		return H;
	}

	@Override
	protected void init() {
		computeScale();
		x0 = (virtualWidth() - W) / 2;
		y0 = (virtualHeight() - H) / 2;
		for (int i = 0; i < TABS.length; i++) {
			final int idx = i;
			Button b = Button.builder(Component.translatable("gui.autonomouscompanions.tab." + TABS[i]), btn -> switchTab(idx))
				.bounds(x0 + 4 + i * 45, y0 + 4, 44, 18).build();
			b.setTooltip(Tooltip.create(Component.translatable("gui.autonomouscompanions.tab." + TABS[i] + ".title")));
			b.active = i != tab;
			this.addRenderableWidget(b);
		}
		CompanionSnapshot s = snap();
		if (s == null) {
			return;
		}
		switch (tab) {
			case 0 -> initCollect();
			case 1 -> initBuild(s);
			case 2 -> initCombat(s);
			case 3 -> initFood(s);
			case 4 -> initRoles(s);
			case 5 -> initInventory();
			case 6 -> initStatus(s);
			case 7 -> initSettings(s);
			default -> {
			}
		}
		layoutSignature = signature(s);
		this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.close"), b -> this.onClose())
			.bounds(x0 + W - 64, y0 + H - 22, 58, 18).build());
	}

	private void switchTab(int idx) {
		tab = idx;
		lastTab = idx;
		this.rebuildWidgets();
	}

	/** Partes do retrato que mudam a disposição dos botões (ex.: lista de ordens). */
	private String signature(CompanionSnapshot s) {
		StringBuilder sb = new StringBuilder();
		sb.append(tab).append('|').append(s.role()).append('|').append(s.build().map(b -> b.status() + "").orElse("-"));
		if (tab == 6) {
			for (var o : s.orders()) {
				sb.append(o.id()).append(o.status()).append(o.urgent());
			}
		}
		if (tab == 7 || tab == 2 || tab == 4) {
			CompanionSettings st = s.settings();
			sb.append(st.followMin).append(st.followMax).append(st.idleRadius).append(st.maxSearchDistance).append(st.idleExplore)
				.append(st.allowTerrainChanges).append(st.debug).append(st.protectOwner).append(st.proactiveDefense).append(st.autoEquip)
				.append(st.roleWork).append(st.allowCooperation).append(st.stay);
		}
		if (tab == 3) {
			sb.append(s.huntables().size());
		}
		return sb.toString();
	}

	public void onSnapshot(CompanionSnapshot s) {
		if (!signature(s).equals(layoutSignature)) {
			int keepScroll = scroll;
			String keepSearch = search != null ? search.getValue() : lastSearch;
			lastSearch = keepSearch;
			this.rebuildWidgets();
			scroll = keepScroll;
		}
	}

	private void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
		ClientPlayNetworking.send(payload);
	}

	private Button button(Component label, int x, int y, int w, Button.OnPress action) {
		return this.addRenderableWidget(Button.builder(label, action).bounds(x, y, w, 18).build());
	}

	private void sendSettings(CompanionSettings st) {
		send(new Payloads.UpdateSettings(entityId, st));
	}

	// ================================================================== 0. Coleta

	private void initCollect() {
		search = new EditBox(this.font, x0 + 8, y0 + 46, 170, 16, Component.translatable("gui.autonomouscompanions.collect.search"));
		search.setHint(Component.translatable("gui.autonomouscompanions.collect.search_hint"));
		search.setValue(lastSearch);
		search.setResponder(text -> {
			lastSearch = text;
			results = BlockCatalog.search(text);
			scroll = 0;
		});
		this.addRenderableWidget(search);
		results = BlockCatalog.search(lastSearch);
		int rx = x0 + 188;
		quantityBox = new EditBox(this.font, rx + 60, y0 + 64, 50, 16, Component.translatable("gui.autonomouscompanions.collect.quantity"));
		quantityBox.setValue(Integer.toString(quantity));
		quantityBox.setResponder(t -> {
			try {
				quantity = Math.max(1, Math.min(2304, Integer.parseInt(t.trim())));
			} catch (NumberFormatException ignored) {
			}
		});
		this.addRenderableWidget(quantityBox);
		int[] deltas = {-64, -16, -1, 1, 16, 64};
		for (int i = 0; i < deltas.length; i++) {
			final int d = deltas[i];
			button(Component.literal((d > 0 ? "+" : "") + d), rx + i * 29, y0 + 84, 28, b -> {
				quantity = Math.max(1, Math.min(2304, quantity + d));
				quantityBox.setValue(Integer.toString(quantity));
			});
		}
		button(Component.translatable("gui.autonomouscompanions.collect.mode", Component.translatable(GatherMode.values()[mode].translationKey())), rx, y0 + 106,
			172, b -> {
				mode = mode == GatherMode.EXACT_BLOCK.ordinal() ? GatherMode.DROPS.ordinal() : GatherMode.EXACT_BLOCK.ordinal();
				b.setMessage(Component.translatable("gui.autonomouscompanions.collect.mode", Component.translatable(GatherMode.values()[mode].translationKey())));
				ClientState.lastAnalysis = null;
			});
		button(Component.translatable("gui.autonomouscompanions.collect.profile", Component.translatable(SearchProfile.byIndex(profile).translationKey())), rx,
			y0 + 128, 172, b -> {
				profile = (profile + 1) % SearchProfile.values().length;
				b.setMessage(Component.translatable("gui.autonomouscompanions.collect.profile", Component.translatable(SearchProfile.byIndex(profile)
					.translationKey())));
			});
		distanceBox = new EditBox(this.font, rx + 110, y0 + 150, 62, 16, Component.translatable("gui.autonomouscompanions.collect.max_distance"));
		distanceBox.setValue("0");
		this.addRenderableWidget(distanceBox);
		button(Component.translatable("gui.autonomouscompanions.collect.urgent", Ui.onOff(urgent)), rx, y0 + 170, 84, b -> {
			urgent = !urgent;
			b.setMessage(Component.translatable("gui.autonomouscompanions.collect.urgent", Ui.onOff(urgent)));
		});
		button(Component.translatable("gui.autonomouscompanions.collect.analyze"), rx + 88, y0 + 170, 84, b -> {
			if (selected != null) {
				send(new Payloads.AnalyzeGather(entityId, selected.id(), mode, quantity));
			}
		});
		button(Component.translatable("gui.autonomouscompanions.collect.start"), rx, y0 + 192, 172, b -> {
			if (selected != null) {
				int maxDist = 0;
				try {
					maxDist = Math.max(0, Integer.parseInt(distanceBox.getValue().trim()));
				} catch (NumberFormatException ignored) {
				}
				send(new Payloads.StartGather(entityId, selected.id(), mode, quantity, profile, maxDist, urgent));
			}
		});
	}

	private int listTop() {
		return y0 + 66;
	}

	private static final int ROWS = 11;

	private void renderCollect(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int lx = x0 + 8;
		int ly = listTop();
		Ui.box(g, lx, ly, 170, ROWS * 16 + 4);
		for (int i = 0; i < ROWS; i++) {
			int idx = scroll + i;
			if (idx >= results.size()) {
				break;
			}
			BlockCatalog.Entry e = results.get(idx);
			int ry = ly + 2 + i * 16;
			boolean hover = mouseX >= lx && mouseX < lx + 170 && mouseY >= ry && mouseY < ry + 16;
			if (e == selected) {
				g.fill(lx + 1, ry, lx + 169, ry + 16, 0xFF3D5A80);
			} else if (hover) {
				g.fill(lx + 1, ry, lx + 169, ry + 16, 0xFF353B48);
			}
			g.item(e.icon(), lx + 2, ry);
			String name = this.font.plainSubstrByWidth(e.name().getString(), 146);
			g.text(this.font, name, lx + 20, ry + 4, Ui.TEXT, false);
			if (hover) {
				g.setTooltipForNextFrame(this.font, Component.literal(e.id()), realMouseX, realMouseY);
			}
		}
		g.text(this.font, Component.translatable("gui.autonomouscompanions.collect.count", results.size()), lx, ly + ROWS * 16 + 8, Ui.MUTED, false);
		int rx = x0 + 188;
		Component sel = selected == null ? Component.translatable("gui.autonomouscompanions.collect.none_selected") : selected.name();
		// Bloco escolhido: ícone + nome numa linha só (o rótulo vira dica ao passar o mouse).
		if (selected != null) {
			g.item(selected.icon(), rx, y0 + 42);
		}
		g.text(this.font, this.font.plainSubstrByWidth(sel.getString(), 150), rx + 20, y0 + 47, selected == null ? Ui.MUTED : Ui.ACCENT, false);
		if (mouseX >= rx && mouseX < rx + 172 && mouseY >= y0 + 42 && mouseY < y0 + 58) {
			g.setTooltipForNextFrame(this.font, Component.translatable("gui.autonomouscompanions.collect.selected").append(" ").append(sel), realMouseX, realMouseY);
		}
		g.text(this.font, Component.translatable("gui.autonomouscompanions.collect.quantity"), rx, y0 + 68, Ui.MUTED, false);
		g.text(this.font, Component.translatable("gui.autonomouscompanions.collect.max_distance"), rx, y0 + 154, Ui.MUTED, false);
		// Resultado da análise.
		Payloads.Analysis a = ClientState.lastAnalysis;
		int ay = y0 + 214;
		if (a != null && selected != null && a.blockId().equals(selected.id()) && a.mode() == mode) {
			int color = a.feasible() ? Ui.TEXT : Ui.BAD;
			boolean truncated = false;
			for (int i = 0; i < a.lines().size(); i++) {
				if (ay > y0 + H - 36) {
					truncated = true;
					break;
				}
				ay += Ui.wrapped(g, this.font, a.lines().get(i), rx, ay, 172, color);
			}
			if (truncated) {
				g.text(this.font, Component.translatable("gui.autonomouscompanions.collect.more"), rx, ay, Ui.MUTED, false);
			}
			// A análise completa aparece ao passar o mouse sobre a área.
			if (mouseX >= rx && mouseX < rx + 172 && mouseY >= y0 + 214 && mouseY < ay + 10) {
				List<net.minecraft.util.FormattedCharSequence> tip = new ArrayList<>();
				for (Component line : a.lines()) {
					tip.addAll(this.font.split(line, 240));
				}
				g.setTooltipForNextFrame(this.font, tip, realMouseX, realMouseY);
			}
		} else {
			Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.collect.help"), rx, ay, 172, Ui.MUTED);
		}
	}

	// ================================================================== 1. Construção

	private void initBuild(CompanionSnapshot s) {
		int x = x0 + 8;
		Button styleButton = button(Component.translatable("gui.autonomouscompanions.build.style", Component.translatable(HouseStyle.byIndex(style).translationKey())),
			x, y0 + 46, 176, b -> {
				style = (style + 1) % HouseStyle.values().length;
				this.rebuildWidgets();
			});
		styleButton.setTooltip(Tooltip.create(Component.translatable(HouseStyle.byIndex(style).descriptionKey())));
		button(Component.translatable("gui.autonomouscompanions.build.size", Component.translatable(HouseSize.byIndex(size).translationKey())), x, y0 + 68, 176,
			b -> {
				size = (size + 1) % HouseSize.values().length;
				b.setMessage(Component.translatable("gui.autonomouscompanions.build.size", Component.translatable(HouseSize.byIndex(size).translationKey())));
			});
		button(Component.translatable("gui.autonomouscompanions.build.variation", seed), x, y0 + 90, 176, b -> {
			seed = (seed * 31 + 17) & 0xFFFF;
			b.setMessage(Component.translatable("gui.autonomouscompanions.build.variation", seed));
		});
		button(Component.translatable("gui.autonomouscompanions.build.prepare", Ui.onOff(prepareTerrain)), x, y0 + 112, 176, b -> {
			prepareTerrain = !prepareTerrain;
			b.setMessage(Component.translatable("gui.autonomouscompanions.build.prepare", Ui.onOff(prepareTerrain)));
		});
		button(Component.translatable("gui.autonomouscompanions.build.team", team), x, y0 + 134, 176, b -> {
			team = team % 3 + 1;
			b.setMessage(Component.translatable("gui.autonomouscompanions.build.team", team));
		}).setTooltip(Tooltip.create(Component.translatable("gui.autonomouscompanions.build.team.tip")));
		boolean free = s.build().isEmpty() || s.build().get().status() >= BuildProject.Status.COMPLETED.ordinal();
		Button mark = button(Component.translatable("gui.autonomouscompanions.build.mark"), x, y0 + 156, 176, b -> {
			send(new Payloads.BuildMark(entityId, style, size, seed, prepareTerrain, Payloads.BuildMark.HOUSE, 0, team));
			this.onClose();
		});
		mark.active = free;
		// Limpar terreno: deixa uma área N×N plana (mato, árvores inteiras, morros e buracos).
		button(Component.translatable("gui.autonomouscompanions.build.area", terrainArea, terrainArea), x, y0 + 191, 86, b -> {
			int[] sizes = dev.autonomouscompanions.build.TerrainPlanner.SIZES;
			int i = 0;
			while (i < sizes.length && sizes[i] != terrainArea) {
				i++;
			}
			terrainArea = sizes[(i + 1) % sizes.length];
			b.setMessage(Component.translatable("gui.autonomouscompanions.build.area", terrainArea, terrainArea));
		});
		Button clear = button(Component.translatable("gui.autonomouscompanions.build.clear"), x + 90, y0 + 191, 86, b -> {
			send(new Payloads.BuildMark(entityId, style, size, seed, true, Payloads.BuildMark.TERRAIN, terrainArea, team));
			this.onClose();
		});
		clear.setTooltip(Tooltip.create(Component.translatable("gui.autonomouscompanions.build.clear.tip")));
		clear.active = free;
		if (s.build().isPresent()) {
			int bx = x0 + 196;
			button(Component.translatable("gui.autonomouscompanions.build.suspend"), bx, y0 + 196, 54, b -> send(new Payloads.BuildControl(entityId,
				Payloads.BuildControl.SUSPEND)));
			button(Component.translatable("gui.autonomouscompanions.build.resume"), bx + 56, y0 + 196, 54, b -> send(new Payloads.BuildControl(entityId,
				Payloads.BuildControl.RESUME)));
			button(Component.translatable("gui.autonomouscompanions.build.cancel"), bx + 112, y0 + 196, 54, b -> send(new Payloads.BuildControl(entityId,
				Payloads.BuildControl.CANCEL)));
		}
	}

	private void renderBuild(GuiGraphicsExtractor g, CompanionSnapshot s) {
		int x = x0 + 8;
		g.text(this.font, Component.translatable("gui.autonomouscompanions.build.terrain_title"), x, y0 + 180, Ui.ACCENT, false);
		int bx = x0 + 196;
		g.text(this.font, Component.translatable("gui.autonomouscompanions.build.project"), bx, y0 + 46, Ui.ACCENT, false);
		if (s.build().isEmpty()) {
			Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.build.no_project"), bx, y0 + 60, 164, Ui.MUTED);
			return;
		}
		var b = s.build().get();
		if (b.kind() == 1) {
			g.text(this.font, Component.translatable("gui.autonomouscompanions.build.terrain_project", b.area(), b.area()), bx, y0 + 60, Ui.TEXT, false);
		} else {
			g.text(this.font, Component.translatable(HouseStyle.byIndex(b.style()).translationKey()), bx, y0 + 60, Ui.TEXT, false);
		}
		g.text(this.font, b.team() > 1 ? Component.translatable(b.role() == 0 ? "gui.autonomouscompanions.build.role_leader" : (b.kind() == 1
			? "gui.autonomouscompanions.build.role_band" : "gui.autonomouscompanions.build.role_helper"), b.team(), b.role() + 1)
			: b.kind() == 1 ? Component.translatable("gui.autonomouscompanions.build.role_alone") : Component.translatable(HouseSize.byIndex(b.size()).translationKey()),
			bx, y0 + 72, Ui.MUTED, false);
		g.text(this.font, Component.translatable(BuildProject.Status.values()[b.status()].translationKey()), bx, y0 + 84, Ui.WARN, false);
		Ui.bar(g, this.font, bx, y0 + 98, 164, b.percent() / 100f, 0xFF4F9D69, Component.literal(b.placed() + "/" + b.total() + " (" + b.percent() + "%)"));
		g.text(this.font, Component.translatable("gui.autonomouscompanions.build.origin", b.origin().getX(), b.origin().getY(), b.origin().getZ()), bx, y0 + 112,
			Ui.MUTED, false);
		int y = y0 + 126;
		for (Component c : s.taskStack()) {
			y += Ui.wrapped(g, this.font, Component.literal("› ").append(c), bx, y, 164, Ui.TEXT);
			if (y > y0 + 186) {
				break;
			}
		}
	}

	// ================================================================== 2. Combate

	private void initCombat(CompanionSnapshot s) {
		CompanionSettings st = s.settings();
		int x = x0 + 8;
		button(Component.translatable("gui.autonomouscompanions.combat.protect", Ui.onOff(st.protectOwner)), x, y0 + 46, 176, b -> {
			st.protectOwner = !st.protectOwner;
			sendSettings(st);
		});
		button(Component.translatable("gui.autonomouscompanions.combat.proactive", Ui.onOff(st.proactiveDefense)), x, y0 + 68, 176, b -> {
			st.proactiveDefense = !st.proactiveDefense;
			sendSettings(st);
		});
		button(Component.translatable("gui.autonomouscompanions.combat.auto_equip", Ui.onOff(st.autoEquip)), x, y0 + 90, 176, b -> {
			st.autoEquip = !st.autoEquip;
			sendSettings(st);
		});
		button(Component.translatable("gui.autonomouscompanions.combat.prepare"), x, y0 + 118, 176,
			b -> send(new Payloads.SimpleCommand(entityId, Payloads.SimpleCommand.EQUIP)));
	}

	private void renderCombat(GuiGraphicsExtractor g, CompanionSnapshot s, int mouseX, int mouseY) {
		int bx = x0 + 196;
		g.text(this.font, Component.translatable("gui.autonomouscompanions.combat.equipment"), bx, y0 + 46, Ui.ACCENT, false);
		String[] labels = {"main", "off", "head", "chest", "legs", "feet"};
		for (int i = 0; i < s.equipment().size(); i++) {
			int sx = bx + (i % 3) * 56;
			int sy = y0 + 60 + (i / 3) * 34;
			Ui.box(g, sx + 15, sy, 18, 18);
			ItemStack st = s.equipment().get(i);
			g.item(st, sx + 16, sy + 1);
			// Nome do espaço centralizado embaixo da caixa (não invade a caixa vizinha).
			g.centeredText(this.font, Component.translatable("gui.autonomouscompanions.slot." + labels[i]), sx + 24, sy + 21, Ui.MUTED);
			if (mouseX >= sx + 15 && mouseX < sx + 33 && mouseY >= sy && mouseY < sy + 18 && !st.isEmpty()) {
				g.setTooltipForNextFrame(this.font, st, realMouseX, realMouseY);
			}
		}
		g.text(this.font, Component.translatable("gui.autonomouscompanions.combat.stats", s.stats().kills(), s.stats().deaths()), bx, y0 + 134, Ui.TEXT, false);
		Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.combat.help"), x0 + 8, y0 + 146, 176, Ui.MUTED);
	}

	// ================================================================== 3. Alimentação

	private List<String[]> meats(CompanionSnapshot s) {
		List<String[]> list = new ArrayList<>();
		if (!s.huntables().isEmpty()) {
			// Primeira opção (padrão): a carne do animal mais próximo, decidida pelo servidor na hora do pedido.
			list.add(new String[]{dev.autonomouscompanions.task.OrderService.NEAREST_MEAT, dev.autonomouscompanions.task.OrderService.NEAREST_MEAT_COOKED});
		}
		for (String h : s.huntables()) {
			list.add(h.split(">", -1));
		}
		return list;
	}

	private void initFood(CompanionSnapshot s) {
		int x = x0 + 8;
		List<String[]> meats = meats(s);
		if (!meats.isEmpty()) {
			meatIndex = Math.floorMod(meatIndex, meats.size());
			button(Component.translatable("gui.autonomouscompanions.food.meat", itemName(meats.get(meatIndex)[0])), x, y0 + 100, 176, b -> {
				meatIndex = (meatIndex + 1) % meats.size();
				this.rebuildWidgets();
			});
			button(Component.translatable("gui.autonomouscompanions.food.cooked", Ui.onOff(cooked)), x, y0 + 122, 176, b -> {
				cooked = !cooked;
				b.setMessage(Component.translatable("gui.autonomouscompanions.food.cooked", Ui.onOff(cooked)));
			});
			int[] deltas = {-8, -1, 1, 8};
			for (int i = 0; i < deltas.length; i++) {
				final int d = deltas[i];
				button(Component.literal((d > 0 ? "+" : "") + d), x + i * 30, y0 + 144, 28, b -> meatQuantity = Math.max(1, Math.min(256, meatQuantity + d)));
			}
			button(Component.translatable("gui.autonomouscompanions.food.order"), x, y0 + 168, 176, b -> {
				String[] m = meats.get(meatIndex);
				String id = cooked && m.length > 1 && !m[1].isEmpty() ? m[1] : m[0];
				send(new Payloads.StartItemOrder(entityId, id, meatQuantity));
			});
		}
		button(Component.translatable("gui.autonomouscompanions.food.eat_now"), x0 + 196, y0 + 168, 164,
			b -> send(new Payloads.SimpleCommand(entityId, Payloads.SimpleCommand.EAT)));
	}

	private Component itemName(String id) {
		if (id.equals(dev.autonomouscompanions.task.OrderService.NEAREST_MEAT)) {
			return Component.translatable("gui.autonomouscompanions.food.nearest");
		}
		if (id.equals(dev.autonomouscompanions.task.Order.ANY_MEAT)) {
			return Component.translatable("order.autonomouscompanions.group.any_meat");
		}
		if (id.equals(dev.autonomouscompanions.task.Order.ANY_COOKED_MEAT)) {
			return Component.translatable("order.autonomouscompanions.group.any_cooked_meat");
		}
		Identifier key = Identifier.tryParse(id);
		Item item = key == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(key).orElse(Items.AIR);
		return new ItemStack(item).getHoverName();
	}

	private void renderFood(GuiGraphicsExtractor g, CompanionSnapshot s) {
		int x = x0 + 8;
		Ui.bar(g, this.font, x, y0 + 48, 176, s.hunger() / 20f, 0xFFC08A3E, Component.translatable("gui.autonomouscompanions.status.hunger", s.hunger()));
		Ui.bar(g, this.font, x, y0 + 62, 176, Math.min(1, s.saturation() / 20f), 0xFFE0C060,
			Component.translatable("gui.autonomouscompanions.food.saturation", String.format("%.1f", s.saturation())));
		if (meats(s).isEmpty()) {
			Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.food.loading"), x, y0 + 100, 176, Ui.MUTED);
		} else {
			g.text(this.font, Component.translatable("gui.autonomouscompanions.food.quantity", meatQuantity), x + 124, y0 + 149, Ui.TEXT, false);
		}
		int bx = x0 + 196;
		g.text(this.font, Component.translatable("gui.autonomouscompanions.food.stock"), bx, y0 + 48, Ui.ACCENT, false);
		int y = y0 + 62;
		java.util.Map<Item, Integer> foods = new java.util.LinkedHashMap<>();
		for (ItemStack st : s.inventory()) {
			if (!st.isEmpty() && st.has(net.minecraft.core.component.DataComponents.FOOD)) {
				foods.merge(st.getItem(), st.getCount(), Integer::sum);
			}
		}
		for (var e : foods.entrySet()) {
			g.item(new ItemStack(e.getKey()), bx, y - 4);
			g.text(this.font, Component.literal(e.getValue() + "x ").append(new ItemStack(e.getKey()).getHoverName()), bx + 20, y, Ui.TEXT, false);
			y += 18;
			if (y > y0 + 150) {
				break;
			}
		}
		if (foods.isEmpty()) {
			g.text(this.font, Component.translatable("gui.autonomouscompanions.food.none"), bx, y, Ui.MUTED, false);
		}
		Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.food.help"), x, y0 + 192, 176, Ui.MUTED);
	}

	// ================================================================== 4. Funções

	private void initRoles(CompanionSnapshot s) {
		CompanionRole[] roles = CompanionRole.values();
		for (int i = 0; i < roles.length; i++) {
			final int idx = i;
			Button b = button(Component.translatable(roles[i].translationKey()), x0 + 8 + (i % 2) * 90, y0 + 46 + (i / 2) * 22, 88,
				btn -> send(new Payloads.SetRole(entityId, idx)));
			b.active = s.role() != i;
		}
		CompanionSettings st = s.settings();
		button(Component.translatable("gui.autonomouscompanions.roles.work", Ui.onOff(st.roleWork)), x0 + 8, y0 + 140, 178, b -> {
			st.roleWork = !st.roleWork;
			sendSettings(st);
		});
		button(Component.translatable("gui.autonomouscompanions.roles.coop", Ui.onOff(st.allowCooperation)), x0 + 8, y0 + 162, 178, b -> {
			st.allowCooperation = !st.allowCooperation;
			sendSettings(st);
		});
	}

	private void renderRoles(GuiGraphicsExtractor g, CompanionSnapshot s) {
		CompanionRole role = CompanionRole.byIndex(s.role());
		int bx = x0 + 196;
		g.text(this.font, Component.translatable("gui.autonomouscompanions.roles.current", Component.translatable(role.translationKey())), bx, y0 + 48,
			Ui.ACCENT, false);
		Ui.wrapped(g, this.font, Component.translatable(role.descriptionKey()), bx, y0 + 62, 164, Ui.TEXT);
	}

	// ================================================================== 5. Inventário

	private void initInventory() {
		int x = x0 + 196;
		if (!giveMode) {
			button(Component.translatable("gui.autonomouscompanions.inventory.deliver_selected"), x, y0 + 128, 164, b -> {
				if (selectedSlot >= 0) {
					send(new Payloads.Deliver(entityId, Payloads.Deliver.SLOT, selectedSlot));
				}
			});
			button(Component.translatable("gui.autonomouscompanions.inventory.deliver_mission"), x, y0 + 150, 164,
				b -> send(new Payloads.Deliver(entityId, Payloads.Deliver.MISSION, 0)));
			button(Component.translatable("gui.autonomouscompanions.inventory.deliver_materials"), x, y0 + 172, 164,
				b -> send(new Payloads.Deliver(entityId, Payloads.Deliver.MATERIALS, 0)));
		} else {
			// Dar itens da jogadora: quantidade exata (1 até a pilha inteira).
			giveBox = new EditBox(this.font, x + 76, y0 + 120, 40, 16, Component.translatable("gui.autonomouscompanions.inventory.give_quantity"));
			giveBox.setValue(Integer.toString(giveQuantity));
			giveBox.setResponder(t -> {
				try {
					giveQuantity = Math.max(1, Math.min(64 * 36, Integer.parseInt(t.trim())));
				} catch (NumberFormatException ignored) {
				}
			});
			this.addRenderableWidget(giveBox);
			int[] deltas = {-16, -1, 1, 16};
			for (int i = 0; i < deltas.length; i++) {
				final int d = deltas[i];
				button(Component.literal((d > 0 ? "+" : "") + d), x + i * 41, y0 + 140, 40, b -> {
					giveQuantity = Math.max(1, giveQuantity + d);
					giveBox.setValue(Integer.toString(giveQuantity));
				});
			}
			button(Component.translatable("gui.autonomouscompanions.inventory.give_all"), x, y0 + 162, 80, b -> {
				ItemStack st = playerSlotStack(giveSlot);
				if (!st.isEmpty()) {
					giveQuantity = st.getCount();
					giveBox.setValue(Integer.toString(giveQuantity));
				}
			});
			button(Component.translatable("gui.autonomouscompanions.inventory.give"), x + 84, y0 + 162, 80, b -> {
				if (giveSlot >= 0 && !playerSlotStack(giveSlot).isEmpty()) {
					send(new Payloads.GiveItems(entityId, giveSlot, giveQuantity));
				}
			});
		}
		button(Component.translatable(giveMode ? "gui.autonomouscompanions.inventory.view_hers" : "gui.autonomouscompanions.inventory.give_mode"), x, y0 + 194,
			164, b -> {
				giveMode = !giveMode;
				this.rebuildWidgets();
			});
	}

	/** Espaço do inventário da jogadora mostrado na posição {@code i} da grade (3 linhas do inventário + barra rápida). */
	private static int playerSlotFor(int i) {
		return i < 27 ? i + 9 : i - 27;
	}

	private ItemStack playerSlotStack(int slot) {
		if (slot < 0 || this.minecraft == null || this.minecraft.player == null) {
			return ItemStack.EMPTY;
		}
		return this.minecraft.player.getInventory().getItem(slot);
	}

	private int slotX(int i) {
		return x0 + 10 + (i % 9) * 19;
	}

	private int slotY(int i) {
		return y0 + 50 + (i / 9) * 19;
	}

	private void renderInventory(GuiGraphicsExtractor g, CompanionSnapshot s, int mouseX, int mouseY) {
		ItemStack hovered = ItemStack.EMPTY;
		if (giveMode) {
			g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.your_items"), x0 + 10, y0 + 40, Ui.ACCENT, false);
			for (int i = 0; i < 36; i++) {
				int sx = slotX(i);
				int sy = slotY(i) + (i >= 27 ? 4 : 0);
				int slot = playerSlotFor(i);
				g.fill(sx, sy, sx + 18, sy + 18, 0xFF2A2F3B);
				g.outline(sx, sy, 18, 18, slot == giveSlot ? Ui.ACCENT : Ui.BORDER);
				ItemStack st = playerSlotStack(slot);
				g.item(st, sx + 1, sy + 1);
				g.itemDecorations(this.font, st, sx + 1, sy + 1);
				if (mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18) {
					hovered = st;
				}
			}
			Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.inventory.give_help"), x0 + 10, y0 + 136, 176, Ui.MUTED);
			int bx = x0 + 196;
			g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.give_quantity"), bx, y0 + 124, Ui.TEXT, false);
			ItemStack chosen = playerSlotStack(giveSlot);
			g.text(this.font, chosen.isEmpty() ? Component.translatable("gui.autonomouscompanions.inventory.give_none")
				: Component.literal(this.font.plainSubstrByWidth(chosen.getHoverName().getString(), 150)), bx, y0 + 46, Ui.ACCENT, false);
			if (!chosen.isEmpty()) {
				g.item(chosen, bx, y0 + 58);
				g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.give_have", chosen.getCount()), bx + 20, y0 + 62, Ui.MUTED, false);
			}
			if (!hovered.isEmpty()) {
				g.setTooltipForNextFrame(this.font, hovered, realMouseX, realMouseY);
			}
			return;
		}
		g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.title"), x0 + 10, y0 + 40, Ui.ACCENT, false);
		for (int i = 0; i < s.inventory().size(); i++) {
			int sx = slotX(i);
			int sy = slotY(i);
			boolean reserved = i < s.reservedSlots().size() && s.reservedSlots().get(i);
			g.fill(sx, sy, sx + 18, sy + 18, reserved ? 0xFF3E5A3E : 0xFF2A2F3B);
			g.outline(sx, sy, 18, 18, i == selectedSlot ? Ui.ACCENT : Ui.BORDER);
			ItemStack st = s.inventory().get(i);
			g.item(st, sx + 1, sy + 1);
			g.itemDecorations(this.font, st, sx + 1, sy + 1);
			if (mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18) {
				hovered = st;
			}
		}
		g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.legend"), x0 + 10, y0 + 130, Ui.MUTED, false);
		int ex = x0 + 10;
		for (int i = 0; i < s.equipment().size(); i++) {
			int sx = ex + i * 22;
			Ui.box(g, sx, y0 + 146, 18, 18);
			g.item(s.equipment().get(i), sx + 1, y0 + 147);
			if (mouseX >= sx && mouseX < sx + 18 && mouseY >= y0 + 146 && mouseY < y0 + 164) {
				hovered = s.equipment().get(i);
			}
		}
		int bx = x0 + 196;
		g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.categories"), bx, y0 + 46, Ui.ACCENT, false);
		int tools = 0;
		int combat = 0;
		int food = 0;
		int mats = 0;
		int reservedCount = 0;
		for (int i = 0; i < s.inventory().size(); i++) {
			ItemStack st = s.inventory().get(i);
			if (st.isEmpty()) {
				continue;
			}
			if (i < s.reservedSlots().size() && s.reservedSlots().get(i)) {
				reservedCount += st.getCount();
			} else if (st.has(net.minecraft.core.component.DataComponents.TOOL)) {
				tools++;
			} else if (st.has(net.minecraft.core.component.DataComponents.WEAPON) || st.has(net.minecraft.core.component.DataComponents.EQUIPPABLE)) {
				combat++;
			} else if (st.has(net.minecraft.core.component.DataComponents.FOOD)) {
				food += st.getCount();
			} else {
				mats += st.getCount();
			}
		}
		g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.cat_work", tools), bx, y0 + 60, Ui.TEXT, false);
		g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.cat_combat", combat), bx, y0 + 72, Ui.TEXT, false);
		g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.cat_food", food), bx, y0 + 84, Ui.TEXT, false);
		g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.cat_owner", reservedCount), bx, y0 + 96, Ui.GOOD, false);
		g.text(this.font, Component.translatable("gui.autonomouscompanions.inventory.cat_materials", mats), bx, y0 + 108, Ui.TEXT, false);
		if (!hovered.isEmpty()) {
			g.setTooltipForNextFrame(this.font, hovered, realMouseX, realMouseY);
		}
	}

	// ================================================================== 6. Estado

	private void initStatus(CompanionSnapshot s) {
		int y = y0 + 150;
		int shown = 0;
		for (CompanionSnapshot.OrderView o : s.orders()) {
			if (shown >= 3) {
				break;
			}
			int ry = y + shown * 20;
			OrderStatus st = OrderStatus.values()[o.status()];
			int bx = x0 + W - 112;
			if (!st.isFinished()) {
				button(Component.literal("✕"), bx, ry, 20, b -> send(new Payloads.OrderControl(entityId, o.id(), Payloads.OrderControl.CANCEL)))
					.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.autonomouscompanions.order.cancel")));
				if (st == OrderStatus.SUSPENDED) {
					button(Component.literal("▶"), bx + 22, ry, 20, b -> send(new Payloads.OrderControl(entityId, o.id(), Payloads.OrderControl.RESUME)))
						.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.autonomouscompanions.order.resume")));
				} else {
					button(Component.literal("⏸"), bx + 22, ry, 20, b -> send(new Payloads.OrderControl(entityId, o.id(), Payloads.OrderControl.SUSPEND)))
						.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.autonomouscompanions.order.suspend")));
				}
				button(Component.literal(o.urgent() ? "★" : "☆"), bx + 44, ry, 20,
					b -> send(new Payloads.OrderControl(entityId, o.id(), Payloads.OrderControl.URGENT)))
					.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.autonomouscompanions.order.urgent")));
			} else if (st == OrderStatus.FAILED) {
				button(Component.literal("↻"), bx + 22, ry, 20, b -> send(new Payloads.OrderControl(entityId, o.id(), Payloads.OrderControl.RETRY)))
					.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.autonomouscompanions.order.retry")));
			}
			shown++;
		}
		if (!s.orders().isEmpty()) {
			button(Component.translatable("gui.autonomouscompanions.order.clear"), x0 + 8, y0 + H - 22, 120,
				b -> send(new Payloads.OrderControl(entityId, java.util.UUID.randomUUID(), Payloads.OrderControl.CLEAR_FINISHED)));
		}
	}

	private void renderStatus(GuiGraphicsExtractor g, CompanionSnapshot s) {
		int x = x0 + 8;
		Ui.bar(g, this.font, x, y0 + 46, 112, s.health() / Math.max(1, s.maxHealth()), 0xFFC0392B,
			Component.translatable("gui.autonomouscompanions.status.health", String.format("%.0f/%.0f", s.health(), s.maxHealth())));
		Ui.bar(g, this.font, x + 118, y0 + 46, 112, s.hunger() / 20f, 0xFFC08A3E, Component.translatable("gui.autonomouscompanions.status.hunger", s.hunger()));
		Ui.bar(g, this.font, x + 236, y0 + 46, 116, 1 - s.fatigue() / 100f, 0xFF5D7FC0,
			Component.translatable("gui.autonomouscompanions.status.rest", 100 - (int) s.fatigue()));
		CompanionState state = CompanionState.byIndex(s.state());
		g.text(this.font, Component.translatable("gui.autonomouscompanions.status.state").append(Component.translatable(state.translationKey())), x, y0 + 60,
			Ui.ACCENT, false);
		int y = y0 + 74 + Math.max(10, Ui.wrapped(g, this.font, s.detail(), x, y0 + 72, W - 20, Ui.TEXT));
		// Ordem ativa.
		CompanionSnapshot.OrderView active = null;
		for (var o : s.orders()) {
			if (!OrderStatus.values()[o.status()].isFinished() && OrderStatus.values()[o.status()] != OrderStatus.SUSPENDED) {
				active = o;
				break;
			}
		}
		if (active != null) {
			OrderType type = OrderType.values()[active.type()];
			Component target = itemName(active.target());
			g.text(this.font, Component.translatable("gui.autonomouscompanions.status.task", Component.translatable(type.translationKey()), target), x, y, Ui.TEXT,
				false);
			y += 11;
			if (type == OrderType.GATHER || type == OrderType.CRAFT) {
				g.text(this.font, Component.translatable("gui.autonomouscompanions.status.progress", active.obtained(), active.requested(), active.delivered()), x, y,
					Ui.TEXT, false);
				y += 11;
			}
			g.text(this.font, Component.translatable("gui.autonomouscompanions.status.metrics", String.format("%.0f", active.distance()), active.blocksMined(),
				active.crafted(), active.placed()), x, y, Ui.MUTED, false);
			y += 11;
			for (Component c : s.taskStack()) {
				g.text(this.font, this.font.plainSubstrByWidth("› " + c.getString(), W - 24), x + 4, y, Ui.MUTED, false);
				y += 10;
				if (y > y0 + 140) {
					break;
				}
			}
		} else {
			g.text(this.font, Component.translatable("gui.autonomouscompanions.status.no_task"), x, y, Ui.MUTED, false);
		}
		// Fila de ordens.
		int oy = y0 + 150;
		int shown = 0;
		for (CompanionSnapshot.OrderView o : s.orders()) {
			if (shown >= 3) {
				break;
			}
			OrderStatus st = OrderStatus.values()[o.status()];
			int color = st == OrderStatus.FAILED ? Ui.BAD : st == OrderStatus.COMPLETED ? Ui.GOOD : st == OrderStatus.SUSPENDED ? Ui.WARN : Ui.TEXT;
			Component line = Component.translatable(OrderType.values()[o.type()].translationKey()).append(" ").append(itemName(o.target()))
				.append(String.format(" %d/%d · ", o.delivered(), o.requested())).append(Component.translatable(st.translationKey()));
			g.text(this.font, this.font.plainSubstrByWidth(line.getString(), W - 130), x, oy + shown * 20 + 1, color, false);
			if (o.reason().isPresent()) {
				g.text(this.font, this.font.plainSubstrByWidth(o.reason().get().getString(), W - 130), x, oy + shown * 20 + 10, Ui.BAD, false);
			}
			shown++;
		}
		Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.status.stats", String.format("%.0f", s.stats().distance()), s.stats().mined(),
			s.stats().crafted(), s.stats().placed(), s.stats().meals(), s.stats().delivered()), x, y0 + 212, W - 16, Ui.MUTED);
	}

	// ================================================================== 7. Configurações

	private void initSettings(CompanionSnapshot s) {
		CompanionSettings st = s.settings();
		int x = x0 + 8;
		stepper(x, y0 + 46, Component.translatable("gui.autonomouscompanions.settings.follow_min", st.followMin), () -> {
			st.followMin = Math.max(1, st.followMin - 1);
			sendSettings(st);
		}, () -> {
			st.followMin = Math.min(st.followMax - 1, st.followMin + 1);
			sendSettings(st);
		});
		stepper(x, y0 + 68, Component.translatable("gui.autonomouscompanions.settings.follow_max", st.followMax), () -> {
			st.followMax = Math.max(st.followMin + 1, st.followMax - 1);
			sendSettings(st);
		}, () -> {
			st.followMax = Math.min(32, st.followMax + 1);
			sendSettings(st);
		});
		stepper(x, y0 + 90, Component.translatable("gui.autonomouscompanions.settings.idle_radius", st.idleRadius), () -> {
			st.idleRadius = Math.max(4, st.idleRadius - 8);
			sendSettings(st);
		}, () -> {
			st.idleRadius = st.idleRadius + 8;
			sendSettings(st);
		});
		stepper(x, y0 + 112, Component.translatable("gui.autonomouscompanions.settings.max_search",
			st.maxSearchDistance == 0 ? Component.translatable("gui.autonomouscompanions.auto") : Component.literal(Integer.toString(st.maxSearchDistance))), () -> {
			st.maxSearchDistance = st.maxSearchDistance <= 128 ? 0 : st.maxSearchDistance / 2;
			sendSettings(st);
		}, () -> {
			st.maxSearchDistance = st.maxSearchDistance == 0 ? 128 : Math.min(16384, st.maxSearchDistance * 2);
			sendSettings(st);
		});
		button(Component.translatable("gui.autonomouscompanions.settings.idle_explore", Ui.onOff(st.idleExplore)), x, y0 + 136, 176, b -> {
			st.idleExplore = !st.idleExplore;
			sendSettings(st);
		});
		button(Component.translatable("gui.autonomouscompanions.settings.terrain", Ui.onOff(st.allowTerrainChanges)), x, y0 + 158, 176, b -> {
			st.allowTerrainChanges = !st.allowTerrainChanges;
			sendSettings(st);
		});
		button(Component.translatable("gui.autonomouscompanions.settings.debug", Ui.onOff(st.debug)), x, y0 + 180, 176, b -> {
			st.debug = !st.debug;
			sendSettings(st);
		});
		int bx = x0 + 196;
		button(Component.translatable("gui.autonomouscompanions.cmd.follow"), bx, y0 + 46, 164,
			b -> send(new Payloads.SimpleCommand(entityId, Payloads.SimpleCommand.FOLLOW))).active = st.stay;
		button(Component.translatable("gui.autonomouscompanions.cmd.stay"), bx, y0 + 68, 164,
			b -> send(new Payloads.SimpleCommand(entityId, Payloads.SimpleCommand.STAY))).active = !st.stay;
		button(Component.translatable("gui.autonomouscompanions.cmd.come"), bx, y0 + 90, 164,
			b -> send(new Payloads.SimpleCommand(entityId, Payloads.SimpleCommand.COME_BACK)));
		button(Component.translatable("gui.autonomouscompanions.cmd.rest"), bx, y0 + 112, 164,
			b -> send(new Payloads.SimpleCommand(entityId, Payloads.SimpleCommand.REST)));
		button(Component.translatable("gui.autonomouscompanions.cmd.rename"), bx, y0 + 136, 164, b -> this.minecraft.gui.setScreen(new NamingScreen(entityId,
			s.variant())));
	}

	private void stepper(int x, int y, Component label, Runnable minus, Runnable plus) {
		button(Component.literal("-"), x, y, 20, b -> minus.run());
		button(label, x + 22, y, 132, b -> {
		}).active = false;
		button(Component.literal("+"), x + 156, y, 20, b -> plus.run());
	}

	// ================================================================== renderização e entrada

	@Override
	protected void extractPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Ui.panel(g, x0, y0, W, H);
		CompanionSnapshot s = snap();
		if (s == null) {
			g.centeredText(this.font, Component.translatable("gui.autonomouscompanions.loading"), virtualWidth() / 2, virtualHeight() / 2, Ui.MUTED);
			extractWidgets(g, mouseX, mouseY, a);
			return;
		}
		// Cabeçalho.
		g.text(this.font, Component.literal(s.name()), x0 + 8, y0 + 27, Ui.ACCENT, true);
		Component state = Component.translatable(CompanionState.byIndex(s.state()).translationKey());
		g.text(this.font, Component.literal("· ").append(state).append(" · ").append(Component.translatable(CompanionRole.byIndex(s.role()).translationKey())),
			x0 + 12 + this.font.width(s.name()), y0 + 27, Ui.MUTED, false);
		g.text(this.font, Component.literal(String.format("♥ %.0f/%.0f", s.health(), s.maxHealth())), x0 + W - 70, y0 + 27, Ui.BAD, false);
		switch (tab) {
			case 0 -> renderCollect(g, mouseX, mouseY);
			case 1 -> renderBuild(g, s);
			case 2 -> renderCombat(g, s, mouseX, mouseY);
			case 3 -> renderFood(g, s);
			case 4 -> renderRoles(g, s);
			case 5 -> renderInventory(g, s, mouseX, mouseY);
			case 6 -> renderStatus(g, s);
			default -> {
			}
		}
		extractWidgets(g, mouseX, mouseY, a);
	}

	@Override
	protected boolean panelMouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (tab == 0) {
			int lx = x0 + 8;
			int ly = listTop();
			if (mx >= lx && mx < lx + 170 && my >= ly + 2 && my < ly + 2 + ROWS * 16) {
				int idx = scroll + (int) ((my - ly - 2) / 16);
				if (idx >= 0 && idx < results.size()) {
					selected = results.get(idx);
					ClientState.lastAnalysis = null;
					send(new Payloads.AnalyzeGather(entityId, selected.id(), mode, quantity));
					return true;
				}
			}
		}
		if (tab == 5 && giveMode) {
			for (int i = 0; i < 36; i++) {
				int sy = slotY(i) + (i >= 27 ? 4 : 0);
				if (mx >= slotX(i) && mx < slotX(i) + 18 && my >= sy && my < sy + 18) {
					giveSlot = playerSlotFor(i);
					ItemStack st = playerSlotStack(giveSlot);
					if (!st.isEmpty() && giveBox != null) {
						giveQuantity = st.getCount();
						giveBox.setValue(Integer.toString(giveQuantity));
					}
					return true;
				}
			}
		}
		if (tab == 5) {
			CompanionSnapshot s = snap();
			if (s != null) {
				for (int i = 0; i < s.inventory().size(); i++) {
					if (mx >= slotX(i) && mx < slotX(i) + 18 && my >= slotY(i) && my < slotY(i) + 18) {
						selectedSlot = i;
						return true;
					}
				}
			}
		}
		return super.panelMouseClicked(event, doubleClick);
	}

	@Override
	protected boolean panelMouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (tab == 0 && mx >= x0 + 8 && mx < x0 + 178) {
			scroll = Math.max(0, Math.min(Math.max(0, results.size() - ROWS), scroll - (int) Math.signum(scrollY) * 3));
			return true;
		}
		return super.panelMouseScrolled(mx, my, scrollX, scrollY);
	}

	@Override
	public void removed() {
		send(new Payloads.Subscribe(entityId, false));
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
