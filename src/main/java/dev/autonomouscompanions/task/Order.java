package dev.autonomouscompanions.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.OrderType;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Ordem persistente. Contém um identificador único de missão, a quantidade pedida e a quantidade efetivamente
 * entregue. A entrega nunca ultrapassa {@code requested - delivered}, o que torna repetições da entrega idempotentes.
 * As etapas de execução (planos, caminhos) não são salvas: ao recarregar, a ordem é replanejada a partir do inventário real.
 */
public final class Order {
	public static final Codec<Progress> PROGRESS_CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.optionalFieldOf("obtained", 0).forGetter(p -> p.obtained),
		Codec.INT.optionalFieldOf("delivered", 0).forGetter(p -> p.delivered),
		Codec.DOUBLE.optionalFieldOf("distance", 0.0).forGetter(p -> p.distance),
		Codec.INT.optionalFieldOf("blocksMined", 0).forGetter(p -> p.blocksMined),
		Codec.INT.optionalFieldOf("itemsCrafted", 0).forGetter(p -> p.itemsCrafted),
		Codec.INT.optionalFieldOf("blocksPlaced", 0).forGetter(p -> p.blocksPlaced),
		Codec.INT.optionalFieldOf("deliveryCount", 0).forGetter(p -> p.deliveryCount)
	).apply(i, Progress::new));

	public static final Codec<Order> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.fieldOf("id").forGetter(o -> o.id),
		OrderType.CODEC.fieldOf("type").forGetter(o -> o.type),
		OrderStatus.CODEC.fieldOf("status").forGetter(o -> o.status),
		Codec.STRING.optionalFieldOf("target", "minecraft:air").forGetter(o -> o.target),
		Codec.STRING.optionalFieldOf("sourceBlock", "").forGetter(o -> o.sourceBlock),
		GatherMode.CODEC.optionalFieldOf("mode", GatherMode.ITEM).forGetter(o -> o.mode),
		Codec.INT.optionalFieldOf("requested", 0).forGetter(o -> o.requested),
		SearchProfile.CODEC.optionalFieldOf("profile", SearchProfile.AUTO).forGetter(o -> o.profile),
		Codec.INT.optionalFieldOf("maxDistance", 0).forGetter(o -> o.maxDistance),
		Codec.BOOL.optionalFieldOf("urgent", false).forGetter(o -> o.urgent),
		UUIDUtil.CODEC.optionalFieldOf("project").forGetter(o -> Optional.ofNullable(o.projectId)),
		ComponentSerialization.CODEC.optionalFieldOf("reason").forGetter(o -> Optional.ofNullable(o.reason)),
		Codec.LONG.optionalFieldOf("created", 0L).forGetter(o -> o.createdTime),
		PROGRESS_CODEC.optionalFieldOf("progress", new Progress()).forGetter(o -> o.progress),
		Codec.STRING.optionalFieldOf("skill", "").forGetter(o -> o.skill)
	).apply(i, Order::new));

	/** Contadores de progresso (persistentes). */
	public static final class Progress {
		public int obtained;
		public int delivered;
		public double distance;
		public int blocksMined;
		public int itemsCrafted;
		public int blocksPlaced;
		public int deliveryCount;

		public Progress() {
		}

		Progress(int obtained, int delivered, double distance, int blocksMined, int itemsCrafted, int blocksPlaced, int deliveryCount) {
			this.obtained = obtained;
			this.delivered = delivered;
			this.distance = distance;
			this.blocksMined = blocksMined;
			this.itemsCrafted = itemsCrafted;
			this.blocksPlaced = blocksPlaced;
			this.deliveryCount = deliveryCount;
		}
	}

	private final UUID id;
	private final OrderType type;
	private OrderStatus status;
	private final String target;
	private final String sourceBlock;
	private final GatherMode mode;
	private final int requested;
	private final SearchProfile profile;
	private final int maxDistance;
	private boolean urgent;
	private final @Nullable UUID projectId;
	private @Nullable Component reason;
	private final long createdTime;
	private final Progress progress;
	/** Habilidade a executar (id da biblioteca de habilidades); vazio nos pedidos comuns. */
	private final String skill;

	private Order(UUID id, OrderType type, OrderStatus status, String target, String sourceBlock, GatherMode mode, int requested, SearchProfile profile,
		int maxDistance, boolean urgent, Optional<UUID> projectId, Optional<Component> reason, long createdTime, Progress progress) {
		this(id, type, status, target, sourceBlock, mode, requested, profile, maxDistance, urgent, projectId, reason, createdTime, progress, "");
	}

	private Order(UUID id, OrderType type, OrderStatus status, String target, String sourceBlock, GatherMode mode, int requested, SearchProfile profile,
		int maxDistance, boolean urgent, Optional<UUID> projectId, Optional<Component> reason, long createdTime, Progress progress, String skill) {
		this.id = id;
		this.type = type;
		this.status = status;
		this.target = target;
		this.sourceBlock = sourceBlock;
		this.mode = mode;
		this.requested = requested;
		this.profile = profile;
		this.maxDistance = maxDistance;
		this.urgent = urgent;
		this.projectId = projectId.orElse(null);
		this.reason = reason.orElse(null);
		this.createdTime = createdTime;
		this.progress = progress;
		this.skill = skill;
	}

	/** Pedido de habilidade: executa o procedimento da habilidade e entrega o item da condição de sucesso. */
	public static Order skill(String skillId, String successTarget, int count, long time) {
		return new Order(UUID.randomUUID(), OrderType.GATHER, OrderStatus.PENDING, successTarget, "", GatherMode.ITEM,
			count, SearchProfile.AUTO, 0, false, Optional.empty(), Optional.empty(), time, new Progress(), skillId);
	}

	public @Nullable String skill() {
		return skill.isEmpty() ? null : skill;
	}

	public static Order gather(Item item, String sourceBlock, GatherMode mode, int count, SearchProfile profile, int maxDistance, long time) {
		return new Order(UUID.randomUUID(), OrderType.GATHER, OrderStatus.PENDING, BuiltInRegistries.ITEM.getKey(item).toString(), sourceBlock, mode,
			count, profile, maxDistance, false, Optional.empty(), Optional.empty(), time, new Progress());
	}

	public static Order craft(Item item, int count, long time) {
		return new Order(UUID.randomUUID(), OrderType.CRAFT, OrderStatus.PENDING, BuiltInRegistries.ITEM.getKey(item).toString(), "", GatherMode.ITEM,
			count, SearchProfile.AUTO, 0, false, Optional.empty(), Optional.empty(), time, new Progress());
	}

	public static Order build(UUID projectId, long time) {
		return new Order(UUID.randomUUID(), OrderType.BUILD, OrderStatus.PENDING, "minecraft:air", "", GatherMode.ITEM, 0, SearchProfile.AUTO, 0,
			false, Optional.of(projectId), Optional.empty(), time, new Progress());
	}

	public static Order equip(long time) {
		return new Order(UUID.randomUUID(), OrderType.EQUIP, OrderStatus.PENDING, "minecraft:air", "", GatherMode.ITEM, 0, SearchProfile.AUTO, 0,
			false, Optional.empty(), Optional.empty(), time, new Progress());
	}

	public static Order returnToOwner(long time) {
		return new Order(UUID.randomUUID(), OrderType.RETURN, OrderStatus.PENDING, "minecraft:air", "", GatherMode.ITEM, 0, SearchProfile.AUTO, 0,
			true, Optional.empty(), Optional.empty(), time, new Progress());
	}

	public UUID id() {
		return id;
	}

	public OrderType type() {
		return type;
	}

	public OrderStatus status() {
		return status;
	}

	public void setStatus(OrderStatus status) {
		this.status = status;
	}

	public String targetId() {
		return target;
	}

	/** Pedido de grupo "qualquer carne" (crua ou assada; frango cru fica de fora por dar fome). */
	public static final String ANY_MEAT = "autonomouscompanions:any_meat";
	/** Pedido de grupo "qualquer carne assada". */
	public static final String ANY_COOKED_MEAT = "autonomouscompanions:any_cooked_meat";
	public static final java.util.Set<Item> COOKED_MEATS = java.util.Set.of(Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON,
		Items.COOKED_CHICKEN, Items.COOKED_RABBIT);
	public static final java.util.Set<Item> ANY_MEATS;
	/** Carnes cruas que viram as carnes assadas do pedido de grupo. */
	public static final java.util.Set<Item> RAW_MEATS_TO_COOK = java.util.Set.of(Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT);

	static {
		java.util.Set<Item> s = new java.util.LinkedHashSet<>(java.util.List.of(Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.RABBIT));
		s.addAll(COOKED_MEATS);
		ANY_MEATS = java.util.Collections.unmodifiableSet(s);
	}

	/** Pedido de um grupo de itens ({@link #ANY_MEAT} ou {@link #ANY_COOKED_MEAT}): qualquer combinação conta. */
	public static Order gatherGroup(String group, int count, long time) {
		return new Order(UUID.randomUUID(), OrderType.GATHER, OrderStatus.PENDING, group, "", GatherMode.ITEM, count, SearchProfile.AUTO, 0, false,
			Optional.empty(), Optional.empty(), time, new Progress());
	}

	public boolean isGroup() {
		return target.equals(ANY_MEAT) || target.equals(ANY_COOKED_MEAT) || target.startsWith("#");
	}

	/** Pedido de um grupo do jogo ("#minecraft:logs"): qualquer item da tag conta. */
	private transient java.util.@Nullable Set<Item> tagItems;

	private java.util.Set<Item> tagItems() {
		if (tagItems == null) {
			java.util.Set<Item> set = new java.util.LinkedHashSet<>();
			Identifier id = Identifier.tryParse(target.substring(1));
			if (id != null) {
				BuiltInRegistries.ITEM.getTagOrEmpty(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, id)).forEach(h -> set.add(h.value()));
			}
			tagItems = java.util.Collections.unmodifiableSet(set);
		}
		return tagItems;
	}

	/** Itens que atendem o pedido (um só, exceto nos pedidos de grupo). */
	public java.util.Set<Item> targetItems() {
		if (target.equals(ANY_MEAT)) {
			return ANY_MEATS;
		}
		if (target.equals(ANY_COOKED_MEAT)) {
			return COOKED_MEATS;
		}
		if (target.startsWith("#")) {
			return tagItems();
		}
		return java.util.Set.of(targetItem());
	}

	/** Quantos itens do pedido ela carrega (qualquer item do grupo). */
	public int countIn(dev.autonomouscompanions.entity.CompanionEntity c) {
		int n = 0;
		for (Item i : targetItems()) {
			n += c.inventory().count(i);
		}
		return n;
	}

	/** Nome do que foi pedido (o item, ou o nome do grupo). */
	public Component targetName() {
		if (target.startsWith("#")) {
			var items = tagItems();
			return Component.literal(items.isEmpty() ? target : new net.minecraft.world.item.ItemStack(items.iterator().next()).getHoverName().getString()
				+ " (ou outro do grupo " + target + ")");
		}
		if (isGroup()) {
			return Component.translatable(target.equals(ANY_MEAT) ? "order.autonomouscompanions.group.any_meat" : "order.autonomouscompanions.group.any_cooked_meat");
		}
		return new net.minecraft.world.item.ItemStack(targetItem()).getHoverName();
	}

	public Item targetItem() {
		if (target.equals(ANY_MEAT)) {
			return Items.BEEF;
		}
		if (target.equals(ANY_COOKED_MEAT)) {
			return Items.COOKED_BEEF;
		}
		if (target.startsWith("#")) {
			var items = tagItems();
			return items.isEmpty() ? Items.AIR : items.iterator().next();
		}
		Identifier key = Identifier.tryParse(target);
		if (key == null) {
			return Items.AIR;
		}
		return BuiltInRegistries.ITEM.getOptional(key).orElse(Items.AIR);
	}

	public String sourceBlock() {
		return sourceBlock;
	}

	public GatherMode mode() {
		return mode;
	}

	public int requested() {
		return requested;
	}

	public int remainingToDeliver() {
		return Math.max(0, requested - progress.delivered);
	}

	public SearchProfile profile() {
		return profile;
	}

	public int maxDistance() {
		return maxDistance;
	}

	public void setUrgent(boolean urgent) {
		this.urgent = urgent;
	}

	public boolean urgent() {
		return urgent;
	}

	public @Nullable UUID projectId() {
		return projectId;
	}

	public @Nullable Component reason() {
		return reason;
	}

	public void setReason(@Nullable Component reason) {
		this.reason = reason;
	}

	public long createdTime() {
		return createdTime;
	}

	public Progress progress() {
		return progress;
	}

	/** Em preparação (fabricando a ferramenta do trabalho): os itens ainda não ficam reservados para a entrega. */
	private transient boolean preparing;

	public void setPreparing(boolean preparing) {
		this.preparing = preparing;
	}

	/** Pedidos de item reservam a quantidade ainda não entregue no inventário da companheira. */
	public boolean reservesItems() {
		return (type == OrderType.GATHER || type == OrderType.CRAFT) && !status.isFinished() && status != OrderStatus.CANCELLED && !preparing;
	}
}
