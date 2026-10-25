package dev.autonomouscompanions.client.screen;

import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.network.Payloads;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Menu do apito: chamar uma companheira específica ou as três de uma vez. Atalhos: 1, 2, 3… chamam cada uma;
 * 0 ou T chamam todas. Shift + botão direito com o apito chama todas sem abrir o menu.
 */
public class WhistleScreen extends Screen {
	private static final int KEY_0 = 48;
	private static final int KEY_T = 84;
	private final List<Payloads.WhistleEntry> entries;

	public WhistleScreen(List<Payloads.WhistleEntry> entries) {
		super(Component.translatable("gui.autonomouscompanions.whistle.title"));
		this.entries = entries;
	}

	private int top() {
		return this.height / 2 - (40 + entries.size() * 24) / 2;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int y = top() + 30;
		Button all = Button.builder(Component.translatable("gui.autonomouscompanions.whistle.all"), b -> call(true, new UUID(0, 0)))
			.bounds(cx - 100, y, 200, 20).build();
		all.active = !entries.isEmpty();
		this.addRenderableWidget(all);
		y += 26;
		for (int i = 0; i < entries.size(); i++) {
			Payloads.WhistleEntry e = entries.get(i);
			Component where = e.distance() < 0 ? Component.translatable("gui.autonomouscompanions.whistle.other_dimension")
				: Component.translatable("gui.autonomouscompanions.whistle.blocks", e.distance());
			Component label = Component.literal((i + 1) + ". " + e.name() + " — ").append(where);
			Button b = Button.builder(label, btn -> call(false, e.id())).bounds(cx - 100, y, 200, 20).build();
			b.setTooltip(Tooltip.create(Component.translatable("gui.autonomouscompanions.whistle.tooltip", Math.round(e.health() * 100),
				Component.translatable(CompanionState.byIndex(e.state()).translationKey()))));
			this.addRenderableWidget(b);
			y += 24;
		}
		this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.cancel"), b -> this.onClose())
			.bounds(cx - 50, y + 4, 100, 20).build());
	}

	private void call(boolean all, UUID target) {
		ClientPlayNetworking.send(new Payloads.WhistleCall(all, target));
		this.onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == KEY_0 || key == KEY_T) {
			call(true, new UUID(0, 0));
			return true;
		}
		int index = key - KEY_0 - 1;
		if (index >= 0 && index < Math.min(9, entries.size())) {
			call(false, entries.get(index).id());
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int cx = this.width / 2;
		int top = top();
		Ui.panel(graphics, cx - 130, top, 260, 70 + entries.size() * 24 + 6);
		graphics.centeredText(this.font, this.title, cx, top + 8, Ui.ACCENT);
		graphics.centeredText(this.font, entries.isEmpty() ? Component.translatable("gui.autonomouscompanions.whistle.empty")
			: Component.translatable("gui.autonomouscompanions.whistle.hint"), cx, top + 18, Ui.MUTED);
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
