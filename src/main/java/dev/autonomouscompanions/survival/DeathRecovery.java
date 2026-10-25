package dev.autonomouscompanions.survival;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.config.CompanionConfig;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.SafeSpot;
import dev.autonomouscompanions.persist.CompanionWorldData;
import dev.autonomouscompanions.registry.ModEntities;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Morte e recuperação controladas. Ao morrer, um instantâneo completo da companheira (nome, dono, função, ordens,
 * necessidades e — se configurado — inventário e equipamentos) é guardado no registro do mundo. Com
 * {@code manterInventarioAoMorrer=false} os itens são largados no local da morte e o instantâneo vai sem eles.
 * A ressurreição recria a MESMA identidade (mesmo UUID) uma única vez, após conferir que não existe outra cópia viva.
 */
public final class DeathRecovery {
	private DeathRecovery() {
	}

	public static void onCompanionDeath(ServerLevel level, CompanionEntity c, DamageSource source) {
		CompanionConfig cfg = ConfigManager.get();
		c.stats().deaths++;
		if (!cfg.manterInventarioAoMorrer) {
			for (int i = 0; i < c.inventory().getContainerSize(); i++) {
				dropAt(level, c, c.inventory().removeItemNoUpdate(i));
			}
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				if (slot.getType() == EquipmentSlot.Type.HAND || slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
					dropAt(level, c, c.getItemBySlot(slot));
					c.setItemSlot(slot, ItemStack.EMPTY);
				}
			}
		}
		CompoundTag snapshot;
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(c.problemPath(), AutonomousCompanions.LOGGER)) {
			TagValueOutput out = TagValueOutput.createWithContext(reporter, level.registryAccess());
			c.saveWithoutId(out);
			snapshot = out.buildResult();
		}
		CompanionWorldData.get(level.getServer()).recordDeath(c, snapshot, level.getGameTime());
		// Itens da mão/armadura não devem ser largados pelo jogo base quando guardados no registro.
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			c.setItemSlot(slot, ItemStack.EMPTY);
		}
		c.inventory().clearContent();
		ServerPlayer owner = c.ownerAnywhere();
		if (owner != null) {
			Component how = switch (cfg.comportamentoMorte) {
				case "PERMANENTE" -> Component.translatable("message.autonomouscompanions.death_permanent");
				case "MANUAL" -> Component.translatable("message.autonomouscompanions.death_manual", c.companionName());
				default -> Component.translatable("message.autonomouscompanions.death_respawn", cfg.segundosParaRessurgir);
			};
			owner.sendSystemMessage(Component.translatable("message.autonomouscompanions.death", c.getDisplayName(), source.getLocalizedDeathMessage(c),
				how));
		}
	}

	private static void dropAt(ServerLevel level, CompanionEntity c, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		ItemEntity e = new ItemEntity(level, c.getX(), c.getY() + 0.5, c.getZ(), stack.copy());
		e.setDefaultPickUpDelay();
		level.addFreshEntity(e);
	}

	/** Executado a cada segundo: ressurreições automáticas pendentes. */
	public static void tick(MinecraftServer server) {
		CompanionConfig cfg = ConfigManager.get();
		if (!cfg.comportamentoMorte.equals("RESSURGIR_PERTO_DO_DONO")) {
			return;
		}
		CompanionWorldData data = CompanionWorldData.get(server);
		long now = server.overworld().getGameTime();
		for (CompanionWorldData.CompanionRecord r : data.deadRecords()) {
			if (now - r.deathTime() < cfg.segundosParaRessurgir * 20L || r.owner().isEmpty()) {
				continue;
			}
			ServerPlayer owner = server.getPlayerList().getPlayer(r.owner().get());
			if (owner == null) {
				continue;
			}
			revive(server, r.id(), owner);
		}
	}

	/** Ressuscita uma companheira registrada como morta perto do jogador indicado. */
	public static @Nullable CompanionEntity revive(MinecraftServer server, UUID id, ServerPlayer near) {
		CompanionWorldData data = CompanionWorldData.get(server);
		CompanionWorldData.CompanionRecord r = data.record(id);
		if (r == null || !r.dead() || r.snapshot().isEmpty()) {
			return null;
		}
		// Anti-duplicação: nunca cria se já houver uma entidade com essa identidade carregada.
		for (ServerLevel level : server.getAllLevels()) {
			net.minecraft.world.entity.Entity existing = level.getEntity(id);
			if (existing != null) {
				// Uma cópia viva significa que o registro estava desatualizado; o corpo em animação de morte só adia.
				if (existing.isAlive()) {
					data.markRevived(id, level.dimension(), existing.blockPosition());
				}
				return null;
			}
		}
		ServerLevel level = (ServerLevel) near.level();
		BlockPos spot = SafeSpot.findNear(level, near.blockPosition(), 2, 8, 4, ModEntities.typeFor(r.variant()));
		if (spot == null) {
			return null;
		}
		CompanionEntity c = ModEntities.typeFor(r.variant()).create(level, EntitySpawnReason.EVENT);
		if (c == null) {
			return null;
		}
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(c.problemPath(), AutonomousCompanions.LOGGER)) {
			c.load(TagValueInput.create(reporter, level.registryAccess(), r.snapshot().get()));
		}
		c.setUUID(id);
		c.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, near.getYRot() + 180, 0);
		c.prepareAfterRevive();
		if (!level.addFreshEntity(c)) {
			return null;
		}
		data.markRevived(id, level.dimension(), spot);
		near.sendSystemMessage(Component.translatable("message.autonomouscompanions.revived", c.getDisplayName()));
		return c;
	}
}
