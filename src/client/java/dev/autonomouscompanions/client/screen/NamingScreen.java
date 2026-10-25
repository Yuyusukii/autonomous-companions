package dev.autonomouscompanions.client.screen;

import dev.autonomouscompanions.entity.CompanionVariant;
import dev.autonomouscompanions.network.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Primeiro contato: campo para o nome, botão de confirmar e cancelar. Ao confirmar, o servidor grava o nome
 * permanentemente e responde abrindo o painel de comandos desta companheira.
 */
public class NamingScreen extends Screen {
	private final int entityId;
	private final CompanionVariant variant;
	private EditBox nameBox;
	private Button confirm;

	public NamingScreen(int entityId, int variant) {
		super(Component.translatable("gui.autonomouscompanions.naming.title"));
		this.entityId = entityId;
		this.variant = CompanionVariant.byIndex(variant);
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int cy = this.height / 2;
		this.nameBox = new EditBox(this.font, cx - 90, cy - 6, 180, 20, Component.translatable("gui.autonomouscompanions.naming.field"));
		this.nameBox.setMaxLength(24);
		this.nameBox.setHint(Component.translatable("gui.autonomouscompanions.naming.hint"));
		this.nameBox.setResponder(s -> updateConfirm());
		this.addRenderableWidget(this.nameBox);
		this.confirm = this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.naming.confirm"), b -> submit())
			.bounds(cx - 90, cy + 22, 86, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.autonomouscompanions.cancel"), b -> this.onClose())
			.bounds(cx + 4, cy + 22, 86, 20).build());
		this.setInitialFocus(this.nameBox);
		updateConfirm();
	}

	private void updateConfirm() {
		if (confirm != null) {
			confirm.active = !nameBox.getValue().trim().isEmpty();
		}
	}

	private void submit() {
		String name = nameBox.getValue().trim();
		if (name.isEmpty()) {
			return;
		}
		ClientPlayNetworking.send(new Payloads.Rename(entityId, name));
		// O painel de comandos abre automaticamente quando o servidor confirmar.
		this.onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation()) {
			submit();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int cx = this.width / 2;
		int cy = this.height / 2;
		Ui.panel(graphics, cx - 110, cy - 62, 220, 116);
		graphics.centeredText(this.font, this.title, cx, cy - 52, Ui.ACCENT);
		graphics.centeredText(this.font, Component.translatable("gui.autonomouscompanions.naming.subtitle",
			Component.translatable(variant.translationKey())), cx, cy - 36, Ui.MUTED);
		graphics.centeredText(this.font, Component.translatable("gui.autonomouscompanions.naming.persistent"), cx, cy - 22, Ui.MUTED);
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
