package dev.autonomouscompanions.ai;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionVariant;
import dev.autonomouscompanions.nav.ExpeditionChunkLoader;
import dev.autonomouscompanions.persist.CompanionWorldData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * Mantém a ajudante perto da dona. Ela trabalha sozinha (caça, corta, minera) e, sem esta guarda, podia ir parar fora
 * da área que o jogo mantém ativa ao redor da jogadora — e ali ela simplesmente congela ("some").
 * <ul>
 *   <li>Carregada e a mais de {@link #MAX_DISTANCE} blocos da dona: é trazida para um lugar seguro ao lado dela.</li>
 *   <li>Descarregada (ficou para trás numa área inativa): a guarda carrega por alguns segundos o chunk da última
 *       posição conhecida, espera ela aparecer e a traz para perto da dona.</li>
 * </ul>
 * Só na mesma dimensão da dona; nunca durante o sono.
 */
public final class HelperGuard {
	/** Distância horizontal máxima da dona antes de ser trazida de volta. */
	public static final int MAX_DISTANCE = 96;
	/** Quantas vezes (de 1 s) espera a ajudante aparecer depois de carregar o chunk. */
	private static final int LOAD_ATTEMPTS = 12;

	private static final Map<UUID, Integer> loading = new HashMap<>();

	private HelperGuard() {
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 7) {
			return;
		}
		if (server.getTickCount() % 200 != 7 && loading.isEmpty()) {
			return;
		}
		CompanionWorldData data = CompanionWorldData.get(server);
		for (CompanionWorldData.CompanionRecord r : data.records()) {
			if (r.dead() || r.variant() != CompanionVariant.AJUDANTE.ordinal() || r.owner().isEmpty()) {
				continue;
			}
			ServerPlayer owner = server.getPlayerList().getPlayer(r.owner().get());
			if (owner == null || owner.isSpectator() || owner.level().dimension() != r.dimension()) {
				loading.remove(r.id());
				continue;
			}
			ServerLevel level = (ServerLevel) owner.level();
			Entity e = level.getEntity(r.id());
			if (e instanceof CompanionEntity helper && helper.isAlive()) {
				Integer pending = loading.remove(r.id());
				// Como segurança, ela pode estar junto de uma companheira longe da dona: aí não é trazida de volta.
				boolean nearWard = helper.guardsAllies() && helper.ownerUUID() != null && !level.getEntitiesOfClass(CompanionEntity.class,
					helper.getBoundingBox().inflate(MAX_DISTANCE, 64, MAX_DISTANCE), a -> a != helper && a.isAlive() && !a.isHelper()
						&& helper.ownerUUID().equals(a.ownerUUID())).isEmpty();
				if (pending != null || !nearWard && horizontal(helper.blockPosition(), owner.blockPosition()) > MAX_DISTANCE) {
					if (!helper.isSleeping() && helper.brain().bringNearOwner(owner)) {
						helper.debug("Ajudante longe demais da dona: trazida de volta.");
					}
				}
				continue;
			}
			if (horizontal(r.lastPos(), owner.blockPosition()) <= MAX_DISTANCE && !loading.containsKey(r.id())) {
				continue; // perto: está só fora da lista por um instante (ex.: trocando de chunk)
			}
			int tries = loading.getOrDefault(r.id(), 0);
			if (tries >= LOAD_ATTEMPTS) {
				loading.remove(r.id()); // tenta de novo na próxima rodada (10 s)
				continue;
			}
			loading.put(r.id(), tries + 1);
			// Ticket temporário (expira sozinho em 3 s) só para a entidade voltar a existir e poder ser trazida.
			level.getChunkSource().addTicketWithRadius(ExpeditionChunkLoader.TICKET, new ChunkPos(r.lastPos().getX() >> 4, r.lastPos().getZ() >> 4), 1);
		}
	}

	private static double horizontal(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	public static void reset() {
		loading.clear();
	}
}
