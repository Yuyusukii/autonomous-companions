package dev.autonomouscompanions.client.screen;

import dev.autonomouscompanions.build.HouseSize;
import dev.autonomouscompanions.build.HouseStyle;
import dev.autonomouscompanions.network.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Confirmação do local de construção: mostra área, altura, orientação, madeira escolhida, problemas e avisos do
 * terreno e a lista completa de materiais (com o que ela já tem). Permite girar, trocar a variação, autorizar o preparo
 * do terreno, confirmar ou cancelar.
 */
public class BuildPreviewScreen extends ScaledPanelScreen {
	private static final int W = 360;
	private static final int H = 250;
	private final Payloads.BuildPreview preview;
	private int x0;
	private int y0;
	private int scroll;

	public BuildPreviewScreen(Payloads.BuildPreview preview) {
		super(Component.translatable("gui.autonomouscompanions.preview.title"));
		this.preview = preview;
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
		int y = y0 + H - 24;
		if (terrain()) {
			// Limpeza: tamanho da área e equipe (recalcula a prévia), confirmar ou cancelar.
			this.addRenderableWidget(Button.builder(Component.literal("− ").append(Component.translatable("gui.autonomouscompanions.preview.area_short")),
				b -> requestTerrain(step(-1), preview.team(), false)).bounds(x0 + 6, y, 66, 18).build());
			this.addRenderableWidget(Button.builder(Component.literal("+ ").append(Component.translatable("gui.autonomouscompanions.preview.area_short")),
				b -> requestTerrain(step(1), preview.team(), false)).bounds(x0 + 74, y, 66, 18).build());
			this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.build.team", preview.team()),
				b -> requestTerrain(preview.width(), preview.team() % 3 + 1, false)).bounds(x0 + 142, y, 82, 18).build());
			Button ok = this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.preview.clear_confirm"), b -> {
				requestTerrain(preview.width(), preview.team(), true);
				this.onClose();
			}).bounds(x0 + 226, y, 66, 18).build());
			ok.active = preview.valid();
			this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.cancel"), b -> this.onClose())
				.bounds(x0 + 294, y, 60, 18).build());
			return;
		}
		this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.preview.rotate"), b -> request(
			(preview.facing() + 1) % 4, preview.seed(), preview.prepareTerrain(), false)).bounds(x0 + 6, y, 66, 18).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.preview.variation"), b -> request(preview.facing(),
			(preview.seed() * 31 + 17) & 0xFFFF, preview.prepareTerrain(), false)).bounds(x0 + 74, y, 66, 18).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.preview.prepare", Ui.onOff(preview.prepareTerrain())),
			b -> request(preview.facing(), preview.seed(), !preview.prepareTerrain(), false)).bounds(x0 + 142, y, 82, 18).build());
		Button confirm = this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.preview.confirm"), b -> {
			request(preview.facing(), preview.seed(), preview.prepareTerrain(), true);
			this.onClose();
		}).bounds(x0 + 226, y, 66, 18).build());
		confirm.active = preview.valid();
		this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.cancel"), b -> this.onClose())
			.bounds(x0 + 294, y, 60, 18).build());
	}

	private void request(int facing, long seed, boolean prepare, boolean confirm) {
		ClientPlayNetworking.send(new Payloads.BuildDecision(preview.entityId(), preview.clicked(), facing, preview.style(), preview.size(), seed, prepare,
			confirm, Payloads.BuildMark.HOUSE, 0, preview.team()));
	}

	private boolean terrain() {
		return preview.kind() == Payloads.BuildMark.TERRAIN;
	}

	/** Próximo tamanho de área (8, 12, 16, 24, 32). */
	private int step(int dir) {
		int[] sizes = dev.autonomouscompanions.build.TerrainPlanner.SIZES;
		int i = 0;
		while (i < sizes.length - 1 && sizes[i] < preview.width()) {
			i++;
		}
		return sizes[Math.max(0, Math.min(sizes.length - 1, i + dir))];
	}

	private void requestTerrain(int area, int team, boolean confirm) {
		ClientPlayNetworking.send(new Payloads.BuildDecision(preview.entityId(), preview.clicked(), 0, 0, 0, 0L, true, confirm, Payloads.BuildMark.TERRAIN,
			area, team));
	}

	@Override
	protected void extractPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Ui.panel(g, x0, y0, W, H);
		g.text(this.font, terrain() ? Component.translatable("gui.autonomouscompanions.preview.terrain_title") : this.title, x0 + 8, y0 + 8, Ui.ACCENT, true);
		int x = x0 + 8;
		int y = y0 + 22;
		Direction facing = Direction.from2DDataValue(preview.facing());
		if (terrain()) {
			y += Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.preview.terrain", preview.width(), preview.width()), x, y, 168, Ui.TEXT);
			y += Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.preview.terrain_level", preview.origin().getX(), preview.origin().getY(),
				preview.origin().getZ()), x, y, 168, Ui.MUTED);
			y += Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.preview.terrain_remove", preview.blocks(), preview.trees()), x, y, 168,
				Ui.TEXT);
			y += Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.preview.terrain_fill", preview.fill()), x, y, 168, Ui.TEXT);
			y += Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.preview.terrain_team", preview.team()), x, y, 168, Ui.MUTED);
			y += 2;
		} else {
		g.text(this.font, Component.translatable(HouseStyle.byIndex(preview.style()).translationKey()).append(" · ")
			.append(Component.translatable(HouseSize.byIndex(preview.size()).translationKey())).append(" · ")
			.append(Component.translatable("build.autonomouscompanions.wood." + preview.wood())), x, y, Ui.TEXT, false);
		y += 12;
		g.text(this.font, Component.translatable("gui.autonomouscompanions.preview.area", preview.width(), preview.depth(), preview.height(), preview.blocks()), x,
			y, Ui.TEXT, false);
		y += 12;
		g.text(this.font, Component.translatable("gui.autonomouscompanions.preview.where", preview.origin().getX(), preview.origin().getY(),
			preview.origin().getZ(), Component.translatable("gui.autonomouscompanions.dir." + facing.getName())), x, y, Ui.MUTED, false);
		y += 14;
		}
		g.text(this.font, preview.valid() ? Component.translatable("gui.autonomouscompanions.preview.ok") : Component.translatable(
			"gui.autonomouscompanions.preview.blocked"), x, y, preview.valid() ? Ui.GOOD : Ui.BAD, false);
		y += 12;
		for (Component p : preview.problems()) {
			y += Ui.wrapped(g, this.font, Component.literal("✖ ").append(p), x, y, 168, Ui.BAD);
		}
		for (Component w : preview.warnings()) {
			y += Ui.wrapped(g, this.font, Component.literal("⚠ ").append(w), x, y, 168, Ui.WARN);
		}
		// Materiais.
		int mx = x0 + 184;
		g.text(this.font, Component.translatable("gui.autonomouscompanions.preview.materials", preview.materials().size()), mx, y0 + 22, Ui.ACCENT, false);
		int my = y0 + 36;
		if (preview.materials().isEmpty()) {
			Ui.wrapped(g, this.font, Component.translatable("gui.autonomouscompanions.preview.no_materials"), mx, my, 150, Ui.MUTED);
		}
		int rows = (H - 70) / 18;
		for (int i = 0; i < rows; i++) {
			int idx = scroll + i;
			if (idx >= preview.materials().size()) {
				break;
			}
			Payloads.MaterialLine m = preview.materials().get(idx);
			Identifier key = Identifier.tryParse(m.item());
			ItemStack stack = new ItemStack(key == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(key).orElse(Items.AIR));
			g.item(stack, mx, my + i * 18);
			int color = m.have() >= m.needed() ? Ui.GOOD : Ui.TEXT;
			String name = this.font.plainSubstrByWidth(stack.getHoverName().getString(), 110);
			g.text(this.font, Component.literal(m.needed() + "x " + name), mx + 20, my + i * 18 + 4, color, false);
			g.text(this.font, Component.literal("(" + m.have() + ")"), mx + 140, my + i * 18 + 4, Ui.MUTED, false);
		}
		extractWidgets(g, mouseX, mouseY, a);
	}

	@Override
	protected boolean panelMouseScrolled(double mx, double my, double scrollX, double scrollY) {
		int rows = (H - 70) / 18;
		scroll = Math.max(0, Math.min(Math.max(0, preview.materials().size() - rows), scroll - (int) Math.signum(scrollY) * 2));
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
