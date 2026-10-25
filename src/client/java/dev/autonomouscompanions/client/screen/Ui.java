package dev.autonomouscompanions.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Utilitários de desenho das telas do mod (cores ARGB com alfa explícito). */
public final class Ui {
	public static final int PANEL = 0xEE1B1E26;
	public static final int PANEL_LIGHT = 0xFF2A2F3B;
	public static final int BORDER = 0xFF5B6478;
	public static final int ACCENT = 0xFF7FC8F8;
	public static final int TEXT = 0xFFF0F0F0;
	public static final int MUTED = 0xFFA8B0C0;
	public static final int GOOD = 0xFF7BD88F;
	public static final int WARN = 0xFFF3C969;
	public static final int BAD = 0xFFF07178;

	private Ui() {
	}

	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL);
		g.outline(x, y, w, h, BORDER);
	}

	public static void box(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, PANEL_LIGHT);
		g.outline(x, y, w, h, BORDER);
	}

	public static void bar(GuiGraphicsExtractor g, Font font, int x, int y, int w, float fraction, int color, Component label) {
		fraction = Math.max(0, Math.min(1, fraction));
		g.fill(x, y, x + w, y + 9, 0xFF101218);
		g.fill(x + 1, y + 1, x + 1 + (int) ((w - 2) * fraction), y + 8, color);
		g.outline(x, y, w, 9, BORDER);
		g.centeredText(font, label, x + w / 2, y + 1, TEXT);
	}

	/** Texto com quebra de linha; retorna a altura usada. */
	public static int wrapped(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int width, int color) {
		int line = 0;
		for (FormattedCharSequence seq : font.split(text, width)) {
			g.text(font, seq, x, y + line * 10, color, false);
			line++;
		}
		return line * 10;
	}

	public static Component onOff(boolean value) {
		return Component.translatable(value ? "gui.autonomouscompanions.on" : "gui.autonomouscompanions.off");
	}
}
