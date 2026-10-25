package dev.autonomouscompanions.task.exec;

import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.crafting.CostModel;
import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.inventory.CompanionInventory;
import dev.autonomouscompanions.mining.BlockBreaker;
import dev.autonomouscompanions.task.Order;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/** Contexto compartilhado pelas etapas de uma tarefa (transitório; reconstruído ao recarregar). */
public final class TaskContext {
	private final CompanionEntity companion;
	private final CompanionBrain brain;
	private final BlockBreaker breaker = new BlockBreaker();
	private final @Nullable Order order;
	private ServerLevel level;
	private CostModel costModel;
	private long costModelTime;
	/** Estações (bancada/fornalha) colocadas pela própria companheira, para recolher depois. */
	private final List<BlockPos> ownStations;
	/** Alvos considerados inalcançáveis nesta tarefa. */
	private final Set<BlockPos> unreachable = new HashSet<>();

	public TaskContext(CompanionEntity companion, CompanionBrain brain, @Nullable Order order, List<BlockPos> ownStations) {
		this.companion = companion;
		this.brain = brain;
		this.order = order;
		this.level = (ServerLevel) companion.level();
		this.ownStations = ownStations;
	}

	public void refresh(ServerLevel level) {
		this.level = level;
	}

	/** Limite máximo de alcance (blocos) para trabalho autônomo perto do dono; 0 = sem limite extra. */
	private int rangeCap;

	public int rangeCap() {
		return rangeCap;
	}

	public TaskContext withRangeCap(int cap) {
		this.rangeCap = cap;
		return this;
	}

	/** Vezes que a busca foi ampliada (recuperação "procurar mais longe" das habilidades): cada vez dobra o alcance. */
	private int searchWidening;

	public int searchWidening() {
		return searchWidening;
	}

	public void widenSearch() {
		searchWidening++;
	}

	public CompanionEntity companion() {
		return companion;
	}

	public CompanionBrain brain() {
		return brain;
	}

	public ServerLevel level() {
		return level;
	}

	public CompanionInventory inventory() {
		return companion.inventory();
	}

	public BlockBreaker breaker() {
		return breaker;
	}

	public @Nullable Order order() {
		return order;
	}

	public List<BlockPos> ownStations() {
		return ownStations;
	}

	public Set<BlockPos> unreachable() {
		return unreachable;
	}

	/** Modelo de custos com cache curto (o inventário muda ao longo da tarefa). */
	public CostModel costs() {
		long now = level.getGameTime();
		// O índice de obtenção precisa estar pronto antes de qualquer cálculo: um custo "impossível" calculado com o índice
		// ainda vazio ficava guardado e a ordem falhava (pedidos feitos logo depois de abrir o mundo).
		if (!dev.autonomouscompanions.mining.DropIndex.isReady()) {
			dev.autonomouscompanions.mining.DropIndex.ensureReady(level.getServer());
			costModel = null;
		}
		if (costModel == null || now - costModelTime > 100) {
			costModel = new CostModel(level, companion);
			costModelTime = now;
		}
		return costModel;
	}

	public void invalidateCosts() {
		costModel = null;
	}

	public long now() {
		return level.getGameTime();
	}

	public List<BlockPos> stationsCopy() {
		return new ArrayList<>(ownStations);
	}
}
