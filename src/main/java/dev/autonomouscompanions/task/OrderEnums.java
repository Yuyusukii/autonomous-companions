package dev.autonomouscompanions.task;

import com.mojang.serialization.Codec;

/** Enumerações do sistema de ordens, agrupadas para manter o pacote enxuto. */
public final class OrderEnums {
	private OrderEnums() {
	}

	public enum OrderType {
		/** Obter um item/bloco em quantidade exata e entregar ao dono. */
		GATHER,
		/** Construir um projeto arquitetônico aprovado. */
		BUILD,
		/** Preparar armas e armaduras proporcionais ao risco. */
		EQUIP,
		/** Fabricar um item qualquer pelo planejador de receitas e entregar. */
		CRAFT,
		/** Voltar até o dono e entregar o que for da missão. */
		RETURN;

		public static final Codec<OrderType> CODEC = Codec.STRING.xmap(OrderType::valueOf, OrderType::name);

		public String translationKey() {
			return "order.autonomouscompanions.type." + name().toLowerCase();
		}
	}

	public enum OrderStatus {
		PENDING,
		ACTIVE,
		RETURNING,
		DELIVERING,
		WAITING_SPACE,
		COMPLETED,
		FAILED,
		SUSPENDED,
		CANCELLED;

		public static final Codec<OrderStatus> CODEC = Codec.STRING.xmap(OrderStatus::valueOf, OrderStatus::name);

		public boolean isFinished() {
			return this == COMPLETED || this == FAILED || this == CANCELLED;
		}

		public boolean isRunnable() {
			return this == PENDING || this == ACTIVE || this == RETURNING || this == DELIVERING || this == WAITING_SPACE;
		}

		public String translationKey() {
			return "order.autonomouscompanions.status." + name().toLowerCase();
		}
	}

	/** Forma de saída de um pedido feito pelo catálogo de blocos. */
	public enum GatherMode {
		/** O bloco exato como item (pode exigir Toque Suave, fundição ou receita). */
		EXACT_BLOCK,
		/** O material que o bloco produz ao ser minerado (ex.: minério de diamante → diamante). */
		DROPS,
		/** Um item específico (carne, ferramentas, materiais). */
		ITEM;

		public static final Codec<GatherMode> CODEC = Codec.STRING.xmap(GatherMode::valueOf, GatherMode::name);

		public String translationKey() {
			return "order.autonomouscompanions.mode." + name().toLowerCase();
		}
	}

	/** Perfis de exploração; o alcance real vem da configuração. */
	public enum SearchProfile {
		AUTO,
		COMMON,
		MODERATE,
		RARE,
		EXPEDITION;

		public static final Codec<SearchProfile> CODEC = Codec.STRING.xmap(SearchProfile::valueOf, SearchProfile::name);

		public String translationKey() {
			return "order.autonomouscompanions.profile." + name().toLowerCase();
		}

		public static SearchProfile byIndex(int i) {
			SearchProfile[] v = values();
			return v[Math.floorMod(i, v.length)];
		}
	}
}
