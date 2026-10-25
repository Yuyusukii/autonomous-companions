package dev.autonomouscompanions.network;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.entity.CompanionSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Todos os pacotes do mod. Cliente → servidor carregam apenas intenções; o servidor valida tudo. */
public final class Payloads {
	private Payloads() {
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> ptype(String path) {
		return new CustomPacketPayload.Type<>(AutonomousCompanions.id(path));
	}

	// ============================================================== cliente → servidor

	public record Rename(int entityId, String name) implements CustomPacketPayload {
		public static final Type<Rename> TYPE = ptype("rename");
		public static final StreamCodec<RegistryFriendlyByteBuf, Rename> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeUtf(p.name, 64);
			}, b -> new Rename(b.readVarInt(), b.readUtf(64)));

		@Override
		public Type<Rename> type() {
			return TYPE;
		}
	}

	/** Pede/cancela atualizações periódicas do painel. */
	public record Subscribe(int entityId, boolean subscribe) implements CustomPacketPayload {
		public static final Type<Subscribe> TYPE = ptype("subscribe");
		public static final StreamCodec<RegistryFriendlyByteBuf, Subscribe> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeBoolean(p.subscribe);
			}, b -> new Subscribe(b.readVarInt(), b.readBoolean()));

		@Override
		public Type<Subscribe> type() {
			return TYPE;
		}
	}

	public record AnalyzeGather(int entityId, String blockId, int mode, int count) implements CustomPacketPayload {
		public static final Type<AnalyzeGather> TYPE = ptype("analyze_gather");
		public static final StreamCodec<RegistryFriendlyByteBuf, AnalyzeGather> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeUtf(p.blockId);
				b.writeVarInt(p.mode);
				b.writeVarInt(p.count);
			}, b -> new AnalyzeGather(b.readVarInt(), b.readUtf(), b.readVarInt(), b.readVarInt()));

		@Override
		public Type<AnalyzeGather> type() {
			return TYPE;
		}
	}

	public record StartGather(int entityId, String blockId, int mode, int count, int profile, int maxDistance, boolean urgent)
		implements CustomPacketPayload {
		public static final Type<StartGather> TYPE = ptype("start_gather");
		public static final StreamCodec<RegistryFriendlyByteBuf, StartGather> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeUtf(p.blockId);
				b.writeVarInt(p.mode);
				b.writeVarInt(p.count);
				b.writeVarInt(p.profile);
				b.writeVarInt(p.maxDistance);
				b.writeBoolean(p.urgent);
			}, b -> new StartGather(b.readVarInt(), b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean()));

		@Override
		public Type<StartGather> type() {
			return TYPE;
		}
	}

	/** Pedido de um item específico (carne, cozida ou crua, ou qualquer item fabricável). */
	public record StartItemOrder(int entityId, String itemId, int count) implements CustomPacketPayload {
		public static final Type<StartItemOrder> TYPE = ptype("start_item_order");
		public static final StreamCodec<RegistryFriendlyByteBuf, StartItemOrder> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeUtf(p.itemId);
				b.writeVarInt(p.count);
			}, b -> new StartItemOrder(b.readVarInt(), b.readUtf(), b.readVarInt()));

		@Override
		public Type<StartItemOrder> type() {
			return TYPE;
		}
	}

	public record OrderControl(int entityId, UUID orderId, int action) implements CustomPacketPayload {
		public static final int CANCEL = 0;
		public static final int SUSPEND = 1;
		public static final int RESUME = 2;
		public static final int RETRY = 3;
		public static final int CLEAR_FINISHED = 4;
		public static final int URGENT = 5;
		public static final Type<OrderControl> TYPE = ptype("order_control");
		public static final StreamCodec<RegistryFriendlyByteBuf, OrderControl> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeUUID(p.orderId);
				b.writeVarInt(p.action);
			}, b -> new OrderControl(b.readVarInt(), b.readUUID(), b.readVarInt()));

		@Override
		public Type<OrderControl> type() {
			return TYPE;
		}
	}

	public record SetRole(int entityId, int role) implements CustomPacketPayload {
		public static final Type<SetRole> TYPE = ptype("set_role");
		public static final StreamCodec<RegistryFriendlyByteBuf, SetRole> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeVarInt(p.role);
			}, b -> new SetRole(b.readVarInt(), b.readVarInt()));

		@Override
		public Type<SetRole> type() {
			return TYPE;
		}
	}

	public record UpdateSettings(int entityId, CompanionSettings settings) implements CustomPacketPayload {
		public static final Type<UpdateSettings> TYPE = ptype("settings");
		public static final StreamCodec<RegistryFriendlyByteBuf, UpdateSettings> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				p.settings.write(b);
			}, b -> new UpdateSettings(b.readVarInt(), CompanionSettings.read(b)));

		@Override
		public Type<UpdateSettings> type() {
			return TYPE;
		}
	}

	public record Deliver(int entityId, int mode, int slot) implements CustomPacketPayload {
		public static final int MISSION = 0;
		public static final int SLOT = 1;
		public static final int MATERIALS = 2;
		public static final Type<Deliver> TYPE = ptype("deliver");
		public static final StreamCodec<RegistryFriendlyByteBuf, Deliver> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeVarInt(p.mode);
				b.writeVarInt(p.slot);
			}, b -> new Deliver(b.readVarInt(), b.readVarInt(), b.readVarInt()));

		@Override
		public Type<Deliver> type() {
			return TYPE;
		}
	}

	/** Jogadora entrega à companheira {@code count} itens do espaço {@code slot} do próprio inventário. */
	public record GiveItems(int entityId, int slot, int count) implements CustomPacketPayload {
		public static final Type<GiveItems> TYPE = ptype("give_items");
		public static final StreamCodec<RegistryFriendlyByteBuf, GiveItems> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeVarInt(p.slot);
				b.writeVarInt(p.count);
			}, b -> new GiveItems(b.readVarInt(), b.readVarInt(), b.readVarInt()));

		@Override
		public Type<GiveItems> type() {
			return TYPE;
		}
	}

	public record SimpleCommand(int entityId, int command) implements CustomPacketPayload {
		public static final int FOLLOW = 0;
		public static final int STAY = 1;
		public static final int COME_BACK = 2;
		public static final int EQUIP = 3;
		public static final int EAT = 4;
		public static final int REST = 5;
		public static final Type<SimpleCommand> TYPE = ptype("simple_command");
		public static final StreamCodec<RegistryFriendlyByteBuf, SimpleCommand> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeVarInt(p.command);
			}, b -> new SimpleCommand(b.readVarInt(), b.readVarInt()));

		@Override
		public Type<SimpleCommand> type() {
			return TYPE;
		}
	}

	/** Entra no modo de marcação de local de construção. */
	public record BuildMark(int entityId, int style, int size, long seed, boolean prepareTerrain, int kind, int area, int team) implements CustomPacketPayload {
		public static final int HOUSE = 0;
		public static final int TERRAIN = 1;

		public BuildMark(int entityId, int style, int size, long seed, boolean prepareTerrain) {
			this(entityId, style, size, seed, prepareTerrain, HOUSE, 0, 1);
		}

		public static final Type<BuildMark> TYPE = ptype("build_mark");
		public static final StreamCodec<RegistryFriendlyByteBuf, BuildMark> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeVarInt(p.style);
				b.writeVarInt(p.size);
				b.writeLong(p.seed);
				b.writeBoolean(p.prepareTerrain);
				b.writeVarInt(p.kind);
				b.writeVarInt(p.area);
				b.writeVarInt(p.team);
			}, b -> new BuildMark(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readLong(), b.readBoolean(), b.readVarInt(), b.readVarInt(),
				b.readVarInt()));

		@Override
		public Type<BuildMark> type() {
			return TYPE;
		}
	}

	/** Recalcula a prévia (girar, trocar opções) ou confirma o projeto. */
	public record BuildDecision(int entityId, BlockPos clicked, int facing, int style, int size, long seed, boolean prepareTerrain, boolean confirm, int kind,
		int area, int team) implements CustomPacketPayload {
		public BuildDecision(int entityId, BlockPos clicked, int facing, int style, int size, long seed, boolean prepareTerrain, boolean confirm) {
			this(entityId, clicked, facing, style, size, seed, prepareTerrain, confirm, BuildMark.HOUSE, 0, 1);
		}

		public static final Type<BuildDecision> TYPE = ptype("build_decision");
		public static final StreamCodec<RegistryFriendlyByteBuf, BuildDecision> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeBlockPos(p.clicked);
				b.writeVarInt(p.facing);
				b.writeVarInt(p.style);
				b.writeVarInt(p.size);
				b.writeLong(p.seed);
				b.writeBoolean(p.prepareTerrain);
				b.writeBoolean(p.confirm);
				b.writeVarInt(p.kind);
				b.writeVarInt(p.area);
				b.writeVarInt(p.team);
			}, b -> new BuildDecision(b.readVarInt(), b.readBlockPos(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readLong(), b.readBoolean(),
				b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt()));

		@Override
		public Type<BuildDecision> type() {
			return TYPE;
		}
	}

	public record BuildControl(int entityId, int action) implements CustomPacketPayload {
		public static final int SUSPEND = 0;
		public static final int RESUME = 1;
		public static final int CANCEL = 2;
		public static final Type<BuildControl> TYPE = ptype("build_control");
		public static final StreamCodec<RegistryFriendlyByteBuf, BuildControl> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeVarInt(p.action);
			}, b -> new BuildControl(b.readVarInt(), b.readVarInt()));

		@Override
		public Type<BuildControl> type() {
			return TYPE;
		}
	}

	// ============================================================== servidor → cliente

	public record OpenNaming(int entityId, int variant) implements CustomPacketPayload {
		public static final Type<OpenNaming> TYPE = ptype("open_naming");
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenNaming> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeVarInt(p.variant);
			}, b -> new OpenNaming(b.readVarInt(), b.readVarInt()));

		@Override
		public Type<OpenNaming> type() {
			return TYPE;
		}
	}

	public record Snapshot(CompanionSnapshot snapshot, boolean open) implements CustomPacketPayload {
		public static final Type<Snapshot> TYPE = ptype("snapshot");
		public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				CompanionSnapshot.write(b, p.snapshot);
				b.writeBoolean(p.open);
			}, b -> new Snapshot(CompanionSnapshot.read(b), b.readBoolean()));

		@Override
		public Type<Snapshot> type() {
			return TYPE;
		}
	}

	/** Resultado da análise de um pedido do catálogo. */
	public record Analysis(int entityId, String blockId, int mode, boolean feasible, String targetItem, int suggestedProfile, int range,
		List<Component> lines) implements CustomPacketPayload {
		public static final Type<Analysis> TYPE = ptype("analysis");
		public static final StreamCodec<RegistryFriendlyByteBuf, Analysis> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeUtf(p.blockId);
				b.writeVarInt(p.mode);
				b.writeBoolean(p.feasible);
				b.writeUtf(p.targetItem);
				b.writeVarInt(p.suggestedProfile);
				b.writeVarInt(p.range);
				writeComponents(b, p.lines);
			}, b -> new Analysis(b.readVarInt(), b.readUtf(), b.readVarInt(), b.readBoolean(), b.readUtf(), b.readVarInt(), b.readVarInt(),
				readComponents(b)));

		@Override
		public Type<Analysis> type() {
			return TYPE;
		}
	}

	public record MaterialLine(String item, int needed, int have) {
	}

	public record BuildPreview(int entityId, boolean valid, BlockPos clicked, BlockPos origin, int facing, int style, int size, long seed,
		boolean prepareTerrain, String wood, int width, int depth, int height, int blocks, List<Component> problems, List<Component> warnings,
		List<MaterialLine> materials, int kind, int team, int trees, int fill) implements CustomPacketPayload {
		public static final Type<BuildPreview> TYPE = ptype("build_preview");
		public static final StreamCodec<RegistryFriendlyByteBuf, BuildPreview> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entityId);
				b.writeBoolean(p.valid);
				b.writeBlockPos(p.clicked);
				b.writeBlockPos(p.origin);
				b.writeVarInt(p.facing);
				b.writeVarInt(p.style);
				b.writeVarInt(p.size);
				b.writeLong(p.seed);
				b.writeBoolean(p.prepareTerrain);
				b.writeUtf(p.wood);
				b.writeVarInt(p.width);
				b.writeVarInt(p.depth);
				b.writeVarInt(p.height);
				b.writeVarInt(p.blocks);
				writeComponents(b, p.problems);
				writeComponents(b, p.warnings);
				b.writeVarInt(p.materials.size());
				for (MaterialLine m : p.materials) {
					b.writeUtf(m.item());
					b.writeVarInt(m.needed());
					b.writeVarInt(m.have());
				}
				b.writeVarInt(p.kind);
				b.writeVarInt(p.team);
				b.writeVarInt(p.trees);
				b.writeVarInt(p.fill);
			}, b -> {
				int id = b.readVarInt();
				boolean valid = b.readBoolean();
				BlockPos clicked = b.readBlockPos();
				BlockPos origin = b.readBlockPos();
				int facing = b.readVarInt();
				int style = b.readVarInt();
				int size = b.readVarInt();
				long seed = b.readLong();
				boolean prep = b.readBoolean();
				String wood = b.readUtf();
				int w = b.readVarInt();
				int d = b.readVarInt();
				int h = b.readVarInt();
				int blocks = b.readVarInt();
				List<Component> problems = readComponents(b);
				List<Component> warnings = readComponents(b);
				int n = b.readVarInt();
				List<MaterialLine> mats = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					mats.add(new MaterialLine(b.readUtf(), b.readVarInt(), b.readVarInt()));
				}
				return new BuildPreview(id, valid, clicked, origin, facing, style, size, seed, prep, wood, w, d, h, blocks, problems, warnings, mats,
					b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt());
			});

		@Override
		public Type<BuildPreview> type() {
			return TYPE;
		}
	}

	public record MarkingMode(boolean active, int entityId) implements CustomPacketPayload {
		public static final Type<MarkingMode> TYPE = ptype("marking_mode");
		public static final StreamCodec<RegistryFriendlyByteBuf, MarkingMode> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeBoolean(p.active);
				b.writeVarInt(p.entityId);
			}, b -> new MarkingMode(b.readBoolean(), b.readVarInt()));

		@Override
		public Type<MarkingMode> type() {
			return TYPE;
		}
	}

	static void writeComponents(RegistryFriendlyByteBuf b, List<Component> list) {
		b.writeVarInt(list.size());
		for (Component c : list) {
			ComponentSerialization.TRUSTED_STREAM_CODEC.encode(b, c);
		}
	}

	static List<Component> readComponents(RegistryFriendlyByteBuf b) {
		int n = b.readVarInt();
		List<Component> list = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			list.add(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(b));
		}
		return list;
	}

	/** Uma companheira na lista do apito. {@code distance} = -1 quando está em outra dimensão. */
	public record WhistleEntry(UUID id, String name, int variant, int state, int distance, float health) {
	}

	/** Servidor → cliente: abre o menu do apito com as companheiras da jogadora. */
	public record WhistleMenu(List<WhistleEntry> entries) implements CustomPacketPayload {
		public static final Type<WhistleMenu> TYPE = ptype("whistle_menu");
		public static final StreamCodec<RegistryFriendlyByteBuf, WhistleMenu> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.entries.size());
				for (WhistleEntry e : p.entries) {
					b.writeUUID(e.id());
					b.writeUtf(e.name(), 64);
					b.writeVarInt(e.variant());
					b.writeVarInt(e.state());
					b.writeVarInt(e.distance() + 1);
					b.writeFloat(e.health());
				}
			}, b -> {
				int n = Math.min(b.readVarInt(), 16);
				List<WhistleEntry> list = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					list.add(new WhistleEntry(b.readUUID(), b.readUtf(64), b.readVarInt(), b.readVarInt(), b.readVarInt() - 1, b.readFloat()));
				}
				return new WhistleMenu(list);
			});

		@Override
		public Type<WhistleMenu> type() {
			return TYPE;
		}
	}

	/** Cliente → servidor: chama uma companheira ({@code target}) ou todas ({@code all}). */
	public record WhistleCall(boolean all, UUID target) implements CustomPacketPayload {
		public static final Type<WhistleCall> TYPE = ptype("whistle_call");
		public static final StreamCodec<RegistryFriendlyByteBuf, WhistleCall> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeBoolean(p.all);
				b.writeUUID(p.target);
			}, b -> new WhistleCall(b.readBoolean(), b.readUUID()));

		@Override
		public Type<WhistleCall> type() {
			return TYPE;
		}
	}
}
