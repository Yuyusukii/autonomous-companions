package dev.autonomouscompanions.ai.behavior;

import dev.autonomouscompanions.ai.Behavior;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.ai.Priorities;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.Navigator;
import java.util.Comparator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Posto da ajudante como segurança das outras (pedido da jogadora em 18/10/2026): sem ameaça por perto, ela fica junto da
 * companheira mais próxima da mesma dona (a até 128 blocos), pronta para defendê-la. A luta em si é do
 * {@link DefendBehavior}: o rastreador de ameaças dela aponta os monstros perto das companheiras. Sem nenhuma companheira
 * por perto, ela segue a dona normalmente.
 */
public final class GuardPostBehavior extends Behavior {
	private static final double WARD_RANGE = 128;
	private @Nullable CompanionEntity ward;
	private long nextPick;

	public GuardPostBehavior(CompanionEntity companion, CompanionBrain brain) {
		super(companion, brain);
	}

	@Override
	public int priority(ServerLevel level) {
		if (!companion.guardsAllies() || !companion.isNamed()) {
			return 0;
		}
		long now = level.getGameTime();
		if (ward == null || !ward.isAlive() || ward.isRemoved() || ward.distanceTo(companion) > WARD_RANGE || now >= nextPick) {
			nextPick = now + 100;
			ward = pickWard(level);
		}
		return ward == null ? 0 : Priorities.GUARD_POST;
	}

	/** Mantém a mesma protegida enquanto ela estiver por perto; senão, a companheira mais próxima. */
	private @Nullable CompanionEntity pickWard(ServerLevel level) {
		if (ward != null && ward.isAlive() && !ward.isRemoved() && ward.level() == level && ward.distanceTo(companion) <= WARD_RANGE) {
			return ward;
		}
		if (companion.ownerUUID() == null) {
			return null;
		}
		return level.getEntitiesOfClass(CompanionEntity.class, companion.getBoundingBox().inflate(WARD_RANGE, 48, WARD_RANGE),
			a -> a != companion && a.isAlive() && !a.isHelper() && companion.ownerUUID().equals(a.ownerUUID()))
			.stream().min(Comparator.comparingDouble(a -> a.distanceToSqr(companion))).orElse(null);
	}

	@Override
	public boolean tick(ServerLevel level) {
		if (ward == null || !ward.isAlive()) {
			return false;
		}
		companion.setCompanionState(CompanionState.FOLLOWING, Component.translatable("detail.autonomouscompanions.guarding", ward.getDisplayName()));
		Navigator nav = brain.navigator();
		double d = companion.distanceTo(ward);
		if (d > 6) {
			nav.setGoal(ward.blockPosition(), 3.0, d > 16 ? 1.4 : 1.1);
			if (nav.tick() == Navigator.Status.FAILED) {
				nav.clear();
			}
		} else {
			nav.clear();
			if (companion.tickCount % 20 == 0) {
				companion.getLookControl().setLookAt(ward, 30, 30);
			}
		}
		return true;
	}

	@Override
	public void stop(ServerLevel level) {
		brain.navigator().clear();
	}

	/** Teste/depuração: quem ela está protegendo agora. */
	public @Nullable CompanionEntity ward() {
		return ward;
	}
}
