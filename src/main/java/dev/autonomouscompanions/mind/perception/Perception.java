package dev.autonomouscompanions.mind.perception;

import dev.autonomouscompanions.demon.DemonMode;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.mind.memory.WorldMemory;
import dev.autonomouscompanions.task.Order;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Percepção: o que a companheira sabe do mundo neste instante, lido do estado real do jogo (nunca suposto): posição,
 * bioma, hora, clima, vida, fome, cansaço, equipamento, inventário, blocos de interesse por perto, criaturas, perigos,
 * a dona, a tarefa atual e o que ela lembra. Calculada sob demanda (conversa, comandos, avaliação de habilidades), não a
 * cada tick: a varredura de blocos é limitada a um cubo de 25×17×25 ao redor dela.
 */
public final class Perception {
	public static final int BLOCK_RADIUS = 12;
	public static final int ENTITY_RADIUS = 24;

	/** Bloco de interesse mais próximo de um tipo e quantos há na área. */
	public record Seen(String name, BlockPos nearest, double distance, int count) {
	}

	/** Criatura mais próxima de um tipo e quantas há. */
	public record Creature(String name, double distance, int count) {
	}

	public record Snapshot(
		BlockPos pos, String dimension, String biome, long timeOfDay, boolean night, boolean raining, boolean thundering,
		float health, float maxHealth, int hunger, float fatigue, int armor, String mainHand, int freeSlots,
		Map<String, Integer> inventory, Map<String, String> bestTools,
		Map<String, Seen> blocks, List<Creature> hostiles, List<Creature> animals, List<String> players,
		List<String> dangers, double ownerDistance, @Nullable String ownerDirection, String activity, @Nullable String order,
		@Nullable BlockPos base, Map<String, Integer> remembered, int hypotheses) {

		public boolean sees(String blockName) {
			return blocks.containsKey(blockName);
		}

		/** Descrição em português, uma linha por assunto. */
		public List<String> describe() {
			List<String> out = new ArrayList<>();
			out.add(String.format("Estou em %d, %d, %d (%s, bioma %s). %s, %s.", pos.getX(), pos.getY(), pos.getZ(), dimension, biome,
				night ? "É noite" : "É dia", thundering ? "com tempestade" : raining ? "chovendo" : "tempo limpo"));
			out.add(String.format("Vida %.0f/%.0f, fome %d/20, cansaço %.0f, armadura %d. Na mão: %s. Espaços livres na bolsa: %d.", health, maxHealth, hunger,
				fatigue, armor, mainHand, freeSlots));
			if (!bestTools.isEmpty()) {
				out.add("Ferramentas: " + String.join(", ", bestTools.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList()) + ".");
			}
			if (!inventory.isEmpty()) {
				out.add("Carrego: " + String.join(", ", inventory.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList()) + ".");
			}
			if (!blocks.isEmpty()) {
				out.add("Vejo por perto: " + String.join(", ", blocks.values().stream().map(s -> s.count() + " " + s.name() + " (o mais perto a "
					+ (int) s.distance() + " blocos)").toList()) + ".");
			}
			if (!hostiles.isEmpty()) {
				out.add("Monstros: " + String.join(", ", hostiles.stream().map(h -> h.count() + " " + h.name() + " (a " + (int) h.distance() + ")").toList())
					+ ".");
			}
			if (!animals.isEmpty()) {
				out.add("Animais: " + String.join(", ", animals.stream().map(a -> a.count() + " " + a.name()).toList()) + ".");
			}
			if (!players.isEmpty()) {
				out.add("Jogadores: " + String.join(", ", players) + ".");
			}
			out.add(dangers.isEmpty() ? "Nenhum perigo por perto." : "Perigos: " + String.join("; ", dangers) + ".");
			if (ownerDirection != null) {
				out.add(String.format("A dona está a %.0f blocos, para o %s.", ownerDistance, ownerDirection));
			}
			out.add("Agora: " + activity + (order == null ? "" : " — pedido: " + order) + ".");
			if (base != null) {
				out.add(String.format("Base: %d, %d, %d (a %.0f blocos).", base.getX(), base.getY(), base.getZ(), Math.sqrt(base.distSqr(pos))));
			}
			if (!remembered.isEmpty() || hypotheses > 0) {
				out.add("Lembro de: " + String.join(", ", remembered.entrySet().stream().map(e -> e.getValue() + "× " + e.getKey()).toList())
					+ (hypotheses > 0 ? " e de " + hypotheses + " área(s) onde costuma ter recurso (hipóteses)" : "") + ".");
			}
			return out;
		}
	}

	private Perception() {
	}

	public static Snapshot of(CompanionEntity c) {
		ServerLevel level = (ServerLevel) c.level();
		BlockPos pos = c.blockPosition();
		String biome = level.getBiome(pos).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
		long time = Math.floorMod(level.getDefaultClockTime(), 24000L);

		// Inventário: os itens em maior quantidade e a melhor ferramenta de cada tipo (pela velocidade contra o bloco típico).
		Map<Item, Integer> counts = new LinkedHashMap<>();
		Map<String, ItemStack> best = new LinkedHashMap<>();
		List<ItemStack> all = new ArrayList<>(c.inventory().snapshot());
		all.add(c.mainHandItem());
		for (ItemStack s : all) {
			if (s.isEmpty()) {
				continue;
			}
			counts.merge(s.getItem(), s.getCount(), Integer::sum);
			bestTool(best, "picareta", s, ItemTags.PICKAXES, Blocks.STONE.defaultBlockState());
			bestTool(best, "machado", s, ItemTags.AXES, Blocks.OAK_LOG.defaultBlockState());
			bestTool(best, "pá", s, ItemTags.SHOVELS, Blocks.DIRT.defaultBlockState());
			if (s.is(ItemTags.SWORDS) && !best.containsKey("espada")) {
				best.put("espada", s);
			}
		}
		Map<String, Integer> inventory = new LinkedHashMap<>();
		counts.entrySet().stream().sorted(Map.Entry.<Item, Integer>comparingByValue().reversed()).limit(8)
			.forEach(e -> inventory.put(new ItemStack(e.getKey()).getHoverName().getString(), e.getValue()));
		Map<String, String> bestTools = new LinkedHashMap<>();
		best.forEach((k, v) -> bestTools.put(k, v.getHoverName().getString()));

		// Blocos de interesse e perigos na área.
		Map<String, Seen> blocks = new LinkedHashMap<>();
		List<String> dangers = new ArrayList<>();
		double nearestLava = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(pos.offset(-BLOCK_RADIUS, -8, -BLOCK_RADIUS), pos.offset(BLOCK_RADIUS, 8, BLOCK_RADIUS))) {
			if (!level.hasChunkAt(p)) {
				continue;
			}
			BlockState s = level.getBlockState(p);
			if (s.isAir()) {
				continue;
			}
			double d = Math.sqrt(p.distSqr(pos));
			if (s.getFluidState().is(FluidTags.LAVA)) {
				nearestLava = Math.min(nearestLava, d);
				see(blocks, "lava", p, d);
			} else if (s.getFluidState().is(FluidTags.WATER) && s.getFluidState().isSource()) {
				see(blocks, "água", p, d);
			} else if (s.is(ConventionalBlockTags.ORES) || s.is(BlockTags.LOGS) || s.is(Blocks.CHEST) || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE)
				|| s.is(BlockTags.BEDS)) {
				see(blocks, s.getBlock().getName().getString(), p, d);
			} else if (s.is(BlockTags.FIRE) && d <= 4) {
				dangers.add("fogo a " + (int) d + " blocos");
			}
		}
		if (nearestLava <= 4) {
			dangers.add("lava a " + (int) nearestLava + " blocos");
		}
		if (c.isInPowderSnow) {
			dangers.add("dentro de neve fofa (congelamento)");
		}
		if (c.isEyeInFluid(FluidTags.WATER)) {
			dangers.add("debaixo d'água (ar " + c.getAirSupply() + "/" + c.getMaxAirSupply() + ")");
		}
		if (c.isOnFire()) {
			dangers.add("pegando fogo");
		}

		// Criaturas.
		Map<String, double[]> hostile = new LinkedHashMap<>();
		Map<String, double[]> animal = new LinkedHashMap<>();
		List<String> players = new ArrayList<>();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(ENTITY_RADIUS), e -> e != c && e.isAlive())) {
			double d = e.distanceTo(c);
			if (e instanceof Player p) {
				players.add(p.getName().getString() + " (a " + (int) d + ")");
			} else if (e instanceof Enemy && !c.isAlliedTo(e)) {
				merge(hostile, e.getType().getDescription().getString(), d);
			} else if (e instanceof Animal) {
				merge(animal, e.getType().getDescription().getString(), d);
			}
		}
		List<Creature> hostiles = creatures(hostile);
		List<Creature> animals = creatures(animal);
		if (!hostiles.isEmpty() && hostiles.getFirst().distance() <= 8) {
			dangers.add(hostiles.getFirst().name() + " a " + (int) hostiles.getFirst().distance() + " blocos");
		}

		ServerPlayer owner = c.ownerInLevel();
		double ownerDistance = owner == null ? -1 : owner.distanceTo(c);
		String ownerDirection = owner == null ? null : direction(owner.getX() - c.getX(), owner.getZ() - c.getZ());

		Order order = c.orders().active();
		String orderText = order == null ? null : order.requested() + "× " + order.targetName().getString() + " (" + order.status() + ", entregue "
			+ order.progress().delivered + ")";
		String activity = c.stateDetail().getString().isEmpty() ? c.companionState().name() : c.stateDetail().getString();

		WorldMemory memory = WorldMemory.get(level.getServer());
		BlockPos base = memory.base(c.getUUID()).filter(p -> p.dimension().equals(level.dimension())).map(WorldMemory.Place::pos).orElse(null);
		Map<String, Integer> remembered = new LinkedHashMap<>();
		memory.sightingSummary(level.dimension()).entrySet().stream().sorted(Map.Entry.<net.minecraft.resources.Identifier, Integer>comparingByValue().reversed())
			.limit(6).forEach(e -> remembered.put(BuiltInRegistries.BLOCK.getValue(e.getKey()).getName().getString(), e.getValue()));
		int hypotheses = (int) memory.regions(level.dimension()).stream().filter(WorldMemory.Region::usable).count();

		return new Snapshot(pos, level.dimension().identifier().getPath(), biome, time, DemonMode.isNight(level), level.isRaining(), level.isThundering(),
			c.getHealth(), c.getMaxHealth(), c.needs().hunger(), c.needs().fatigue(), c.getArmorValue(), c.mainHandItem().isEmpty() ? "nada"
				: c.mainHandItem().getHoverName().getString(), c.inventory().emptySlots(), inventory, bestTools, blocks, hostiles, animals, players, dangers,
			ownerDistance, ownerDirection, activity, orderText, base, remembered, hypotheses);
	}

	private static void bestTool(Map<String, ItemStack> best, String kind, ItemStack s, net.minecraft.tags.TagKey<Item> tag, BlockState typical) {
		if (!s.is(tag)) {
			return;
		}
		ItemStack cur = best.get(kind);
		if (cur == null || s.getDestroySpeed(typical) > cur.getDestroySpeed(typical)) {
			best.put(kind, s);
		}
	}

	private static void see(Map<String, Seen> blocks, String name, BlockPos p, double d) {
		Seen s = blocks.get(name);
		if (s == null) {
			blocks.put(name, new Seen(name, p.immutable(), d, 1));
		} else {
			blocks.put(name, d < s.distance() ? new Seen(name, p.immutable(), d, s.count() + 1) : new Seen(name, s.nearest(), s.distance(), s.count() + 1));
		}
	}

	private static void merge(Map<String, double[]> map, String name, double d) {
		double[] v = map.computeIfAbsent(name, k -> new double[] {Double.MAX_VALUE, 0});
		v[0] = Math.min(v[0], d);
		v[1]++;
	}

	private static List<Creature> creatures(Map<String, double[]> map) {
		List<Creature> out = new ArrayList<>();
		map.forEach((name, v) -> out.add(new Creature(name, v[0], (int) v[1])));
		out.sort(Comparator.comparingDouble(Creature::distance));
		return out;
	}

	/** Direção cardeal (norte = -Z) de um deslocamento. */
	static String direction(double dx, double dz) {
		Direction d = Direction.getApproximateNearest(dx, 0, dz);
		return switch (d) {
			case NORTH -> "norte";
			case SOUTH -> "sul";
			case EAST -> "leste";
			case WEST -> "oeste";
			default -> d.getName();
		};
	}
}
