package dev.autonomouscompanions.spawn;

import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.persist.CompanionWorldData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Animais de criação por perto. Com três companheiras (e a ajudante) caçando, os animais da região acabam rápido e o
 * jogo quase não repõe animais de criação depois que o mundo é gerado. A cada 30 s, para cada jogadora que tem
 * companheiras: se houver menos que {@code animaisMinimosPerto} animais de criação adultos a até 64 blocos, aparece um
 * pequeno grupo (2 a 4) de um animal que o <b>próprio bioma</b> gera (nada de vacas no deserto), a 24–56 blocos dela,
 * num ponto que passa nas regras normais de spawn do jogo (chão de grama, luz, espaço livre).
 */
public final class AnimalBoost {
	/** Animais que dão carne (os que a ajudante e as companheiras caçam). */
	private static final Set<EntityType<?>> MEAT = Set.of(EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN, EntityTypes.RABBIT,
		EntityTypes.MOOSHROOM);
	private static final int INTERVAL = 20 * 30;
	private static final int RADIUS = 64;

	private AnimalBoost() {
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % INTERVAL != 13 || !ConfigManager.get().animaisPorPerto) {
			return;
		}
		Set<UUID> owners = new HashSet<>();
		for (CompanionWorldData.CompanionRecord r : CompanionWorldData.get(server).records()) {
			if (!r.dead()) {
				r.owner().ifPresent(owners::add);
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (owners.contains(player.getUUID()) && !player.isSpectator()) {
				boost((ServerLevel) player.level(), player);
			}
		}
	}

	/** Tenta criar um grupo perto da jogadora. @return quantos animais apareceram. */
	public static int boost(ServerLevel level, ServerPlayer player) {
		return boost(level, player, 24, 56);
	}

	/** Igual a {@link #boost(ServerLevel, ServerPlayer)}, com a faixa de distância escolhida (os testes usam uma faixa curta). */
	public static int boost(ServerLevel level, ServerPlayer player, int minDist, int maxDist) {
		// Respeita a regra do mundo "gerar criaturas" (desligada, nada aparece).
		if (!level.getGameRules().get(GameRules.SPAWN_MOBS)) {
			return 0;
		}
		return spawnGroup(level, player, minDist, maxDist);
	}

	/** Cria o grupo sem consultar a regra do mundo (usado pelos testes, cujos ambientes desligam o spawn natural). */
	public static int spawnGroup(ServerLevel level, ServerPlayer player, int minDist, int maxDist) {
		int min = ConfigManager.get().animaisMinimosPerto;
		if (countNear(level, player.blockPosition()) >= min) {
			return 0;
		}
		RandomSource random = level.getRandom();
		for (int attempt = 0; attempt < 12; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double dist = minDist + random.nextDouble() * (maxDist - minDist);
			int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
			int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);
			if (!level.hasChunkAt(new BlockPos(x, player.getBlockY(), z))) {
				continue;
			}
			BlockPos probe = new BlockPos(x, player.getBlockY(), z);
			List<EntityType<?>> options = new ArrayList<>();
			List<MobSpawnSettings.SpawnerData> data = new ArrayList<>();
			for (var w : level.getChunkSource().getGenerator().getMobsAt(level, level.structureManager(), MobCategory.CREATURE, probe).unwrap()) {
				if (MEAT.contains(w.value().type())) {
					for (int i = 0; i < Math.max(1, w.weight()); i++) {
						options.add(w.value().type());
						data.add(w.value());
					}
				}
			}
			if (options.isEmpty()) {
				continue;
			}
			int pick = random.nextInt(options.size());
			EntityType<?> type = options.get(pick);
			BlockPos ground = ground(level, probe);
			if (ground == null || !SpawnPlacements.isSpawnPositionOk(type, level, ground) || !SpawnPlacements.checkSpawnRules(type, level, EntitySpawnReason.NATURAL, ground,
				random)) {
				continue;
			}
			int group = Mth.clamp(data.get(pick).count().sample(random), 2, 4);
			int spawned = 0;
			for (int i = 0; i < group; i++) {
				BlockPos at = ground(level, ground.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2));
				if (at == null || !SpawnPlacements.isSpawnPositionOk(type, level, at)) {
					at = ground;
				}
				if (type.spawn(level, at, EntitySpawnReason.NATURAL) != null) {
					spawned++;
				}
			}
			if (spawned > 0) {
				return spawned;
			}
		}
		return 0;
	}

	/**
	 * Chão perto da altura dada: o primeiro espaço livre com bloco sólido embaixo, procurando de 10 blocos acima até 10
	 * abaixo (assim um teto ou copa de árvore não vira "chão").
	 */
	private static @Nullable BlockPos ground(ServerLevel level, BlockPos near) {
		for (int dy = 10; dy >= -10; dy--) {
			BlockPos p = near.above(dy);
			if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.below()).isSolidRender()) {
				return p;
			}
		}
		return null;
	}

	/** Animais de criação adultos e sem dono a até 64 blocos. */
	public static int countNear(ServerLevel level, BlockPos center) {
		return level.getEntitiesOfClass(Animal.class, new AABB(center).inflate(RADIUS, 32, RADIUS),
			a -> a.isAlive() && !a.isBaby() && MEAT.contains(a.getType()) && !(a instanceof OwnableEntity o && o.getOwnerReference() != null)).size();
	}
}
