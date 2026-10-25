package dev.autonomouscompanions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.autonomouscompanions.build.Blueprint;
import dev.autonomouscompanions.build.BlueprintGenerator;
import dev.autonomouscompanions.build.HouseSize;
import dev.autonomouscompanions.build.HouseStyle;
import dev.autonomouscompanions.build.WoodPalette;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Valida as plantas dos 6 estilos em todos os tamanhos, variações e madeiras. */
class BlueprintTest {
	private static final long[] SEEDS = {0, 1, 42, 1234, 65535};

	@BeforeAll
	static void boot() {
		McBootstrap.init();
	}

	@Test
	void everyStyleSizeAndSeedProducesAValidHouse() {
		for (HouseStyle style : HouseStyle.values()) {
			for (HouseSize size : HouseSize.values()) {
				for (long seed : SEEDS) {
					for (WoodPalette wood : WoodPalette.ALL) {
						Blueprint bp = BlueprintGenerator.generate(style, size, seed, wood);
						String label = style + "/" + size + "/" + seed + "/" + wood.id();
						validate(bp, label);
					}
				}
			}
		}
	}

	private static void validate(Blueprint bp, String label) {
		assertFalse(bp.placements().isEmpty(), label + ": planta vazia");
		Set<BlockPos> seen = new HashSet<>();
		int doorLower = 0;
		int doorUpper = 0;
		int bedFoot = 0;
		int bedHead = 0;
		int lights = 0;
		for (Blueprint.Placement p : bp.placements()) {
			assertTrue(seen.add(p.local()), label + ": posição repetida " + p.local());
			assertFalse(p.state().isAir(), label + ": ar listado como bloco a colocar");
			if (Blueprint.itemCount(p.state()) > 0) {
				assertNotNull(Blueprint.itemFor(p.state()), label + ": bloco sem item correspondente " + p.state());
			} else {
				boolean secondHalf = p.state().getBlock() instanceof DoorBlock || p.state().getBlock() instanceof AbstractBedBlock;
				assertTrue(secondHalf, label + ": bloco sem item que não é metade de porta/cama " + p.state());
			}
			assertTrue(p.local().getX() >= bp.minX() && p.local().getX() <= bp.maxX(), label);
			assertTrue(p.local().getY() >= bp.minY() && p.local().getY() <= bp.maxY(), label);
			assertTrue(p.local().getZ() >= bp.minZ() && p.local().getZ() <= bp.maxZ(), label);
			if (p.state().getBlock() instanceof DoorBlock) {
				if (p.state().getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
					doorLower++;
				} else {
					doorUpper++;
				}
			}
			if (p.state().getBlock() instanceof AbstractBedBlock) {
				if (p.state().getValue(AbstractBedBlock.PART) == BedPart.FOOT) {
					bedFoot++;
				} else {
					bedHead++;
				}
			}
			if (p.state().getLightEmission() >= 10) {
				lights++;
			}
		}
		assertTrue(doorLower >= 1 && doorLower == doorUpper, label + ": porta incompleta (" + doorLower + "/" + doorUpper + ")");
		// Geometria: cada porta tem a metade de cima logo acima e passagem livre na frente e atrás; cada cama tem a
		// cabeceira na direção certa.
		java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> at = new java.util.HashMap<>();
		for (Blueprint.Placement p : bp.placements()) {
			at.put(p.local(), p.state());
		}
		for (Blueprint.Placement p : bp.placements()) {
			var s = p.state();
			if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				var up = at.get(p.local().above());
				assertTrue(up != null && up.getBlock() == s.getBlock() && up.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER, label + ": porta sem metade de cima em " + p.local());
				var facing = s.getValue(DoorBlock.FACING);
				for (var d : new net.minecraft.core.Direction[]{facing, facing.getOpposite()}) {
					for (int dy = 0; dy <= 1; dy++) {
						var q = at.get(p.local().relative(d).above(dy));
						assertTrue(q == null || q.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty(),
							label + ": passagem da porta bloqueada em " + p.local().relative(d).above(dy) + " por " + q);
					}
				}
			}
			// Lanternas penduradas precisam de um bloco acima; tochas de parede, de um bloco atrás.
			if (s.getBlock() instanceof net.minecraft.world.level.block.LanternBlock && s.getValue(net.minecraft.world.level.block.LanternBlock.HANGING)) {
				assertTrue(at.containsKey(p.local().above()), label + ": lanterna pendurada sem apoio em " + p.local());
			}
			if (s.getBlock() instanceof net.minecraft.world.level.block.WallTorchBlock) {
				var behind = p.local().relative(s.getValue(net.minecraft.world.level.block.WallTorchBlock.FACING).getOpposite());
				assertTrue(at.containsKey(behind), label + ": tocha de parede sem apoio em " + p.local());
			}
			if (s.getBlock() instanceof AbstractBedBlock && s.getValue(AbstractBedBlock.PART) == BedPart.FOOT) {
				var head = at.get(p.local().relative(s.getValue(AbstractBedBlock.FACING)));
				assertTrue(head != null && head.getBlock() == s.getBlock() && head.getValue(AbstractBedBlock.PART) == BedPart.HEAD, label + ": cama sem cabeceira em " + p.local());
			}
		}
		assertTrue(bedFoot >= 1 && bedFoot == bedHead, label + ": cama incompleta (" + bedFoot + "/" + bedHead + ")");
		assertTrue(lights >= 2, label + ": iluminação insuficiente (" + lights + ")");
		assertTrue(bp.height() >= 5, label + ": casa baixa demais (" + bp.height() + ")");
		assertTrue(bp.width() >= bp.size().ordinal() * 2 + 7, label + ": largura menor que o tamanho pedido");
		// Os espaços internos marcados como ar não podem coincidir com blocos.
		for (BlockPos air : bp.airCells()) {
			assertFalse(seen.contains(air), label + ": célula de ar ocupada " + air);
		}
		// A lista de materiais soma exatamente os itens de cada bloco.
		Map<Item, Integer> materials = bp.materials();
		int total = materials.values().stream().mapToInt(Integer::intValue).sum();
		int expected = bp.placements().stream().mapToInt(p -> Blueprint.itemCount(p.state())).sum();
		assertEquals(expected, total, label + ": lista de materiais inconsistente");
		assertEquals(bp.placements().size(), bp.blockCount(), label);
	}

	@Test
	void sameSeedIsDeterministicAndDifferentSeedsVary() {
		for (HouseStyle style : HouseStyle.values()) {
			Blueprint a = BlueprintGenerator.generate(style, HouseSize.MEDIUM, 77, WoodPalette.OAK);
			Blueprint b = BlueprintGenerator.generate(style, HouseSize.MEDIUM, 77, WoodPalette.OAK);
			assertEquals(a.placements(), b.placements(), style + ": mesma semente gerou plantas diferentes");
			boolean varies = false;
			for (long seed = 0; seed < 12 && !varies; seed++) {
				varies = !BlueprintGenerator.generate(style, HouseSize.MEDIUM, seed, WoodPalette.OAK).placements().equals(a.placements());
			}
			assertTrue(varies, style + ": a variação procedural não muda nada");
		}
	}

	@Test
	void stylesAreDistinct() {
		Set<Map<Item, Integer>> compositions = new HashSet<>();
		for (HouseStyle style : HouseStyle.values()) {
			compositions.add(BlueprintGenerator.generate(style, HouseSize.MEDIUM, 5, WoodPalette.SPRUCE).materials());
		}
		assertEquals(HouseStyle.values().length, compositions.size(), "dois estilos geraram exatamente os mesmos materiais");
	}

	@Test
	void rotationKeepsEveryBlockDistinct() {
		Blueprint bp = BlueprintGenerator.generate(HouseStyle.FARMHOUSE, HouseSize.LARGE, 9, WoodPalette.BIRCH);
		BlockPos origin = new BlockPos(100, 64, -50);
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			Set<BlockPos> world = new HashSet<>();
			for (Blueprint.Placement p : bp.placements()) {
				world.add(Blueprint.toWorld(origin, facing, p.local()));
			}
			assertEquals(bp.placements().size(), world.size(), facing + ": rotação sobrepôs blocos");
			var bounds = bp.worldBounds(origin, facing);
			for (BlockPos w : world) {
				assertTrue(bounds.inflate(0.01).contains(net.minecraft.world.phys.Vec3.atCenterOf(w)), facing + ": bloco fora dos limites " + w);
			}
		}
		assertNotEquals(Blueprint.toWorld(origin, Direction.NORTH, new BlockPos(3, 0, 1)), Blueprint.toWorld(origin, Direction.EAST, new BlockPos(3, 0, 1)));
	}

	@Test
	void printMaterialSummary() {
		for (HouseStyle style : HouseStyle.values()) {
			Blueprint bp = BlueprintGenerator.generate(style, HouseSize.SMALL, 1, WoodPalette.OAK);
			Map<Item, Integer> m = bp.materials();
			int total = m.values().stream().mapToInt(Integer::intValue).sum();
			System.out.println("[materiais] " + style + " SMALL: " + m.size() + " tipos, " + total + " itens, " + bp.blockCount() + " blocos, "
				+ bp.width() + "x" + bp.depth() + "x" + bp.height());
		}
	}
}
