package dev.autonomouscompanions.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Tela com um painel de tamanho fixo que é reduzido proporcionalmente quando a janela (ou a escala da interface) não
 * comporta o painel inteiro — as abas e os botões nunca ficam fora da tela. Os eventos de mouse são convertidos para
 * as coordenadas do painel; as dicas (tooltips) usam a posição real do cursor.
 */
public abstract class ScaledPanelScreen extends Screen {
	protected float uiScale = 1.0F;
	protected int realMouseX;
	protected int realMouseY;

	protected ScaledPanelScreen(Component title) {
		super(title);
	}

	protected abstract int panelWidth();

	protected abstract int panelHeight();

	/** Recalcula a escala; chamar no início de {@code init()}. */
	protected void computeScale() {
		float sx = this.width / (float) (panelWidth() + 8);
		float sy = this.height / (float) (panelHeight() + 8);
		uiScale = Math.min(1.0F, Math.min(sx, sy));
	}

	protected int virtualWidth() {
		return Math.round(this.width / uiScale);
	}

	protected int virtualHeight() {
		return Math.round(this.height / uiScale);
	}

	@Override
	public final void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		realMouseX = mouseX;
		realMouseY = mouseY;
		int mx = (int) (mouseX / uiScale);
		int my = (int) (mouseY / uiScale);
		graphics.pose().pushMatrix();
		graphics.pose().scale(uiScale, uiScale);
		extractPanel(graphics, mx, my, a);
		graphics.pose().popMatrix();
	}

	/** Desenho do painel em coordenadas virtuais (já escaladas). */
	protected abstract void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a);

	/** Desenha os botões e campos (chamar dentro de {@link #extractPanel}). */
	protected void extractWidgets(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	private MouseButtonEvent toPanel(MouseButtonEvent event) {
		return new MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo());
	}

	@Override
	public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return panelMouseClicked(toPanel(event), doubleClick);
	}

	protected boolean panelMouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public final boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(toPanel(event));
	}

	@Override
	public final boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return super.mouseDragged(toPanel(event), dx / uiScale, dy / uiScale);
	}

	@Override
	public final boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		return panelMouseScrolled(x / uiScale, y / uiScale, scrollX, scrollY);
	}

	protected boolean panelMouseScrolled(double x, double y, double scrollX, double scrollY) {
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void mouseMoved(double x, double y) {
		super.mouseMoved(x / uiScale, y / uiScale);
	}
}
