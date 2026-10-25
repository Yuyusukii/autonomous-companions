package dev.autonomouscompanions.yuno;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.nav.SafeSpot;
import dev.autonomouscompanions.registry.ModEntities;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Mantém uma única Yuno por jogadora (sempre por perto, sem nunca aparecer na frente dela) e agenda o presente diário:
 * uma maçã dourada comum num dos baús que a jogadora usa, entregue quando ela está longe e não está vendo o baú.
 */
public final class YunoManager {
	/** Um dia de Minecraft. */
	public static final long DAY = 24000L;
	/** "Longe do baú": a jogadora a mais que isto não está perto. */
	public static final double AWAY = 24.0;

	private YunoManager() {
	}

	/** Primeira vez: a Yuno passa a existir para esta jogadora (nasce escondida, perto). */
	public static void introduce(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		YunoData data = YunoData.get(server);
		if (data.entry(player.getUUID()) != null) {
			return;
		}
		data.getOrCreate(player.getUUID());
		data.setNextGift(player.getUUID(), server.overworld().getGameTime() + DAY);
		spawn(player);
		AutonomousCompanions.LOGGER.info("Yuno passou a observar {}", player.getGameProfile().name());
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		YunoData data = YunoData.get(server);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			YunoData.Entry e = data.entry(p.getUUID());
			if (e != null) {
				tickPlayer(server, p, e);
			}
		}
	}

	/** Um ciclo do gerenciador para uma jogadora (público para os testes). */
	public static void tickPlayer(MinecraftServer server, ServerPlayer player, YunoData.Entry e) {
		if (player.isSpectator() || !player.isAlive()) {
			return;
		}
		YunoEntity yuno = find(server, e.yunoId());
		ServerLevel level = (ServerLevel) player.level();
		if (yuno == null || yuno.level() != level) {
			// Ficou num chunk descarregado ou em outra dimensão: a Yuno "de verdade" passa a ser uma nova, perto da
			// jogadora; a antiga se desfaz sozinha se o chunk dela for carregado de novo.
			if (yuno != null) {
				yuno.discard();
			}
			yuno = spawn(player);
		} else if (yuno.distanceTo(player) > 96 && yuno.mode() != YunoEntity.Mode.DELIVERING) {
			yuno.vanish(level, false); // reposiciona escondida perto da jogadora
		}
		// Presente diário (só conta os dias em que a jogadora tem algum baú).
		YunoData data = YunoData.get(server);
		long now = server.overworld().getGameTime();
		if (now >= e.nextGift()) {
			if (!e.chests().isEmpty()) {
				data.setOwed(player.getUUID(), e.owed() + 1);
			}
			data.setNextGift(player.getUUID(), now + DAY);
		}
		if (e.owed() > 0 && yuno != null && yuno.mode() != YunoEntity.Mode.DELIVERING) {
			GlobalPos chest = chooseChest(server, player, e);
			if (chest != null) {
				yuno.startDelivery(level, chest);
			}
		}
	}

	/** Baú lembrado, na dimensão e área carregada da jogadora, com espaço, com ela longe e sem estar olhando para ele. */
	public static @Nullable GlobalPos chooseChest(MinecraftServer server, ServerPlayer player, YunoData.Entry e) {
		ServerLevel level = (ServerLevel) player.level();
		List<GlobalPos> chests = List.copyOf(e.chests());
		for (int i = chests.size() - 1; i >= 0; i--) {
			GlobalPos g = chests.get(i);
			if (g.dimension() != level.dimension() || !level.hasChunkAt(g.pos())) {
				continue;
			}
			if (!(level.getBlockState(g.pos()).getBlock() instanceof ChestBlock) || !(level.getBlockEntity(g.pos()) instanceof ChestBlockEntity chest)) {
				YunoData.get(server).forgetChest(player.getUUID(), g); // quebrado ou trocado
				continue;
			}
			Vec3 c = Vec3.atCenterOf(g.pos());
			if (player.distanceToSqr(c) <= AWAY * AWAY || YunoEntity.inViewCone(player, c, 60)) {
				continue;
			}
			if (!hasRoom(chest)) {
				continue;
			}
			return g;
		}
		return null;
	}

	private static boolean hasRoom(ChestBlockEntity chest) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack s = chest.getItem(i);
			if (s.isEmpty() || s.is(Items.GOLDEN_APPLE) && s.getCount() < s.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	/** Coloca uma maçã dourada comum no baú (junta com outras maçãs douradas, senão num espaço vazio). */
	public static boolean putGift(ServerLevel level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof ChestBlockEntity chest)) {
			return false;
		}
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack s = chest.getItem(i);
			if (s.is(Items.GOLDEN_APPLE) && s.getCount() < s.getMaxStackSize()) {
				s.grow(1);
				chest.setChanged();
				return true;
			}
		}
		for (int i = 0; i < chest.getContainerSize(); i++) {
			if (chest.getItem(i).isEmpty()) {
				chest.setItem(i, YunoEntity.gift());
				chest.setChanged();
				return true;
			}
		}
		return false;
	}

	/**
	 * A jogadora abriu um baú: ele passa a ser "dela" (lembrado para os presentes). Se houver um presente da Yuno ainda
	 * não descoberto nele (ou na outra metade de um baú duplo), corações, um som suave e um recado misterioso.
	 */
	public static void onChestOpened(ServerPlayer player, BlockPos pos) {
		ServerLevel level = (ServerLevel) player.level();
		if (!(level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
			return;
		}
		YunoData data = YunoData.get(level.getServer());
		if (data.entry(player.getUUID()) == null) {
			return; // ainda não conhece a Yuno
		}
		GlobalPos here = GlobalPos.of(level.dimension(), pos);
		data.rememberChest(player.getUUID(), here);
		boolean surprise = data.takeSurprise(player.getUUID(), here);
		for (Direction d : Direction.Plane.HORIZONTAL) {
			surprise |= data.takeSurprise(player.getUUID(), GlobalPos.of(level.dimension(), pos.relative(d)));
		}
		if (surprise) {
			level.sendParticles(ParticleTypes.HEART, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 5, 0.35, 0.2, 0.35, 0.0);
			level.sendParticles(ParticleTypes.CHERRY_LEAVES, pos.getX() + 0.5, pos.getY() + 1.4, pos.getZ() + 0.5, 10, 0.4, 0.3, 0.4, 0.01);
			level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8F, 1.4F);
			player.sendSystemMessage(Component.translatable("message.autonomouscompanions.yuno_gift"), true);
		}
	}

	/** A Yuno atual desta jogadora (é ela mesma? as cópias antigas se desfazem). */
	public static boolean isCurrent(MinecraftServer server, YunoEntity yuno) {
		if (yuno.owner() == null) {
			return false;
		}
		YunoData.Entry e = YunoData.get(server).entry(yuno.owner());
		return e != null && yuno.getUUID().equals(e.yunoId());
	}

	public static @Nullable YunoEntity find(MinecraftServer server, @Nullable UUID id) {
		if (id == null) {
			return null;
		}
		for (ServerLevel level : server.getAllLevels()) {
			Entity e = level.getEntity(id);
			if (e instanceof YunoEntity y && y.isAlive()) {
				return y;
			}
		}
		return null;
	}

	/** Cria a Yuno escondida (invisível) longe da vista; ela surge observando depois de alguns segundos. */
	public static @Nullable YunoEntity spawn(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		YunoEntity y = ModEntities.YUNO.create(level, EntitySpawnReason.EVENT);
		if (y == null) {
			return null;
		}
		y.setOwner(player.getUUID());
		BlockPos spot = y.pickSpot(level, player, false);
		if (spot == null) {
			spot = SafeSpot.findNear(level, player.blockPosition(), 20, 40, 8, ModEntities.YUNO);
		}
		if (spot == null) {
			return null;
		}
		y.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
		y.setInvisible(true);
		y.startHidden(level.getGameTime() + 200 + level.getRandom().nextInt(400));
		level.addFreshEntity(y);
		YunoData.get(level.getServer()).setYuno(player.getUUID(), y.getUUID());
		return y;
	}
}
