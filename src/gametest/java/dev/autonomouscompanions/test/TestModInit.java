package dev.autonomouscompanions.test;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.config.ConfigManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * No servidor GameTest cada teste cria seus próprios jogadores simulados; a criação automática das três companheiras
 * iniciais é desligada (só em memória) para não interferir nos outros testes. O teste de criação inicial usa o mesmo
 * fluxo agendado de {@code StarterSpawner.queue}. No teste de cliente o modo padrão continua valendo.
 */
public final class TestModInit implements ModInitializer {
	@Override
	public void onInitialize() {
		if (System.getProperty("fabric-api.gametest") != null) {
			// Aplicado ao iniciar o servidor, depois que o mod principal já carregou o arquivo de configuração.
			ServerLifecycleEvents.SERVER_STARTING.register(server -> {
				ConfigManager.get().modoCompanheirasIniciais = "DESATIVADO";
				// O reforço de animais criaria bichos nas arenas de outros testes (caça, combate); o teste dele chama boost() direto.
				ConfigManager.get().animaisPorPerto = false;
				AutonomousCompanions.LOGGER.info("[testes] criação automática das companheiras iniciais desativada no servidor GameTest");
			});
		}
	}
}
