package dev.autonomouscompanions.coop;

import dev.autonomouscompanions.build.BuildProject;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.persist.CompanionWorldData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Regras de cooperação entre companheiras do mesmo servidor:
 * <ul>
 *   <li>reserva de alvos: duas companheiras não mineram o mesmo bloco ao mesmo tempo;</li>
 *   <li>áreas protegidas: projetos de construção aprovados reservam sua área (ninguém minera ali, e dois projetos
 *       não se sobrepõem);</li>
 *   <li>pedidos de suprimento: uma construtora sem material publica um pedido; outra companheira livre do mesmo dono
 *       pode atendê-lo e entregar os itens diretamente a ela.</li>
 * </ul>
 */
public final class Cooperation {
	private static @Nullable Cooperation instance;
	private static @Nullable MinecraftServer instanceServer;

	private final MinecraftServer server;
	private final Map<Long, UUID> claims = new HashMap<>();
	private final List<SupplyRequest> requests = new ArrayList<>();

	/** Pedido de suprimento (transitório; recriado pela construtora se necessário). */
	public static final class SupplyRequest {
		final UUID id = UUID.randomUUID();
		final UUID requester;
		final UUID owner;
		final Item item;
		int remaining;
		@Nullable UUID takenBy;
		long created;

		SupplyRequest(UUID requester, UUID owner, Item item, int count, long created) {
			this.requester = requester;
			this.owner = owner;
			this.item = item;
			this.remaining = count;
			this.created = created;
		}

		public Item item() {
			return item;
		}

		public int remaining() {
			return remaining;
		}
	}

	private Cooperation(MinecraftServer server) {
		this.server = server;
	}

	public static synchronized Cooperation get(MinecraftServer server) {
		if (instance == null || instanceServer != server) {
			instance = new Cooperation(server);
			instanceServer = server;
		}
		return instance;
	}

	public static synchronized void reset() {
		instance = null;
		instanceServer = null;
	}

	// ------------------------------------------------------------------ alvos

	/** Área de trabalho de cada companheira (ex.: a árvore que está cortando), para as outras escolherem outro lugar. */
	private record WorkArea(BlockPos center, int radius) {
	}

	private final Map<UUID, WorkArea> workAreas = new java.util.HashMap<>();

	public synchronized void claimWorkArea(UUID companion, BlockPos center, int radius) {
		workAreas.put(companion, new WorkArea(center.immutable(), radius));
	}

	public synchronized void releaseWorkArea(UUID companion) {
		workAreas.remove(companion);
	}

	/** Verdadeiro se a posição está na área de trabalho de outra companheira (distância horizontal, até 12 de altura). */
	public synchronized boolean isInOtherWorkArea(UUID companion, BlockPos pos) {
		for (var e : workAreas.entrySet()) {
			if (e.getKey().equals(companion)) {
				continue;
			}
			WorkArea a = e.getValue();
			double dx = a.center().getX() - pos.getX();
			double dz = a.center().getZ() - pos.getZ();
			if (dx * dx + dz * dz <= a.radius() * a.radius() && Math.abs(a.center().getY() - pos.getY()) <= 12) {
				return true;
			}
		}
		return false;
	}

	public synchronized boolean claimTarget(UUID companion, BlockPos pos) {
		UUID current = claims.get(pos.asLong());
		if (current != null && !current.equals(companion)) {
			return false;
		}
		claims.put(pos.asLong(), companion);
		return true;
	}

	public synchronized boolean isTargetClaimedByOther(UUID companion, BlockPos pos) {
		UUID current = claims.get(pos.asLong());
		return current != null && !current.equals(companion);
	}

	public synchronized void releaseTarget(UUID companion, BlockPos pos) {
		claims.remove(pos.asLong(), companion);
	}

	public synchronized void releaseAll(UUID companion) {
		claims.values().removeIf(companion::equals);
		workAreas.remove(companion);
		for (SupplyRequest r : requests) {
			if (companion.equals(r.takenBy)) {
				r.takenBy = null;
			}
		}
	}

	// ------------------------------------------------------------------ áreas protegidas

	/**
	 * Áreas das obras ativas por dimensão, guardadas por até 1 s (ou até mudar a quantidade de projetos). Antes os limites
	 * de todas as obras eram recalculados para cada bloco candidato de cada busca: numa busca de troncos com 43 alvos isso
	 * custou 58 ms num único tick (medido no teste de fluidez).
	 */
	private final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, java.util.List<net.minecraft.world.phys.AABB>> protectedAreas =
		new java.util.HashMap<>();
	private long protectedTick = Long.MIN_VALUE;
	private int protectedCount = -1;

	private java.util.List<net.minecraft.world.phys.AABB> protectedAreas(ServerLevel level) {
		CompanionWorldData data = CompanionWorldData.get(server);
		long now = server.getTickCount();
		if (now - protectedTick >= 20 || data.projects().size() != protectedCount) {
			protectedAreas.clear();
			for (BuildProject p : data.projects()) {
				if (p.isActive()) {
					protectedAreas.computeIfAbsent(p.dimension(), k -> new java.util.ArrayList<>()).add(p.bounds().inflate(1));
				}
			}
			protectedTick = now;
			protectedCount = data.projects().size();
		}
		return protectedAreas.getOrDefault(level.dimension(), java.util.List.of());
	}

	public boolean isInsideProtectedArea(ServerLevel level, BlockPos pos) {
		for (net.minecraft.world.phys.AABB box : protectedAreas(level)) {
			if (box.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
				return true;
			}
		}
		return false;
	}

	public @Nullable BuildProject overlappingProject(ServerLevel level, net.minecraft.world.phys.AABB area, @Nullable UUID ignore) {
		for (BuildProject p : CompanionWorldData.get(server).projects()) {
			if (p.isActive() && !p.id().equals(ignore) && p.dimension().equals(level.dimension()) && p.bounds().inflate(2).intersects(area)) {
				return p;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ suprimentos

	public synchronized void postRequest(CompanionEntity requester, Item item, int count) {
		if (requester.ownerUUID() == null) {
			return;
		}
		for (SupplyRequest r : requests) {
			if (r.requester.equals(requester.getUUID()) && r.item == item) {
				r.remaining = count;
				return;
			}
		}
		requests.add(new SupplyRequest(requester.getUUID(), requester.ownerUUID(), item, count, server.getTickCount()));
	}

	public synchronized void cancelRequests(UUID requester) {
		requests.removeIf(r -> r.requester.equals(requester));
	}

	public synchronized @Nullable SupplyRequest takeRequest(CompanionEntity helper) {
		Iterator<SupplyRequest> it = requests.iterator();
		while (it.hasNext()) {
			SupplyRequest r = it.next();
			if (server.getTickCount() - r.created > 20 * 60 * 20) {
				it.remove();
				continue;
			}
			if (r.takenBy == null && r.remaining > 0 && !r.requester.equals(helper.getUUID()) && r.owner.equals(helper.ownerUUID())) {
				r.takenBy = helper.getUUID();
				return r;
			}
		}
		return null;
	}

	public synchronized boolean isOpen(SupplyRequest r) {
		return requests.contains(r) && r.remaining > 0;
	}

	public synchronized void abandon(SupplyRequest r, CompanionEntity helper) {
		if (helper.getUUID().equals(r.takenBy)) {
			r.takenBy = null;
		}
	}

	public synchronized boolean hasOpenRequests(UUID requester) {
		for (SupplyRequest r : requests) {
			if (r.requester.equals(requester) && r.remaining > 0) {
				return true;
			}
		}
		return false;
	}

	public @Nullable CompanionEntity requesterEntity(ServerLevel level, SupplyRequest r) {
		Entity e = level.getEntity(r.requester);
		return e instanceof CompanionEntity c && c.isAlive() ? c : null;
	}

	/** Transfere itens do ajudante para a solicitante (inserção confirmada antes da remoção). */
	public synchronized int handOver(CompanionEntity from, CompanionEntity to, SupplyRequest r) {
		int moved = 0;
		var inv = from.inventory();
		for (int i = inv.getContainerSize() - 1; i >= 0 && moved < r.remaining; i--) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !s.is(r.item) || inv.countFree(r.item) <= 0) {
				continue;
			}
			int k = Math.min(Math.min(s.getCount(), r.remaining - moved), inv.countFree(r.item));
			ItemStack offer = s.copyWithCount(k);
			ItemStack rest = to.inventory().insert(offer);
			int inserted = k - rest.getCount();
			s.shrink(inserted);
			if (s.isEmpty()) {
				inv.setItem(i, ItemStack.EMPTY);
			}
			moved += inserted;
			if (inserted < k) {
				break;
			}
		}
		r.remaining -= moved;
		r.takenBy = null;
		if (r.remaining <= 0) {
			requests.remove(r);
		}
		return moved;
	}
}
