package dev.autonomouscompanions.demon;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Efeitos de impacto do Modo Demônio enviados pelo servidor (todos os jogadores por perto veem o mesmo). São só
 * partículas e sons: nada de fogo, explosão real ou blocos alterados. A aura contínua é desenhada no cliente
 * ({@code DemonClientEffects}), para não sobrecarregar a rede.
 */
public final class DemonFx {
	public static final int CRIMSON = 0xB0001A;
	public static final int SCARLET = 0xFF1F1F;
	public static final int CORE = 0xFF7070;
	public static final int DARK = 0x2A0006;

	private DemonFx() {
	}

	public static DustParticleOptions dust(int color, float scale) {
		return new DustParticleOptions(color, scale);
	}

	public static DustColorTransitionOptions fade(int from, int to, float scale) {
		return new DustColorTransitionOptions(from, to, scale);
	}

	private static void send(ServerLevel level, ParticleOptions p, double x, double y, double z, int count, double spread, double speed) {
		level.sendParticles(p, x, y, z, count, spread, spread, spread, speed);
	}

	/** Uma partícula com velocidade definida (contagem 0: o deslocamento vira direção de movimento). */
	private static void moving(ServerLevel level, ParticleOptions p, double x, double y, double z, double vx, double vy, double vz, double speed) {
		level.sendParticles(p, x, y, z, 0, vx, vy, vz, speed);
	}

	/** Onda circular que se expande a partir de um ponto (no chão ou no ar). */
	public static void ring(ServerLevel level, Vec3 center, double radius, int points, ParticleOptions p, double outSpeed) {
		for (int i = 0; i < points; i++) {
			double a = i * Math.PI * 2 / points;
			double cx = Math.cos(a);
			double cz = Math.sin(a);
			moving(level, p, center.x + cx * radius, center.y, center.z + cz * radius, cx, 0.02, cz, outSpeed);
		}
	}

	/** Silhueta residual: pontos dentro do corpo, como uma "imagem" que se desfaz no lugar de onde ela saiu. */
	public static void afterimage(ServerLevel level, Entity e, Vec3 at) {
		RandomSource r = level.getRandom();
		for (int i = 0; i < 28; i++) {
			double h = r.nextDouble() * 1.8;
			double w = h > 1.35 ? 0.25 : h > 0.75 ? 0.3 : 0.18; // cabeça, tronco/braços, pernas
			double ox = (r.nextDouble() - 0.5) * 2 * w;
			double oz = (r.nextDouble() - 0.5) * 2 * w * 0.6;
			send(level, fade(SCARLET, DARK, 0.9f + r.nextFloat() * 0.6f), at.x + ox, at.y + h, at.z + oz, 1, 0.0, 0.0);
		}
	}

	/** Desaparecimento: explosão compacta vermelha e preta, círculo de energia e rastro na direção do movimento. */
	public static void depart(ServerLevel level, Entity e, Vec3 from, Vec3 to) {
		afterimage(level, e, from);
		send(level, dust(SCARLET, 1.4f), from.x, from.y + 0.9, from.z, 18, 0.35, 0.05);
		send(level, ParticleTypes.LARGE_SMOKE, from.x, from.y + 0.6, from.z, 6, 0.25, 0.02);
		ring(level, from.add(0, 0.1, 0), 0.6, 16, dust(CRIMSON, 1.1f), 0.18);
		Vec3 dir = to.subtract(from);
		double len = dir.length();
		if (len > 0.1) {
			Vec3 step = dir.normalize();
			// Rastro: a linha some em poucos ticks e dá a sensação de velocidade extrema.
			int n = (int) Math.min(24, len * 1.5);
			for (int i = 1; i <= n; i++) {
				Vec3 p = from.add(step.scale(Math.min(len, i * 0.7)));
				send(level, fade(SCARLET, DARK, 0.7f), p.x, p.y + 1.0, p.z, 1, 0.05, 0.0);
			}
		}
	}

	/** Reaparecimento: núcleo luminoso, silhueta surgindo, onda circular e faíscas arremessadas. */
	public static void arrive(ServerLevel level, Entity e, Vec3 at, SoundEvent sound) {
		send(level, dust(CORE, 2.0f), at.x, at.y + 1.0, at.z, 6, 0.08, 0.0);
		send(level, ParticleTypes.CRIMSON_SPORE, at.x, at.y + 1.0, at.z, 10, 0.4, 0.02);
		ring(level, at.add(0, 0.15, 0), 0.4, 20, dust(SCARLET, 1.2f), 0.3);
		send(level, ParticleTypes.CRIT, at.x, at.y + 1.0, at.z, 10, 0.3, 0.4);
		level.playSound(null, at.x, at.y, at.z, sound, SoundSource.HOSTILE, 0.7f, 1.4f + level.getRandom().nextFloat() * 0.3f);
	}

	/** Execução: clarão carmesim, onda de choque pequena e fragmentos que somem rápido. */
	public static void execute(ServerLevel level, Entity target, SoundEvent sound) {
		Vec3 c = target.getBoundingBox().getCenter();
		double size = Math.max(0.6, target.getBbWidth());
		send(level, ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 1, 0.0, 0.0);
		send(level, dust(SCARLET, 2.2f), c.x, c.y, c.z, (int) (14 * size), size * 0.4, 0.15);
		send(level, fade(CORE, DARK, 1.2f), c.x, c.y, c.z, (int) (10 * size), size * 0.5, 0.25);
		send(level, ParticleTypes.DAMAGE_INDICATOR, c.x, c.y, c.z, 4, 0.2, 0.1);
		ring(level, new Vec3(c.x, target.getY() + 0.1, c.z), size * 0.5, 18, dust(CRIMSON, 1.0f), 0.35);
		level.playSound(null, c.x, c.y, c.z, sound, SoundSource.HOSTILE, 0.8f, 0.8f + level.getRandom().nextFloat() * 0.3f);
	}

	/** Pico da transformação: onda carmesim, nuvem escura nos pés e feixes que se espalham. */
	public static void climax(ServerLevel level, Entity e) {
		Vec3 feet = e.position();
		send(level, ParticleTypes.EXPLOSION, feet.x, feet.y + 1.0, feet.z, 1, 0.0, 0.0);
		send(level, ParticleTypes.SONIC_BOOM, feet.x, feet.y + 1.0, feet.z, 1, 0.0, 0.0);
		for (int k = 0; k < 3; k++) {
			ring(level, feet.add(0, 0.1 + k * 0.6, 0), 0.5 + k * 0.3, 32, dust(k == 0 ? SCARLET : CRIMSON, 1.6f - k * 0.3f), 0.45 - k * 0.1);
		}
		send(level, ParticleTypes.LARGE_SMOKE, feet.x, feet.y + 0.2, feet.z, 30, 1.0, 0.04);
		RandomSource r = level.getRandom();
		for (int i = 0; i < 12; i++) {
			double a = r.nextDouble() * Math.PI * 2;
			for (int j = 1; j <= 8; j++) {
				send(level, fade(SCARLET, DARK, 0.8f), feet.x + Math.cos(a) * j * 0.5, feet.y + 1.0 + j * 0.15, feet.z + Math.sin(a) * j * 0.5, 1, 0.0, 0.0);
			}
		}
	}

	/** Pulso de energia que sobe do chão durante a transformação. */
	public static void groundPulse(ServerLevel level, Entity e, double radius) {
		ring(level, e.position().add(0, 0.05, 0), radius, 24, dust(CRIMSON, 1.3f), 0.2);
		send(level, ParticleTypes.ASH, e.getX(), e.getY() + 0.2, e.getZ(), 12, 0.8, 0.02);
	}
}
