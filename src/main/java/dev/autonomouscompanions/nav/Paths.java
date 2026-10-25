package dev.autonomouscompanions.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;
import org.jspecify.annotations.Nullable;

/**
 * Verificação de caminho confiável. O {@code Path.canReach()} do jogo considera "alcançado" um fim de caminho ainda
 * longe do alvo na vertical (ex.: 9 blocos acima de um minério enterrado), então conferimos a distância real entre o
 * último nó e o alvo.
 */
public final class Paths {
	private Paths() {
	}

	/** O caminho termina a no máximo {@code maxDistance} blocos (3D) do alvo? */
	public static boolean reaches(@Nullable Path path, BlockPos target, double maxDistance) {
		if (path == null || !path.canReach() || path.getEndNode() == null) {
			return false;
		}
		BlockPos end = path.getEndNode().asBlockPos();
		double dx = end.getX() - target.getX();
		double dy = end.getY() - target.getY();
		double dz = end.getZ() - target.getZ();
		return dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance;
	}
}
