package dev.autonomouscompanions.spawn;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionVariant;
import dev.autonomouscompanions.nav.SafeSpot;
import dev.autonomouscompanions.persist.CompanionWorldData;
import dev.autonomouscompanions.registry.ModEntities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import org.jspecify.annotations.Nullable;

/**
 * Cria as três companheiras iniciais quando o jogador entra pela primeira vez no mundo. O registro persistente do mundo
 * garante que isso aconteça uma única vez (não ressurgem ao reentrar, morrer, trocar de dimensão ou recarregar chunks).
 * As posições são verificadas (chão firme, sem sufocar, sem lava/água) e, se o lugar for ruim, procuram-se alternativas.
 */
public final class StarterSpawner {
	private static final Map<UUID, Integer> PENDING = new HashMap<>();
	/** Jogadoras que já têm as três, mas ainda não a ajudante (mundos criados antes dela existir). */
	private static final java.util.Set<UUID> PENDING_HELPER = new java.util.HashSet<>();
	/** Nome fixo da ajudante (ela não é nomeada pela dona). */
	public static final String HELPER_NAME = "Ajudante";
	private static final java.util.Set<UUID> FORCED = new java.util.HashSet<>();
	private static final int FIRST_DELAY = 40;
	private static final int MAX_ATTEMPTS = 30;

	private StarterSpawner() {
	}

	public static void onPlayerJoin(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		CompanionWorldData data = CompanionWorldData.get(server);
		// Ela já nasce com o apito (uma única vez por jogadora, também em mundos criados antes do apito existir).
		giveWhistleOnce(player);
		if (data.hasReceivedStarters(player.getUUID())) {
			dev.autonomouscompanions.yuno.YunoManager.introduce(player); // mundos criados antes da Yuno existir
		}
		if (data.hasReceivedStarters(player.getUUID()) && !data.hasHelper(player.getUUID())) {
			PENDING_HELPER.add(player.getUUID());
		}
		if (!shouldReceive(data, player.getUUID())) {
			return;
		}
		PENDING.put(player.getUUID(), -FIRST_DELAY);
	}

	/**
	 * Agenda a criação das três companheiras para este jogador ignorando o modo automático (usado pelo comando de
	 * operador e pelos testes). Continua valendo a regra de nunca entregar duas vezes ao mesmo jogador.
	 */
	public static boolean queue(ServerPlayer player) {
		CompanionWorldData data = CompanionWorldData.get(player.level().getServer());
		if (data.hasReceivedStarters(player.getUUID())) {
			return false;
		}
		PENDING.put(player.getUUID(), -FIRST_DELAY);
		FORCED.add(player.getUUID());
		return true;
	}

	public static boolean isPending(UUID player) {
		return PENDING.containsKey(player);
	}

	public static void clearPending() {
		PENDING.clear();
		PENDING_HELPER.clear();
		FORCED.clear();
	}

	public static boolean shouldReceive(CompanionWorldData data, UUID player) {
		if (data.hasReceivedStarters(player)) {
			return false;
		}
		String mode = ConfigManager.get().modoCompanheirasIniciais;
		if (mode.equals("DESATIVADO")) {
			return false;
		}
		return !(mode.equals("PRIMEIRO_JOGADOR") && data.firstStarterGiven());
	}

	/** Agendadas por {@link #queue} continuam válidas mesmo com o modo automático desativado. */
	private static boolean stillEligible(CompanionWorldData data, UUID player, boolean forced) {
		return forced ? !data.hasReceivedStarters(player) : shouldReceive(data, player);
	}

	public static void tick(MinecraftServer server) {
		tickHelpers(server);
		if (PENDING.isEmpty()) {
			return;
		}
		for (var it = PENDING.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			int t = e.getValue() + 1;
			e.setValue(t);
			if (t < 0 || t % 20 != 0) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null || t / 20 > MAX_ATTEMPTS) {
				FORCED.remove(e.getKey());
				it.remove();
				continue;
			}
			CompanionWorldData data = CompanionWorldData.get(server);
			if (!stillEligible(data, player.getUUID(), FORCED.contains(player.getUUID()))) {
				giveWhistleOnce(player);
				FORCED.remove(player.getUUID());
				it.remove();
				continue;
			}
			List<CompanionEntity> spawned = spawnStarters(player);
			if (spawned != null) {
				data.markStartersGiven(player.getUUID());
				if (spawned.size() > 3) {
					data.markHelperGiven(player.getUUID());
				} else {
					PENDING_HELPER.add(player.getUUID());
				}
				for (CompanionEntity c : spawned) {
					data.updateCompanion(c);
				}
				FORCED.remove(player.getUUID());
				it.remove();
				AutonomousCompanions.LOGGER.info("Três companheiras criadas para {}", player.getGameProfile().name());
				dev.autonomouscompanions.yuno.YunoManager.introduce(player); // a Yuno também chega, escondida
			}
		}
	}

	/** Entrega o apito uma única vez por jogadora (no inventário; se estiver cheio, aos pés dela). */
	public static void giveWhistleOnce(ServerPlayer player) {
		if (!CompanionWorldData.get(player.level().getServer()).markWhistleGiven(player.getUUID())) {
			return;
		}
		giveWhistle(player);
	}

	public static void giveWhistle(ServerPlayer player) {
		net.minecraft.world.item.ItemStack whistle = new net.minecraft.world.item.ItemStack(dev.autonomouscompanions.registry.ModItems.WHISTLE);
		if (!player.getInventory().add(whistle)) {
			player.drop(whistle, false, net.minecraft.util.Prediction.SERVER_ONLY);
		}
		player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.autonomouscompanions.whistle_received"));
	}

	/** Cria as três companheiras perto do jogador; retorna nulo (sem criar nada) se não houver três locais seguros. */
	public static @Nullable List<CompanionEntity> spawnStarters(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		if (!player.isAlive() || !level.hasChunkAt(player.blockPosition())) {
			return null;
		}
		List<BlockPos> spots = new ArrayList<>();
		float yaw = player.getYRot();
		// Três ângulos à frente do jogador, a ~3 blocos, cada um com busca de alternativas próximas.
		// A quarta posição (um pouco atrás) é a da ajudante.
		double[] offsets = {-40, 0, 40, 110};
		for (double off : offsets) {
			double a = Math.toRadians(yaw + off);
			int x = Mth.floor(player.getX() - Math.sin(a) * 3.0);
			int z = Mth.floor(player.getZ() + Math.cos(a) * 3.0);
			BlockPos center = new BlockPos(x, player.getBlockY(), z);
			BlockPos spot = findDistinct(level, center, spots);
			if (spot == null) {
				spot = findDistinct(level, player.blockPosition(), spots);
			}
			if (spot == null) {
				if (spots.size() == 3) {
					break; // sem lugar para a ajudante agora: ela vem depois (fila da ajudante)
				}
				return null;
			}
			spots.add(spot);
		}
		List<CompanionEntity> list = new ArrayList<>();
		for (int i = 0; i < spots.size(); i++) {
			boolean helper = i == 3;
			CompanionEntity c = (helper ? ModEntities.HELPER : ModEntities.COMPANION).create(level, EntitySpawnReason.EVENT);
			if (c == null) {
				list.forEach(CompanionEntity::discard);
				return null;
			}
			BlockPos p = spots.get(i);
			double dx = player.getX() - (p.getX() + 0.5);
			double dz = player.getZ() - (p.getZ() + 0.5);
			float face = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
			c.snapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, face, 0.0F);
			c.setVariant(CompanionVariant.byIndex(i));
			c.setStarterIndex(i);
			c.setOwner(player);
			if (helper) {
				setupHelper(c);
			}
			c.finalizeSpawn(level, level.getCurrentDifficultyAt(p), EntitySpawnReason.EVENT, null);
			c.setYHeadRot(face);
			list.add(c);
		}
		for (CompanionEntity c : list) {
			level.addFreshEntityWithPassengers(c);
		}
		return list;
	}

	public static void setupHelper(CompanionEntity c) {
		c.setVariant(CompanionVariant.AJUDANTE);
		c.setCompanionName(HELPER_NAME);
		c.settings().idleExplore = false;
		c.settings().roleWork = false;
		c.settings().protectOwner = false;
		c.settings().proactiveDefense = false;
	}

	/** Cria só a ajudante perto da jogadora (mundos antigos ou comando). Retorna nula se não houver lugar seguro. */
	public static @Nullable CompanionEntity spawnHelper(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		if (!player.isAlive() || !level.hasChunkAt(player.blockPosition())) {
			return null;
		}
		BlockPos spot = findDistinct(level, player.blockPosition().relative(player.getDirection().getOpposite(), 2), List.of(player.blockPosition()));
		CompanionEntity c = spot == null ? null : ModEntities.HELPER.create(level, EntitySpawnReason.EVENT);
		if (c == null) {
			return null;
		}
		c.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, player.getYRot(), 0.0F);
		c.setOwner(player);
		setupHelper(c);
		c.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null);
		level.addFreshEntityWithPassengers(c);
		CompanionWorldData data = CompanionWorldData.get(level.getServer());
		data.markHelperGiven(player.getUUID());
		data.updateCompanion(c);
		player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.autonomouscompanions.helper_arrived", c.getDisplayName()));
		return c;
	}

	private static int helperTimer;

	private static void tickHelpers(MinecraftServer server) {
		if (PENDING_HELPER.isEmpty() || ++helperTimer < 60) {
			return;
		}
		helperTimer = 0;
		CompanionWorldData data = CompanionWorldData.get(server);
		for (var it = PENDING_HELPER.iterator(); it.hasNext(); ) {
			UUID id = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player == null || data.hasHelper(id)) {
				it.remove();
			} else if (spawnHelper(player) != null) {
				it.remove();
			}
		}
	}

	private static @Nullable BlockPos findDistinct(ServerLevel level, BlockPos center, List<BlockPos> taken) {
		for (int r = 0; r <= 8; r++) {
			for (BlockPos column : SafeSpot.ring(center, r)) {
				for (int dy = 0; dy <= 6; dy++) {
					for (int sign : new int[]{1, -1}) {
						BlockPos p = column.above(dy * sign);
						if (taken.stream().anyMatch(t -> t.distManhattan(p) < 2)) {
							continue;
						}
						if (SafeSpot.isSafeStand(level, p, ModEntities.COMPANION)) {
							return p;
						}
						if (dy == 0) {
							break;
						}
					}
				}
			}
		}
		return null;
	}
}
