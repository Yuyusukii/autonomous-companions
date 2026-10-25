package dev.autonomouscompanions.registry;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.item.WhistleItem;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/** Itens do mod. */
public final class ModItems {
	public static final ResourceKey<Item> WHISTLE_KEY = ResourceKey.create(Registries.ITEM, AutonomousCompanions.id("apito"));

	/** Apito: chama as companheiras da dona para perto, interrompendo o que estiverem fazendo. */
	public static final Item WHISTLE = Registry.register(BuiltInRegistries.ITEM, WHISTLE_KEY,
		new WhistleItem(new Item.Properties().setId(WHISTLE_KEY).stacksTo(1).rarity(Rarity.UNCOMMON)));

	private ModItems() {
	}

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(WHISTLE));
	}
}
