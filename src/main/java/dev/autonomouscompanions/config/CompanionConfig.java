package dev.autonomouscompanions.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuração global do mod. É serializada em {@code config/autonomouscompanions.json}.
 * Todos os campos são públicos para a serialização via Gson; use {@link #validate()} após carregar.
 */
public final class CompanionConfig {
	// --- Acompanhamento e exploração ociosa ---
	public int limiteExploracaoOciosa = 100;
	public int distanciaMinimaAcompanhamento = 3;
	public int distanciaMaximaAcompanhamento = 7;
	/** Distância (blocos) a partir da qual uma companheira presa e sem rota pode ser recuperada perto do dono. */
	public int distanciaRecuperacaoEmergencia = 48;
	/** Segundos sem conseguir se aproximar do dono antes de acionar a recuperação controlada. */
	public int segundosAntesDaRecuperacao = 30;

	// --- Busca de recursos ---
	public int alcanceBuscaComum = 128;
	public int alcanceBuscaModerado = 512;
	public int alcanceBuscaRaro = 2048;
	public int alcanceBuscaExpedicao = 4096;
	public int alcanceBuscaMaximoAbsoluto = 16384;
	/** Intervalo (ticks) entre ciclos do escaneador de recursos de cada companheira. */
	public int intervaloBuscaTicks = 5;
	/** Orçamento global de seções de chunk (16x16x16) analisadas por tick, somando todas as companheiras. */
	public int secoesAnalisadasPorTick = 24;
	public boolean escanearBlocosEnterrados = true;
	/** Carrega temporariamente apenas os chunks ao redor da companheira em expedições explícitas, liberando-os ao terminar. */
	public boolean carregamentoDinamicoEmExpedicoes = true;
	public int raioCarregamentoExpedicaoChunks = 2;
	/** Repõe animais de criação (do próprio bioma) perto de quem tem companheiras quando há poucos por perto. */
	public boolean animaisPorPerto = true;
	public int animaisMinimosPerto = 8;

	// --- IA e processamento ---
	public int maxProfundidadePlanejamento = 16;
	public int maxEtapasPlanejamento = 96;
	public int maxNosCaminho = 1200;
	public int segundosSemProgressoParaFalha = 240;
	public int intervaloDecisaoTicks = 10;
	public int intervaloAmeacasTicks = 10;

	// --- Saúde e sobrevivência ---
	public double vidaMaxima = 20.0;
	public double danoBase = 1.0;
	public double velocidadeMovimento = 0.30;
	public float multiplicadorFome = 1.0f;
	public boolean sonoHabilitado = true;
	public float multiplicadorCansaco = 1.0f;
	/** RESSURGIR_PERTO_DO_DONO, MANUAL ou PERMANENTE. */
	public String comportamentoMorte = "RESSURGIR_PERTO_DO_DONO";
	public int segundosParaRessurgir = 180;
	public boolean manterInventarioAoMorrer = true;
	/** Vida infinita: dano comum não as fere (o vazio as traz de volta para perto da dona; /kill continua valendo). */
	public boolean vidaInfinita = true;
	/** Sem fome: a fome fica sempre cheia. */
	public boolean semFome = true;
	/** Sem cansaço: a disposição nunca cai. */
	public boolean semCansaco = true;
	/** A companheira de cabelo vermelho entra no Modo Demônio toda noite. */
	public boolean modoDemonioTodaNoite = true;
	/** Raio horizontal (blocos) da caçada noturna do Modo Demônio. */
	public int raioModoDemonioNoturno = 1000;
	/** A ajudante faz a segurança das outras (ataca monstros perto delas) em vez de abastecê-las. */
	public boolean ajudanteSeguranca = true;
	/** Distância (blocos) de uma companheira em que a ajudante trata um monstro como ameaça a ela. */
	public int raioSegurancaAjudante = 16;

	// --- Combate ---
	public boolean defenderContraJogadores = true;
	public float vidaParaFugirPercentual = 0.30f;

	// --- Construção ---
	public boolean construcaoHabilitada = true;
	public boolean permitirPrepararTerreno = true;
	public double alcanceConstrucao = 5.0;

	// --- Funções e cooperação ---
	public boolean trabalhoAutonomoDasFuncoes = true;
	public boolean cooperacaoHabilitada = true;

	// --- Entrega ---
	public boolean soltarItensSeInventarioDoDonoCheio = false;

	// --- Surgimento ---
	/** PRIMEIRO_JOGADOR: só o primeiro jogador do mundo recebe as três; CADA_JOGADOR: cada novo jogador recebe três; DESATIVADO: ninguém recebe automaticamente (use /companheiras criar_iniciais). */
	public String modoCompanheirasIniciais = "PRIMEIRO_JOGADOR";

	/** Gera o catálogo da versão carregada em <mundo>/autonomouscompanions/catalogo (só quando a versão, os mods ou os dados mudam). */
	public boolean exportarCatalogo = true;

	public boolean depuracao = false;

	public Map<String, String> _comentarios = defaultComments();

	public void validate() {
		limiteExploracaoOciosa = clamp(limiteExploracaoOciosa, 8, 512);
		distanciaMinimaAcompanhamento = clamp(distanciaMinimaAcompanhamento, 1, 16);
		distanciaMaximaAcompanhamento = clamp(distanciaMaximaAcompanhamento, distanciaMinimaAcompanhamento + 1, 32);
		distanciaRecuperacaoEmergencia = clamp(distanciaRecuperacaoEmergencia, 16, 512);
		segundosAntesDaRecuperacao = clamp(segundosAntesDaRecuperacao, 5, 600);
		alcanceBuscaMaximoAbsoluto = clamp(alcanceBuscaMaximoAbsoluto, 64, 60000);
		alcanceBuscaComum = clamp(alcanceBuscaComum, 16, alcanceBuscaMaximoAbsoluto);
		alcanceBuscaModerado = clamp(alcanceBuscaModerado, alcanceBuscaComum, alcanceBuscaMaximoAbsoluto);
		alcanceBuscaRaro = clamp(alcanceBuscaRaro, alcanceBuscaModerado, alcanceBuscaMaximoAbsoluto);
		alcanceBuscaExpedicao = clamp(alcanceBuscaExpedicao, alcanceBuscaRaro, alcanceBuscaMaximoAbsoluto);
		intervaloBuscaTicks = clamp(intervaloBuscaTicks, 1, 100);
		animaisMinimosPerto = clamp(animaisMinimosPerto, 0, 32);
		secoesAnalisadasPorTick = clamp(secoesAnalisadasPorTick, 1, 512);
		raioCarregamentoExpedicaoChunks = clamp(raioCarregamentoExpedicaoChunks, 0, 2);
		maxProfundidadePlanejamento = clamp(maxProfundidadePlanejamento, 3, 24);
		maxEtapasPlanejamento = clamp(maxEtapasPlanejamento, 8, 512);
		maxNosCaminho = clamp(maxNosCaminho, 200, 4000);
		segundosSemProgressoParaFalha = clamp(segundosSemProgressoParaFalha, 30, 3600);
		intervaloDecisaoTicks = clamp(intervaloDecisaoTicks, 1, 40);
		intervaloAmeacasTicks = clamp(intervaloAmeacasTicks, 2, 40);
		vidaMaxima = Math.max(1.0, Math.min(1024.0, vidaMaxima));
		danoBase = Math.max(0.0, Math.min(64.0, danoBase));
		velocidadeMovimento = Math.max(0.1, Math.min(0.6, velocidadeMovimento));
		multiplicadorFome = Math.max(0.0f, Math.min(10.0f, multiplicadorFome));
		multiplicadorCansaco = Math.max(0.0f, Math.min(10.0f, multiplicadorCansaco));
		segundosParaRessurgir = clamp(segundosParaRessurgir, 0, 86400);
		raioModoDemonioNoturno = clamp(raioModoDemonioNoturno, 16, 2000);
		raioSegurancaAjudante = clamp(raioSegurancaAjudante, 4, 48);
		vidaParaFugirPercentual = Math.max(0.05f, Math.min(0.9f, vidaParaFugirPercentual));
		alcanceConstrucao = Math.max(3.0, Math.min(6.0, alcanceConstrucao));
		if (!comportamentoMorte.equals("RESSURGIR_PERTO_DO_DONO") && !comportamentoMorte.equals("MANUAL") && !comportamentoMorte.equals("PERMANENTE")) {
			comportamentoMorte = "RESSURGIR_PERTO_DO_DONO";
		}
		if (!modoCompanheirasIniciais.equals("PRIMEIRO_JOGADOR") && !modoCompanheirasIniciais.equals("CADA_JOGADOR") && !modoCompanheirasIniciais.equals("DESATIVADO")) {
			modoCompanheirasIniciais = "PRIMEIRO_JOGADOR";
		}
		_comentarios = defaultComments();
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	public static Map<String, String> defaultComments() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("limiteExploracaoOciosa", "Distância horizontal máxima (blocos) que uma companheira sem ordens se afasta do dono por vontade própria. Padrão 100.");
		m.put("distanciaMinimaAcompanhamento", "Distância confortável mínima até o dono ao seguir.");
		m.put("distanciaMaximaAcompanhamento", "A partir desta distância ela volta a caminhar em direção ao dono.");
		m.put("distanciaRecuperacaoEmergencia", "Somente se estiver presa, sem rota e mais longe que isso por 'segundosAntesDaRecuperacao', ela é reposicionada com segurança perto do dono (nunca durante missões).");
		m.put("alcanceBusca*", "Perfis de exploração: comum, moderado, raro e expedição (blocos horizontais). Valores iniciais, não garantias de disponibilidade.");
		m.put("intervaloBuscaTicks/secoesAnalisadasPorTick", "Limites de processamento do escaneador incremental (evita travamentos).");
		m.put("escanearBlocosEnterrados", "Se falso, ela só considera blocos expostos ao ar e precisa escavar galerias para encontrar recursos subterrâneos.");
		m.put("carregamentoDinamicoEmExpedicoes", "Mantém carregados temporariamente somente os chunks ao redor da companheira durante missões explícitas longe do dono. Liberado ao final. Se falso, ela pausa quando o dono se afasta além da distância de simulação.");
		m.put("animaisPorPerto/animaisMinimosPerto", "Se houver menos animais de criação que o mínimo a até 64 blocos de quem tem companheiras, a cada 30 s aparece um pequeno grupo (2 a 4) de um animal do próprio bioma, a 24-56 blocos, seguindo as regras normais de spawn do jogo. Padrão: ligado, mínimo 8.");
		m.put("vidaMaxima", "Pontos de vida (20 = 10 corações). Requer recarregar o mundo para aplicar às existentes.");
		m.put("multiplicadorFome/multiplicadorCansaco", "Escala o consumo de fome e o acúmulo de cansaço.");
		m.put("comportamentoMorte", "RESSURGIR_PERTO_DO_DONO: ressurge após 'segundosParaRessurgir' perto do dono. MANUAL: só com /companheiras reviver. PERMANENTE: morte definitiva (dados ficam registrados).");
		m.put("manterInventarioAoMorrer", "Se verdadeiro, o inventário fica guardado no registro da morte e volta com ela; se falso, é largado no local da morte.");
		m.put("exportarCatalogo", "Gera, em segundo plano, o catálogo da versão carregada (blocos, itens, receitas, drops, criaturas, encantamentos, poções, biomas e estruturas, inclusive de outros mods) em <mundo>/autonomouscompanions/catalogo. Só regrava quando a versão, os mods ou os dados mudam. Também: /companheiras catalogo exportar.");
		m.put("vidaInfinita/semFome/semCansaco", "Regras de sobrevivência das companheiras e da ajudante. Padrão: vida infinita (dano comum não as fere; ao cair no vazio voltam para perto da dona; /kill continua valendo), sem fome e sem cansaço.");
		m.put("modoDemonioTodaNoite/raioModoDemonioNoturno", "A companheira de cabelo vermelho entra no Modo Demônio toda noite e caça os monstros hostis (nunca animais, aldeões, animais domesticados, jogadores ou aliadas) num raio horizontal de raioModoDemonioNoturno blocos até amanhecer, sem dormir depois. Só onde há noite (Superfície). Se falso, o modo volta a despertar só no golpe fatal em combate (quando a vida infinita estiver desligada).");
		m.put("ajudanteSeguranca/raioSegurancaAjudante", "A ajudante passa a ser a segurança das outras: ataca todo monstro hostil a até raioSegurancaAjudante blocos de uma companheira da mesma dona (ou que esteja mirando uma delas) e, sem perigo, fica perto delas. Se falso, ela volta a abastecê-las com comida e itens de pedra.");
		m.put("construcaoHabilitada", "Habilita o sistema de construção de casas.");
		m.put("permitirPrepararTerreno", "Permite (quando autorizado na tela) remover blocos naturais e nivelar o terreno do projeto.");
		m.put("trabalhoAutonomoDasFuncoes", "Permite que as funções (lenhadora, agricultora etc.) trabalhem por conta própria quando não há ordens.");
		m.put("modoCompanheirasIniciais", "PRIMEIRO_JOGADOR, CADA_JOGADOR ou DESATIVADO (sem criação automática).");
		return m;
	}
}
