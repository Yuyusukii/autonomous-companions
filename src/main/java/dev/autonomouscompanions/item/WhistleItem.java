package dev.autonomouscompanions.item;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.network.Payloads;
import dev.autonomouscompanions.registry.ModEntities;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Apito: ao usar (botão direito) abre um menu para chamar uma das companheiras ou todas de uma vez; com Shift chama
 * todas direto. As chamadas interrompem o que estavam fazendo e elas vêm o mais rápido possível (correndo; se
 * estiverem longe demais, sem caminho ou em outra dimensão, são trazidas para um lugar seguro ao lado da dona).
 * A ajudante não atende ao apito: ela continua abastecendo as outras.
 */
public final class WhistleItem extends Item {
	public WhistleItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel && player instanceof ServerPlayer sp) {
			if (player.isShiftKeyDown()) {
				call(sp, true, null);
			} else {
				ServerPlayNetworking.send(sp, new Payloads.WhistleMenu(entries(sp)));
			}
		}
		return InteractionResult.SUCCESS;
	}

	/** Companheiras da jogadora (sem a ajudante), em qualquer dimensão carregada. */
	public static List<CompanionEntity> companionsOf(ServerPlayer player) {
		List<CompanionEntity> mine = new ArrayList<>();
		for (ServerLevel level : player.level().getServer().getAllLevels()) {
			mine.addAll(level.getEntities(ModEntities.COMPANION, c -> c.isAlive() && c.isOwnedBy(player)));
		}
		mine.sort(Comparator.comparingInt(CompanionEntity::starterIndex).thenComparing(CompanionEntity::companionName));
		return mine;
	}

	public static List<Payloads.WhistleEntry> entries(ServerPlayer player) {
		List<Payloads.WhistleEntry> list = new ArrayList<>();
		for (CompanionEntity c : companionsOf(player)) {
			int dist = c.level() == player.level() ? (int) Math.round(c.distanceTo(player)) : -1;
			String name = c.isNamed() ? c.companionName() : c.getDisplayName().getString();
			list.add(new Payloads.WhistleEntry(c.getUUID(), name, c.variant().ordinal(), c.companionState().ordinal(), dist, c.getHealth() / c.getMaxHealth()));
		}
		return list;
	}

	/** Pacote do menu: valida que a companheira é da jogadora e que ela ainda tem um apito. */
	public static void handleCall(ServerPlayer player, boolean all, UUID target) {
		if (!player.getInventory().contains(s -> s.getItem() instanceof WhistleItem)) {
			return;
		}
		call(player, all, all ? null : target);
	}

	/** Toca o apito e chama todas ({@code all}) ou só a companheira {@code target}. Retorna quantas atenderam. */
	public static int call(ServerPlayer player, boolean all, UUID target) {
		ServerLevel level = (ServerLevel) player.level();
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.PLAYERS, 1.0F, 1.9F);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.PLAYERS, 0.8F, 1.6F);
		int called = 0;
		CompanionEntity only = null;
		for (CompanionEntity c : companionsOf(player)) {
			if (all || c.getUUID().equals(target)) {
				c.brain().recall(player);
				called++;
				only = c;
			}
		}
		if (called == 0) {
			player.sendSystemMessage(Component.translatable("message.autonomouscompanions.whistle_none"), true);
		} else if (!all && only != null) {
			player.sendSystemMessage(Component.translatable("message.autonomouscompanions.whistle_one", only.getDisplayName()), true);
		} else {
			player.sendSystemMessage(Component.translatable("message.autonomouscompanions.whistle", called), true);
		}
		return called;
	}

	/** Chama todas as companheiras desta jogadora. Retorna quantas atenderam. */
	public static int callAll(ServerPlayer player) {
		return call(player, true, null);
	}
}
