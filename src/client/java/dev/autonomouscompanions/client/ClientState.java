package dev.autonomouscompanions.client;

import dev.autonomouscompanions.network.CompanionSnapshot;
import dev.autonomouscompanions.network.Payloads;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Cache do lado do cliente com os últimos retratos recebidos do servidor (somente exibição). */
public final class ClientState {
	public static final Map<Integer, CompanionSnapshot> SNAPSHOTS = new HashMap<>();
	public static boolean marking;
	public static int markingEntity;
	public static Payloads.@Nullable Analysis lastAnalysis;

	private ClientState() {
	}

	public static @Nullable CompanionSnapshot get(int entityId) {
		return SNAPSHOTS.get(entityId);
	}

	public static void clear() {
		SNAPSHOTS.clear();
		marking = false;
		lastAnalysis = null;
	}
}
