package dev.autonomouscompanions;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Inicializa os registros do Minecraft uma única vez para os testes unitários. */
public final class McBootstrap {
	private static boolean done;

	private McBootstrap() {
	}

	public static synchronized void init() {
		if (!done) {
			SharedConstants.tryDetectVersion();
			Bootstrap.bootStrap();
			done = true;
		}
	}
}
