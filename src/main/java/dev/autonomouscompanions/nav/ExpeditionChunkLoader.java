package dev.autonomouscompanions.nav;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/**
 * Carregamento dinâmico e temporário durante missões explícitas: mantém ativo apenas o pequeno entorno do chunk onde a
 * companheira está (como uma pérola do ender em voo), para que ela continue andando e trabalhando longe do dono.
 * O ticket expira sozinho em 3 s se não for renovado, não é salvo no disco e é removido ao terminar a missão — nunca
 * há carregamento permanente nem pré-carregamento de áreas para busca.
 */
public final class ExpeditionChunkLoader {
	public static TicketType TICKET;

	public static void register() {
		TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, AutonomousCompanions.id("expedition"),
			new TicketType(60L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
	}

	private final CompanionEntity companion;
	private @Nullable ChunkPos current;
	private @Nullable ServerLevel currentLevel;
	private int currentRadius;
	private int keepAlive;
	private int refresh;

	public ExpeditionChunkLoader(CompanionEntity companion) {
		this.companion = companion;
	}

	/** Chamado a cada tick pelas etapas de missão que precisam continuar longe do dono. */
	public void keepActive() {
		keepAlive = 40;
	}

	public boolean isActive() {
		return current != null;
	}

	public void tick(ServerLevel level) {
		if (!ConfigManager.get().carregamentoDinamicoEmExpedicoes || keepAlive <= 0) {
			release();
			return;
		}
		keepAlive--;
		ChunkPos cp = companion.chunkPosition();
		int radius = ConfigManager.get().raioCarregamentoExpedicaoChunks;
		if (current == null || !cp.equals(current) || currentLevel != level || radius != currentRadius || ++refresh >= 20) {
			refresh = 0;
			level.getChunkSource().addTicketWithRadius(TICKET, cp, radius);
			if (current != null && (!cp.equals(current) || currentLevel != level || radius != currentRadius)) {
				release();
			}
			current = cp;
			currentLevel = level;
			currentRadius = radius;
		}
	}

	public void release() {
		if (current != null && currentLevel != null) {
			currentLevel.getChunkSource().removeTicketWithRadius(TICKET, current, currentRadius);
		}
		current = null;
		currentLevel = null;
	}
}
