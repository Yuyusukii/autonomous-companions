package dev.autonomouscompanions.client;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Catálogo pesquisável com TODOS os blocos registrados no jogo (inclusive de outros mods), construído a partir do
 * registro real. Pesquisa por nome traduzido, nome sem acentos e identificador do registro.
 */
public final class BlockCatalog {
	public record Entry(Block block, String id, Component name, String searchable, ItemStack icon) {
	}

	private static List<Entry> entries;
	private static String builtForLanguage = "";

	private BlockCatalog() {
	}

	public static String normalize(String s) {
		String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return n.toLowerCase(Locale.ROOT);
	}

	public static synchronized List<Entry> all() {
		String lang = net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected();
		if (entries == null || !lang.equals(builtForLanguage)) {
			List<Entry> list = new ArrayList<>();
			for (Block block : BuiltInRegistries.BLOCK) {
				if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
					continue;
				}
				String id = BuiltInRegistries.BLOCK.getKey(block).toString();
				Component name = block.getName();
				String text = name.getString();
				ItemStack icon = block.asItem() == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(block.asItem());
				list.add(new Entry(block, id, name, normalize(text) + " " + id, icon));
			}
			list.sort(Comparator.comparing(e -> normalize(e.name().getString())));
			entries = list;
			builtForLanguage = lang;
		}
		return entries;
	}

	public static List<Entry> search(String query) {
		String q = normalize(query.trim());
		if (q.isEmpty()) {
			return all();
		}
		String[] terms = q.split("\\s+");
		List<Entry> out = new ArrayList<>();
		for (Entry e : all()) {
			boolean ok = true;
			for (String t : terms) {
				if (!e.searchable().contains(t)) {
					ok = false;
					break;
				}
			}
			if (ok) {
				out.add(e);
			}
		}
		return out;
	}
}
