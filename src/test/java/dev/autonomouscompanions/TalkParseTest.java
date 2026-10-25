package dev.autonomouscompanions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.autonomouscompanions.mind.talk.Talk;
import dev.autonomouscompanions.mind.talk.Talk.Kind;
import org.junit.jupiter.api.Test;

/** Interpretação das frases da conversa (sem o jogo): tipo do pedido, assunto e quantidade, em português do dia a dia. */
class TalkParseTest {
	private static void check(String text, Kind kind, String subject, int quantity) {
		Talk.Intent i = Talk.parse(text);
		assertEquals(kind, i.kind(), "tipo de \"" + text + "\"");
		if (subject != null) {
			assertEquals(subject, i.subject(), "assunto de \"" + text + "\"");
		}
		assertEquals(quantity, i.quantity(), "quantidade de \"" + text + "\"");
	}

	@Test
	void ordersWithQuantities() {
		check("traga 10 barras de ferro", Kind.OBTAIN, "barras de ferro", 10);
		check("Traga dez carvões!", Kind.OBTAIN, "carvoes", 10);
		check("me dê uma pilha de pedregulho", Kind.OBTAIN, "pedregulho", 64);
		check("pegue meia pilha de tábuas", Kind.OBTAIN, "tabuas", 32);
		check("busca carne assada", Kind.OBTAIN, "carne assada", 0);
		check("faça uma fornalha", Kind.CRAFT, "fornalha", 1);
		check("fabrique 3 baús", Kind.CRAFT, "baus", 3);
		check("minere ferro", Kind.SKILL, null, 0);
		check("corte 8 árvores", Kind.SKILL, null, 8);
	}

	@Test
	void questionsAndStates() {
		check("o que você está fazendo?", Kind.STATUS, "", 0);
		check("O que você vê?", Kind.PERCEPTION, "", 0);
		check("olhe em volta", Kind.PERCEPTION, "", 0);
		check("o que você lembra?", Kind.MEMORY, "", 0);
		check("onde tem diamante?", Kind.WHERE, "diamante", 0);
		check("onde acho um minério de ferro", Kind.WHERE, "minerio de ferro", 0);
		check("como faço uma picareta de pedra?", Kind.HOW, null, 0);
		check("o que é obsidiana?", Kind.HOW, null, 0);
	}

	@Test
	void commands() {
		check("aqui é a base", Kind.BASE, "", 0);
		check("Aqui vai ser a nossa base!", Kind.BASE, "", 0);
		check("pare", Kind.STOP, "", 0);
		check("cancela isso", Kind.STOP, "", 0);
		check("venha aqui", Kind.FOLLOW, "", 0);
		check("me siga", Kind.FOLLOW, "", 0);
		check("descanse um pouco", Kind.REST, "", 0);
		check("ajuda", Kind.HELP, "", 0);
		check("blablabla", Kind.UNKNOWN, null, 0);
	}
}
