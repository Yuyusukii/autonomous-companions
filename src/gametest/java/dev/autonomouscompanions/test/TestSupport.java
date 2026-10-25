package dev.autonomouscompanions.test;

import com.mojang.authlib.GameProfile;
import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.entity.CompanionVariant;
import dev.autonomouscompanions.registry.ModEntities;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Utilitários compartilhados pelos testes de jogo. */
public final class TestSupport {
	/** Altura do chão de grama nas arenas (camada 0 = terra, camada 1 = grama). */
	public static final int FLOOR = 2;

	private TestSupport() {
	}

	/** Jogador simulado no nível do teste, com conexão local (sem cliente real), no modo de jogo pedido. */
	public static ServerPlayer owner(GameTestHelper h, BlockPos rel, GameType mode) {
		ServerLevel level = h.getLevel();
		String name = "dona" + Integer.toHexString(UUID.randomUUID().hashCode() & 0xFFFF);
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
		ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
		player.setGameMode(mode);
		moveOwner(h, player, rel);
		return player;
	}

	public static void moveOwner(GameTestHelper h, ServerPlayer player, BlockPos rel) {
		BlockPos abs = h.absolutePos(rel);
		player.teleportTo(h.getLevel(), abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, Set.<Relative>of(), 0.0F, 0.0F, false);
	}

	/** Companheira já nomeada e vinculada à dona. */
	public static CompanionEntity companion(GameTestHelper h, ServerPlayer owner, BlockPos rel, String name) {
		CompanionEntity c = h.spawn(ModEntities.COMPANION, rel, EntitySpawnReason.EVENT);
		c.setVariant(CompanionVariant.byIndex(Math.floorMod(name.hashCode(), 3)));
		c.setOwner(owner);
		c.setCompanionName(name);
		c.settings().idleExplore = false;
		c.settings().roleWork = false;
		// Regras antigas de sobrevivência (vulnerável, fome, cansaço, Demônio no golpe fatal): os testes existentes verificam
		// essas regras, que continuam no mod atrás das opções da configuração. As regras novas (vida infinita etc.) têm testes
		// próprios, que desligam isto (SustainedModeGameTests).
		c.settings().classicSurvival = true;
		return c;
	}

	/** A ajudante da dona (mesma configuração da criação real). */
	public static CompanionEntity helper(GameTestHelper h, ServerPlayer owner, BlockPos rel) {
		CompanionEntity c = h.spawn(ModEntities.HELPER, rel, EntitySpawnReason.EVENT);
		c.setOwner(owner);
		dev.autonomouscompanions.spawn.StarterSpawner.setupHelper(c);
		c.applyConfiguredAttributes();
		c.settings().classicSurvival = true; // ajudante fornecedora (regras antigas); a segurança tem teste próprio
		return c;
	}

	public static void give(CompanionEntity c, Item item, int count) {
		while (count > 0) {
			int k = Math.min(count, item.getDefaultMaxStackSize());
			ItemStack rest = c.inventory().insert(new ItemStack(item, k));
			if (!rest.isEmpty()) {
				throw new IllegalStateException("inventário da companheira cheio ao preparar o teste");
			}
			count -= k;
		}
	}

	public static int count(Player p, Item item) {
		int n = 0;
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	public static int itemEntities(GameTestHelper h, Item item) {
		return h.getEntities(net.minecraft.world.entity.EntityTypes.ITEM).stream().filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount())
			.sum();
	}

	/** Salva a entidade como o jogo salva no disco. */
	public static CompoundTag save(Entity e) {
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(e.problemPath(), AutonomousCompanions.LOGGER)) {
			TagValueOutput out = TagValueOutput.createWithContext(reporter, e.registryAccess());
			e.saveWithoutId(out);
			return out.buildResult();
		}
	}

	/** Simula descarregar e recarregar a companheira (mesmo UUID, mesmos dados), como ao fechar e reabrir o mundo. */
	public static CompanionEntity reload(GameTestHelper h, CompanionEntity c) {
		ServerLevel level = h.getLevel();
		CompoundTag tag = save(c);
		c.discard();
		CompanionEntity copy = ModEntities.COMPANION.create(level, EntitySpawnReason.LOAD);
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(copy.problemPath(), AutonomousCompanions.LOGGER)) {
			copy.load(TagValueInput.create(reporter, level.registryAccess(), tag));
		}
		if (!level.addFreshEntity(copy)) {
			throw new IllegalStateException("não foi possível readicionar a companheira");
		}
		return copy;
	}

	/** Remove as companheiras e jogadores simulados do teste para não pesarem nos testes seguintes. */
	public static void cleanup(GameTestHelper h, ServerPlayer... players) {
		h.getEntities(ModEntities.COMPANION).forEach(Entity::discard);
		h.getEntities(ModEntities.HELPER).forEach(Entity::discard);
		for (ServerPlayer p : players) {
			h.getLevel().getServer().getPlayerList().remove(p);
		}
	}

	public static double horizontal(Entity a, Entity b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}
}
