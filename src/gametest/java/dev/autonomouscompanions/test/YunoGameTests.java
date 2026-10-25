package dev.autonomouscompanions.test;

import static dev.autonomouscompanions.test.TestSupport.FLOOR;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.registry.ModEntities;
import dev.autonomouscompanions.yuno.YunoData;
import dev.autonomouscompanions.yuno.YunoEntity;
import dev.autonomouscompanions.yuno.YunoManager;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Yuno: observa escondida, some quando é vista e deixa uma maçã dourada por dia num baú da jogadora. */
public class YunoGameTests {
	private static final String DAY = "autonomouscompanions-test:ac_day";
	private static final String ARENA = "autonomouscompanions-test:arena";
	private static final String CORRIDOR = "autonomouscompanions-test:corridor";

	/** Jogadora olhando na direção {@code yaw} (graus do jogo: -90 = leste/+x, 90 = oeste/-x). */
	private static void face(GameTestHelper h, ServerPlayer p, BlockPos rel, float yaw) {
		BlockPos abs = h.absolutePos(rel);
		p.teleportTo(h.getLevel(), abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, Set.<Relative>of(), yaw, 0.0F, false);
		p.setYHeadRot(yaw);
	}

	private static YunoEntity yuno(GameTestHelper h, ServerPlayer owner, BlockPos rel) {
		YunoData data = YunoData.get(h.getLevel().getServer());
		data.getOrCreate(owner.getUUID());
		data.setNextGift(owner.getUUID(), h.getLevel().getServer().overworld().getGameTime() + YunoManager.DAY);
		YunoEntity y = h.spawn(ModEntities.YUNO, rel, EntitySpawnReason.EVENT);
		y.setOwner(owner.getUUID());
		data.setYuno(owner.getUUID(), y.getUUID());
		y.appearAt(Vec3.atBottomCenterOf(h.absolutePos(rel)), h.getLevel().getGameTime() + 100_000);
		return y;
	}

	private static void cleanup(GameTestHelper h, ServerPlayer owner) {
		h.getEntities(ModEntities.YUNO).forEach(e -> e.discard());
		TestSupport.cleanup(h, owner);
	}

	/** Quando a jogadora olha para ela, um breve vislumbre... e ela some (fica invisível, longe da mira). */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 200)
	public void vanishesWhenLookedAt(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 12), GameType.SURVIVAL);
		face(h, owner, new BlockPos(2, FLOOR, 12), -90.0F);
		YunoEntity y = yuno(h, owner, new BlockPos(20, FLOOR, 12));
		h.assertTrue(y.isLookedAtBy(owner), "a jogadora deveria estar olhando para ela");
		h.succeedWhen(() -> {
			h.assertTrue(y.mode() == YunoEntity.Mode.VANISHED && y.isInvisible() && !y.isPickable(), "não sumiu: " + y.mode());
			cleanup(h, owner);
		});
	}

	/** De costas, a jogadora não a vê: ela continua lá, imóvel, observando. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 200)
	public void keepsWatchingWhileUnseen(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 12), GameType.SURVIVAL);
		face(h, owner, new BlockPos(2, FLOOR, 12), 90.0F);
		YunoEntity y = yuno(h, owner, new BlockPos(20, FLOOR, 12));
		Vec3 start = y.position();
		h.runAfterDelay(80, () -> {
			h.assertTrue(y.mode() == YunoEntity.Mode.WATCHING && !y.isInvisible(), "sumiu sem ser vista: " + y.mode());
			h.assertTrue(y.position().distanceTo(start) < 0.5, "saiu do lugar");
			cleanup(h, owner);
			h.succeed();
		});
	}

	/** Se a jogadora chega perto (mesmo de costas), ela some. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 200)
	public void vanishesWhenApproached(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 12), GameType.SURVIVAL);
		face(h, owner, new BlockPos(2, FLOOR, 12), 90.0F);
		YunoEntity y = yuno(h, owner, new BlockPos(20, FLOOR, 12));
		h.runAfterDelay(20, () -> face(h, owner, new BlockPos(16, FLOOR, 12), 90.0F));
		h.succeedWhen(() -> {
			h.assertTrue(y.mode() == YunoEntity.Mode.VANISHED, "não sumiu com a jogadora perto");
			cleanup(h, owner);
		});
	}

	/** Não é ameaça e não se machuca: golpe da jogadora não tira vida (ela só some); as companheiras não a atacam. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void harmlessAndUntouchable(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(2, FLOOR, 12), GameType.SURVIVAL);
		face(h, owner, new BlockPos(2, FLOOR, 12), 90.0F);
		CompanionEntity c = TestSupport.companion(h, owner, new BlockPos(4, FLOOR, 12), "Lia");
		c.setVariant(dev.autonomouscompanions.entity.CompanionVariant.RUBI);
		YunoEntity y = yuno(h, owner, new BlockPos(20, FLOOR, 12));
		h.assertTrue(c.isAlliedTo(y), "a companheira não a considera aliada");
		h.assertTrue(!c.demon().isEligible(y), "seria alvo do Modo Demônio");
		h.assertTrue(!y.hurtServer(h.getLevel(), y.damageSources().playerAttack(owner), 10.0F), "levou dano");
		h.assertTrue(y.getHealth() == y.getMaxHealth() && y.mode() == YunoEntity.Mode.VANISHED, "não sumiu ao ser atingida");
		cleanup(h, owner);
		h.succeed();
	}

	/** Os esconderijos escolhidos ficam sempre fora do campo de visão no momento em que ela surge, mas visíveis. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 100)
	public void hidingSpotsAreOutOfViewButVisible(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(12, FLOOR, 12), GameType.SURVIVAL);
		face(h, owner, new BlockPos(12, FLOOR, 12), -90.0F);
		YunoEntity y = yuno(h, owner, new BlockPos(20, FLOOR, 20));
		int found = 0;
		for (int i = 0; i < 20; i++) {
			BlockPos p = y.pickSpot(h.getLevel(), owner, i % 2 == 0);
			if (p == null) {
				continue;
			}
			found++;
			Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + 1.6, p.getZ() + 0.5);
			h.assertTrue(!YunoEntity.inViewCone(owner, eye, 75), "esconderijo dentro do campo de visão: " + p.toShortString());
			h.assertTrue(dev.autonomouscompanions.nav.SafeSpot.isSafeStand(h.getLevel(), p, null), "esconderijo inseguro");
		}
		h.assertTrue(found > 0, "nenhum esconderijo encontrado");
		cleanup(h, owner);
		h.succeed();
	}

	/**
	 * Presente: com a jogadora longe e de costas, a Yuno vai ao baú e deixa uma maçã dourada comum (não encantada); só
	 * uma por dia; ao abrir o baú, a surpresa é revelada. Com a jogadora perto do baú, ela não entrega.
	 */
	@GameTest(environment = DAY, structure = CORRIDOR, maxTicks = 1200)
	public void leavesOneGoldenApplePerDayInTheChest(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(63, FLOOR, 4), GameType.SURVIVAL);
		BlockPos chestRel = new BlockPos(66, FLOOR, 4);
		h.setBlock(chestRel, Blocks.CHEST);
		BlockPos chestPos = h.absolutePos(chestRel);
		YunoManager.introduce(owner);
		YunoData data = YunoData.get(h.getLevel().getServer());
		YunoManager.onChestOpened(owner, chestPos);
		h.assertTrue(data.entry(owner.getUUID()).chests().size() == 1, "o baú não foi lembrado");
		ChestBlockEntity chest = (ChestBlockEntity) h.getLevel().getBlockEntity(chestPos);
		long[] marks = new long[1];
		h.startSequence()
			.thenExecute(() -> {
				// Um dia se passou, mas a jogadora está colada no baú: nada de presente.
				face(h, owner, new BlockPos(63, FLOOR, 4), 90.0F);
				data.setNextGift(owner.getUUID(), h.getLevel().getServer().overworld().getGameTime());
			})
			.thenIdle(120)
			.thenExecute(() -> {
				h.assertValueEqual(chest.countItem(Items.GOLDEN_APPLE), 0, "entregou com a jogadora perto");
				h.assertValueEqual(data.entry(owner.getUUID()).owed(), 1, "presente do dia");
				// A jogadora vai embora (de costas para o baú).
				face(h, owner, new BlockPos(3, FLOOR, 4), 90.0F);
			})
			.thenWaitUntil(() -> h.assertValueEqual(chest.countItem(Items.GOLDEN_APPLE), 1, "maçã dourada no baú"))
			.thenExecute(() -> {
				ItemStack apple = ItemStack.EMPTY;
				for (int i = 0; i < chest.getContainerSize(); i++) {
					if (chest.getItem(i).is(Items.GOLDEN_APPLE)) {
						apple = chest.getItem(i);
					}
				}
				h.assertTrue(!apple.has(DataComponents.CUSTOM_NAME), "a maçã deveria ser comum");
				h.assertValueEqual(chest.countItem(Items.ENCHANTED_GOLDEN_APPLE), 0, "maçã encantada");
				h.assertValueEqual(data.entry(owner.getUUID()).owed(), 0, "presente devido após a entrega");
				h.assertTrue(data.entry(owner.getUUID()).surprises().size() == 1, "surpresa não registrada");
				marks[0] = data.entry(owner.getUUID()).given();
			})
			.thenIdle(200)
			.thenExecute(() -> {
				// Antes de completar outro dia, nada mais.
				h.assertValueEqual(chest.countItem(Items.GOLDEN_APPLE), 1, "mais de uma maçã no mesmo dia");
				// A jogadora volta e abre o baú: a surpresa é revelada uma vez.
				face(h, owner, new BlockPos(63, FLOOR, 4), -90.0F);
				YunoManager.onChestOpened(owner, chestPos);
				h.assertTrue(data.entry(owner.getUUID()).surprises().isEmpty(), "a surpresa não foi revelada");
				// Dia seguinte: outra maçã (empilha com a primeira) quando ela se afastar.
				face(h, owner, new BlockPos(3, FLOOR, 4), 90.0F);
				data.setNextGift(owner.getUUID(), h.getLevel().getServer().overworld().getGameTime());
			})
			.thenWaitUntil(() -> h.assertValueEqual(chest.countItem(Items.GOLDEN_APPLE), 2, "maçã do segundo dia"))
			.thenExecute(() -> {
				h.assertValueEqual(data.entry(owner.getUUID()).given(), (int) marks[0] + 1, "contador de presentes");
				cleanup(h, owner);
			})
			.thenSucceed();
	}

	/** Uma única Yuno por jogadora: apresentar de novo não cria outra, e uma cópia antiga se desfaz sozinha. */
	@GameTest(environment = DAY, structure = ARENA, maxTicks = 300)
	public void onlyOneYunoPerPlayer(GameTestHelper h) {
		ServerPlayer owner = TestSupport.owner(h, new BlockPos(12, FLOOR, 12), GameType.SURVIVAL);
		YunoManager.introduce(owner);
		YunoManager.introduce(owner);
		YunoData data = YunoData.get(h.getLevel().getServer());
		var id = data.entry(owner.getUUID()).yunoId();
		h.assertTrue(id != null, "nenhuma Yuno registrada");
		YunoEntity stale = h.spawn(ModEntities.YUNO, new BlockPos(4, FLOOR, 4), EntitySpawnReason.EVENT);
		stale.setOwner(owner.getUUID());
		h.succeedWhen(() -> {
			h.assertTrue(stale.isRemoved(), "a cópia antiga continua no mundo");
			long mine = h.getLevel().getEntities(ModEntities.YUNO, e -> owner.getUUID().equals(e.owner())).size();
			h.assertValueEqual((int) mine, 1, "Yunos desta jogadora");
			h.assertTrue(data.entry(owner.getUUID()).yunoId().equals(id), "a Yuno registrada mudou sem motivo");
			cleanup(h, owner);
		});
	}
}
