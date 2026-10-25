package dev.autonomouscompanions.client.render;

import dev.autonomouscompanions.demon.DemonFx;
import dev.autonomouscompanions.demon.DemonState;
import dev.autonomouscompanions.entity.CompanionEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * Aura do Modo Demônio, desenhada no cliente a partir do estado sincronizado (cada jogador vê o mesmo estado; nada
 * passa pela rede a cada tick). Quatro camadas:
 * <ol>
 *   <li>aura principal: chama carmesim colada à silhueta, núcleo claro perto do corpo e bordas escuras irregulares,
 *       subindo do chão para o cabelo;</li>
 *   <li>energia circulante: três correntes em espiral subindo pelo corpo e contas de energia orbitando em curvas;</li>
 *   <li>partículas demoníacas: brasas, esporos carmesim, fragmentos escuros, cinzas e fios de energia que se rompem;</li>
 *   <li>pressão sobrenatural: pulsos de luz, ondas no chão empurrando as partículas para fora e névoa escura nos pés.</li>
 * </ol>
 * A quantidade cai com a distância da câmera (e cada camada tem limite), para não pesar com várias no mundo.
 */
public final class DemonClientEffects {
	private DemonClientEffects() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(DemonClientEffects::tick);
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.isPaused() || mc.getCameraEntity() == null) {
			return;
		}
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof CompanionEntity c && c.demonState().engaged()) {
				double dist = c.distanceTo(mc.getCameraEntity());
				if (dist < 96) {
					emit(level, c, dist);
				}
			}
		}
	}

	private static void emit(ClientLevel level, CompanionEntity c, double dist) {
		DemonState s = c.demonState();
		float age = c.demonAge(0);
		float k = switch (s) {
			case TRANSFORMING -> smooth(age, 4, 45);
			case ACTIVE -> 0.85F + 0.15F * Mth.sin(age * 0.25F);
			case EXHAUSTED -> 1.0F - smooth(age, 0, 40);
			case SLEEPING -> age < 120 ? 0.15F * (1.0F - age / 120.0F) : 0.0F;
			default -> 0.0F;
		};
		if (k <= 0.01F) {
			return;
		}
		float lod = dist < 24 ? 1.0F : dist < 48 ? 0.5F : 0.25F;
		RandomSource r = c.getRandom();
		double x = c.getX();
		double y = c.getY();
		double z = c.getZ();
		boolean sleeping = s == DemonState.SLEEPING;
		if (sleeping) {
			// Restos de energia nos primeiros segundos de sono.
			if (r.nextFloat() < k * 2) {
				level.addParticle(ParticleTypes.CRIMSON_SPORE, x + (r.nextDouble() - 0.5), y + 0.3, z + (r.nextDouble() - 0.5), 0, 0.02, 0);
			}
			return;
		}

		// ---- Camada 1: aura principal (silhueta) ----
		int n1 = Math.round(9 * k * lod);
		for (int i = 0; i < n1; i++) {
			double h = r.nextDouble() * 1.95;
			double body = h > 1.45 ? 0.3 : h > 0.72 ? 0.36 : 0.22; // cabeça/cabelo, tronco e braços, pernas
			double rad = body + r.nextDouble() * 0.22;
			double a = r.nextDouble() * Math.PI * 2;
			double px = x + Math.cos(a) * rad;
			double pz = z + Math.sin(a) * rad;
			double up = 0.03 + r.nextDouble() * 0.06;
			boolean core = rad < body + 0.07;
			ParticleOptions p = core ? new DustParticleOptions(DemonFx.CORE, 0.5F + r.nextFloat() * 0.4F)
				: r.nextFloat() < 0.35F ? new DustColorTransitionOptions(DemonFx.SCARLET, DemonFx.DARK, 0.9F + r.nextFloat() * 0.7F)
				: new DustParticleOptions(r.nextBoolean() ? DemonFx.CRIMSON : DemonFx.SCARLET, 0.7F + r.nextFloat() * 0.8F);
			level.addParticle(p, px, y + h, pz, Math.cos(a) * 0.01, up, Math.sin(a) * 0.01);
		}
		// Ondulações que sobem do chão até o cabelo.
		if (r.nextFloat() < 0.6F * k * lod) {
			double a = r.nextDouble() * Math.PI * 2;
			level.addParticle(new DustColorTransitionOptions(DemonFx.CRIMSON, DemonFx.SCARLET, 1.2F), x + Math.cos(a) * 0.45, y + 0.05,
				z + Math.sin(a) * 0.45, 0, 0.12 + r.nextDouble() * 0.06, 0);
		}

		// ---- Camada 2: energia circulante ----
		if (lod >= 0.5F) {
			for (int strand = 0; strand < 3; strand++) {
				float phase = age * 0.35F + strand * 2.094F;
				double h = ((age * 0.05F + strand * 0.33F) % 1.0F) * 2.0;
				double rad = 0.42 + 0.06 * Mth.sin(age * 0.3F + strand);
				level.addParticle(new DustParticleOptions(strand == 0 ? DemonFx.CORE : DemonFx.SCARLET, 0.6F), x + Mth.cos(phase) * rad, y + h,
					z + Mth.sin(phase) * rad, 0, 0.01, 0);
			}
			for (int orb = 0; orb < 2; orb++) {
				// Contas orbitando em trajetória curva (inclinada), que às vezes somem.
				if (r.nextFloat() < 0.2F) {
					continue;
				}
				float ang = age * (0.18F + orb * 0.07F) + orb * 3.1F;
				double rad = 1.05 + 0.15 * Mth.sin(age * 0.11F + orb);
				double h = 0.9 + 0.55 * Mth.sin(ang * 0.5F + orb);
				level.addParticle(new DustParticleOptions(DemonFx.SCARLET, 0.8F), x + Mth.cos(ang) * rad, y + h, z + Mth.sin(ang) * rad,
					-Mth.sin(ang) * 0.02, 0, Mth.cos(ang) * 0.02);
			}
		}

		// ---- Camada 3: partículas demoníacas ----
		if (r.nextFloat() < 0.7F * k) {
			level.addParticle(ParticleTypes.CRIMSON_SPORE, x + (r.nextDouble() - 0.5) * 1.6, y + r.nextDouble() * 2.2, z + (r.nextDouble() - 0.5) * 1.6,
				0, 0.02, 0);
		}
		if (r.nextFloat() < 0.05F * k * lod) {
			level.addParticle(ParticleTypes.LAVA, x + (r.nextDouble() - 0.5) * 0.6, y + 0.6 + r.nextDouble(), z + (r.nextDouble() - 0.5) * 0.6, 0, 0, 0);
		}
		if (r.nextFloat() < 0.35F * k * lod) {
			level.addParticle(ParticleTypes.SMOKE, x + (r.nextDouble() - 0.5) * 0.9, y + r.nextDouble() * 1.8, z + (r.nextDouble() - 0.5) * 0.9, 0,
				0.03, 0);
		}
		if (r.nextFloat() < 0.5F * k * lod) {
			level.addParticle(ParticleTypes.ASH, x + (r.nextDouble() - 0.5) * 2.5, y + r.nextDouble() * 2.5, z + (r.nextDouble() - 0.5) * 2.5, 0, 0, 0);
		}
		if (r.nextFloat() < 0.12F * k * lod) {
			// Fio de energia que se rompe no ar: três pontos em linha que se desfazem rápido.
			double a = r.nextDouble() * Math.PI * 2;
			double h = 0.3 + r.nextDouble() * 1.6;
			for (int j = 0; j < 3; j++) {
				double d = 0.45 + j * 0.18;
				level.addParticle(new DustColorTransitionOptions(DemonFx.CORE, DemonFx.DARK, 0.45F), x + Math.cos(a) * d, y + h + j * 0.08,
					z + Math.sin(a) * d, 0, 0, 0);
			}
		}

		// ---- Camada 4: pressão sobrenatural ----
		if (s == DemonState.ACTIVE || s == DemonState.TRANSFORMING && age > 30) {
			int t = (int) age;
			if (t % 30 == 0) {
				// Pulso: onda circular no chão empurrando para fora + clarão no peito.
				for (int i = 0; i < Math.round(28 * lod); i++) {
					double a = i * Math.PI * 2 / (28 * lod);
					level.addParticle(new DustParticleOptions(DemonFx.CRIMSON, 1.3F), x + Math.cos(a) * 0.5, groundY(c), z + Math.sin(a) * 0.5,
						Math.cos(a) * 0.35, 0.01, Math.sin(a) * 0.35);
				}
				for (int i = 0; i < 5; i++) {
					level.addParticle(new DustParticleOptions(DemonFx.CORE, 1.8F), x + (r.nextDouble() - 0.5) * 0.3, y + 1.1 + (r.nextDouble() - 0.5) * 0.3,
						z + (r.nextDouble() - 0.5) * 0.3, 0, 0, 0);
				}
			}
			if (t % 10 == 0) {
				// Pulso de energia sob os pés enquanto flutua.
				for (int i = 0; i < 10; i++) {
					double a = i * Math.PI / 5;
					level.addParticle(new DustParticleOptions(DemonFx.SCARLET, 0.7F), x + Math.cos(a) * 0.3, y - 0.05, z + Math.sin(a) * 0.3,
						Math.cos(a) * 0.08, -0.02, Math.sin(a) * 0.08);
				}
			}
			if (r.nextFloat() < 0.25F * lod) {
				level.addParticle(ParticleTypes.LARGE_SMOKE, x + (r.nextDouble() - 0.5) * 1.2, groundY(c) + 0.1, z + (r.nextDouble() - 0.5) * 1.2,
					(r.nextDouble() - 0.5) * 0.05, 0.005, (r.nextDouble() - 0.5) * 0.05);
			}
			if (r.nextFloat() < 0.6F * lod) {
				// Vento da tempestade sobrenatural: cinzas claras cruzando ao redor dela.
				double a = r.nextDouble() * Math.PI * 2;
				double d = 2 + r.nextDouble() * 5;
				level.addParticle(ParticleTypes.WHITE_ASH, x + Math.cos(a) * d, y + r.nextDouble() * 3, z + Math.sin(a) * d, 0.25, 0, 0.08);
			}
		}
	}

	/** Altura do chão sob ela (a aura no chão fica no chão mesmo com ela flutuando). */
	private static double groundY(CompanionEntity c) {
		for (int dy = 0; dy < 4; dy++) {
			var p = c.blockPosition().below(dy);
			if (!c.level().getBlockState(p.below()).getCollisionShape(c.level(), p.below()).isEmpty()) {
				return p.getY() + 0.05;
			}
		}
		return c.getY();
	}

	private static float smooth(float a, float from, float to) {
		float t = Mth.clamp((a - from) / (to - from), 0.0F, 1.0F);
		return t * t * (3 - 2 * t);
	}
}
