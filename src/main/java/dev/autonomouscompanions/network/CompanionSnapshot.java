package dev.autonomouscompanions.network;

import dev.autonomouscompanions.entity.CompanionSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.item.ItemStack;

/** Retrato do estado de uma companheira enviado ao dono para a interface (o cliente nunca decide resultados). */
public record CompanionSnapshot(int entityId, UUID uuid, String name, int variant, String ownerName, int state, Component detail, float health,
	float maxHealth, int hunger, float saturation, float fatigue, int role, CompanionSettings settings, List<OrderView> orders, List<Component> taskStack,
	List<ItemStack> inventory, List<ItemStack> equipment, Stats stats, BlockPos pos, String dimension, Optional<BuildView> build, List<String> huntables,
	List<Boolean> reservedSlots) {

	public record OrderView(UUID id, int type, int status, String target, String sourceBlock, int mode, int requested, int delivered, int obtained,
		double distance, int blocksMined, int crafted, int placed, Optional<Component> reason, boolean urgent) {
		static void write(RegistryFriendlyByteBuf buf, OrderView o) {
			buf.writeUUID(o.id);
			buf.writeVarInt(o.type);
			buf.writeVarInt(o.status);
			buf.writeUtf(o.target);
			buf.writeUtf(o.sourceBlock);
			buf.writeVarInt(o.mode);
			buf.writeVarInt(o.requested);
			buf.writeVarInt(o.delivered);
			buf.writeVarInt(o.obtained);
			buf.writeDouble(o.distance);
			buf.writeVarInt(o.blocksMined);
			buf.writeVarInt(o.crafted);
			buf.writeVarInt(o.placed);
			buf.writeBoolean(o.reason.isPresent());
			o.reason.ifPresent(c -> ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, c));
			buf.writeBoolean(o.urgent);
		}

		static OrderView read(RegistryFriendlyByteBuf buf) {
			UUID id = buf.readUUID();
			int type = buf.readVarInt();
			int status = buf.readVarInt();
			String target = buf.readUtf();
			String source = buf.readUtf();
			int mode = buf.readVarInt();
			int req = buf.readVarInt();
			int del = buf.readVarInt();
			int obt = buf.readVarInt();
			double dist = buf.readDouble();
			int mined = buf.readVarInt();
			int crafted = buf.readVarInt();
			int placed = buf.readVarInt();
			Optional<Component> reason = buf.readBoolean() ? Optional.of(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf)) : Optional.empty();
			boolean urgent = buf.readBoolean();
			return new OrderView(id, type, status, target, source, mode, req, del, obt, dist, mined, crafted, placed, reason, urgent);
		}
	}

	public record Stats(double distance, int mined, int crafted, int placed, int kills, int deaths, int meals, int delivered) {
	}

	/** {@code kind}: 0 casa, 1 limpeza; {@code role}: posição dela na equipe (0 = líder). */
	public record BuildView(UUID projectId, int style, int size, int status, int percent, int placed, int total, BlockPos origin, int kind, int area,
		int team, int role) {
	}

	public static void write(RegistryFriendlyByteBuf buf, CompanionSnapshot s) {
		buf.writeVarInt(s.entityId);
		buf.writeUUID(s.uuid);
		buf.writeUtf(s.name);
		buf.writeVarInt(s.variant);
		buf.writeUtf(s.ownerName);
		buf.writeVarInt(s.state);
		ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, s.detail);
		buf.writeFloat(s.health);
		buf.writeFloat(s.maxHealth);
		buf.writeVarInt(s.hunger);
		buf.writeFloat(s.saturation);
		buf.writeFloat(s.fatigue);
		buf.writeVarInt(s.role);
		s.settings.write(buf);
		buf.writeVarInt(s.orders.size());
		for (OrderView o : s.orders) {
			OrderView.write(buf, o);
		}
		buf.writeVarInt(s.taskStack.size());
		for (Component c : s.taskStack) {
			ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, c);
		}
		buf.writeVarInt(s.inventory.size());
		for (ItemStack st : s.inventory) {
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, st);
		}
		buf.writeVarInt(s.equipment.size());
		for (ItemStack st : s.equipment) {
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, st);
		}
		Stats st = s.stats;
		buf.writeDouble(st.distance());
		buf.writeVarInt(st.mined());
		buf.writeVarInt(st.crafted());
		buf.writeVarInt(st.placed());
		buf.writeVarInt(st.kills());
		buf.writeVarInt(st.deaths());
		buf.writeVarInt(st.meals());
		buf.writeVarInt(st.delivered());
		buf.writeBlockPos(s.pos);
		buf.writeUtf(s.dimension);
		buf.writeBoolean(s.build.isPresent());
		s.build.ifPresent(b -> {
			buf.writeUUID(b.projectId());
			buf.writeVarInt(b.style());
			buf.writeVarInt(b.size());
			buf.writeVarInt(b.status());
			buf.writeVarInt(b.percent());
			buf.writeVarInt(b.placed());
			buf.writeVarInt(b.total());
			buf.writeBlockPos(b.origin());
			buf.writeVarInt(b.kind());
			buf.writeVarInt(b.area());
			buf.writeVarInt(b.team());
			buf.writeVarInt(b.role());
		});
		buf.writeVarInt(s.huntables.size());
		for (String h : s.huntables) {
			buf.writeUtf(h);
		}
		buf.writeVarInt(s.reservedSlots.size());
		for (Boolean b : s.reservedSlots) {
			buf.writeBoolean(b);
		}
	}

	public static CompanionSnapshot read(RegistryFriendlyByteBuf buf) {
		int entityId = buf.readVarInt();
		UUID uuid = buf.readUUID();
		String name = buf.readUtf();
		int variant = buf.readVarInt();
		String owner = buf.readUtf();
		int state = buf.readVarInt();
		Component detail = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
		float health = buf.readFloat();
		float maxHealth = buf.readFloat();
		int hunger = buf.readVarInt();
		float sat = buf.readFloat();
		float fatigue = buf.readFloat();
		int role = buf.readVarInt();
		CompanionSettings settings = CompanionSettings.read(buf);
		int n = buf.readVarInt();
		List<OrderView> orders = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			orders.add(OrderView.read(buf));
		}
		n = buf.readVarInt();
		List<Component> stack = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			stack.add(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf));
		}
		n = buf.readVarInt();
		List<ItemStack> inv = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			inv.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
		}
		n = buf.readVarInt();
		List<ItemStack> eq = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			eq.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
		}
		Stats stats = new Stats(buf.readDouble(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt());
		BlockPos pos = buf.readBlockPos();
		String dim = buf.readUtf();
		Optional<BuildView> build = Optional.empty();
		if (buf.readBoolean()) {
			build = Optional.of(new BuildView(buf.readUUID(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readVarInt(), buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
		}
		n = buf.readVarInt();
		List<String> hunt = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			hunt.add(buf.readUtf());
		}
		n = buf.readVarInt();
		List<Boolean> reserved = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			reserved.add(buf.readBoolean());
		}
		return new CompanionSnapshot(entityId, uuid, name, variant, owner, state, detail, health, maxHealth, hunger, sat, fatigue, role, settings, orders,
			stack, inv, eq, stats, pos, dim, build, hunt, reserved);
	}
}
