package dev.autonomouscompanions.yuno;

import dev.autonomouscompanions.entity.CompanionEntity;
import dev.autonomouscompanions.nav.SafeSpot;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Yuno: apaixonada pela jogadora, mas não quer ser vista. Não conversa, não segue, não luta e não é ameaça para
 * ninguém. Ela:
 * <ul>
 *   <li>observa de longe, em lugares parcialmente cobertos mas com linha de visão até a jogadora (é mais comum vê-la
 *       assim); raramente surge perto, sempre por trás;</li>
 *   <li>aparece só fora do campo de visão e some logo depois que a jogadora olha para ela (um breve vislumbre), ou se
 *       a jogadora chega perto, encosta ou a atinge;</li>
 *   <li>uma vez por dia de jogo, quando a jogadora está longe, vai até um baú dela, abre a tampa, deixa uma maçã
 *       dourada e vai embora.</li>
 * </ul>
 */
public class YunoEntity extends PathfinderMob {
	public enum Mode {
		/** Escondida/invisível, longe da vista, esperando a hora de reaparecer. */
		VANISHED,
		/** Visível, imóvel, observando a jogadora. */
		WATCHING,
		/** Deixando um presente num baú. */
		DELIVERING
	}

	/** Chance de reaparecer perto (atrás da jogadora) em vez de longe. */
	public static final float CLOSE_CHANCE = 0.12F;
	/** Ticks olhando para ela até ela sumir (o "vislumbre"). */
	public static final int GLIMPSE_TICKS = 4;

	private @Nullable UUID owner;
	private Mode mode = Mode.VANISHED;
	private long modeUntil;
	private int seenTicks;
	private @Nullable GlobalPos deliverTo;
	private int deliverTicks;
	private boolean lidOpen;

	public YunoEntity(EntityType<? extends YunoEntity> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
		this.setCanPickUpLoot(false);
		this.setCustomNameVisible(false);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 20.0)
			.add(Attributes.MOVEMENT_SPEED, 0.3)
			.add(Attributes.FOLLOW_RANGE, 64.0);
	}

	@Override
	protected void registerGoals() {
		// Sem objetivos do jogo base: o comportamento é todo controlado por ela (abaixo).
	}

	// ------------------------------------------------------------------ dona

	public @Nullable UUID owner() {
		return owner;
	}

	public void setOwner(UUID owner) {
		this.owner = owner;
	}

	/** Surge observando exatamente neste ponto (usado pelos testes). */
	public void appearAt(Vec3 at, long until) {
		this.teleportTo(at.x, at.y, at.z);
		this.setInvisible(false);
		mode = Mode.WATCHING;
		modeUntil = until;
		seenTicks = 0;
	}

	/** Começa escondida até {@code until} (tempo do mundo). */
	public void startHidden(long until) {
		mode = Mode.VANISHED;
		modeUntil = until;
	}

	public Mode mode() {
		return mode;
	}

	private @Nullable ServerPlayer ownerPlayer() {
		if (owner == null || !(level() instanceof ServerLevel sl)) {
			return null;
		}
		ServerPlayer p = sl.getServer().getPlayerList().getPlayer(owner);
		return p != null && p.isAlive() && !p.isSpectator() ? p : null;
	}

	// ------------------------------------------------------------------ nunca é ameaça, nunca é alvo útil

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return super.hurtServer(level, source, damage);
		}
		// Atingida (por quem for): não se machuca, só some.
		vanish(level, true);
		return false;
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (!level().isClientSide()) {
			vanish((ServerLevel) level(), true);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public boolean isPickable() {
		// Escondida (invisível), não atrapalha a mira nem leva flechas.
		return mode != Mode.VANISHED && super.isPickable();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(Entity entity) {
	}

	@Override
	public boolean removeWhenFarAway(double distSqr) {
		return false;
	}

	@Override
	public boolean requiresCustomPersistence() {
		return true;
	}

	@Override
	public void checkDespawn() {
	}

	@Override
	protected boolean considersEntityAsAlly(Entity other) {
		// Aliada da dona e das companheiras dela: ninguém do grupo a vê como inimiga, nem ela a eles.
		if (owner != null && (owner.equals(other.getUUID()) || other instanceof CompanionEntity c && owner.equals(c.ownerUUID()))) {
			return true;
		}
		return super.considersEntityAsAlly(other);
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
	}

	// ------------------------------------------------------------------ IA

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		this.getNavigation().stop();
		if (this.tickCount % 100 == 0 && !YunoManager.isCurrent(level.getServer(), this)) {
			// Cópia antiga (de um chunk recarregado): a Yuno "de verdade" já está em outro lugar.
			this.discard();
			return;
		}
		ServerPlayer p = ownerPlayer();
		long now = level.getGameTime();
		if (p == null || p.level() != level) {
			if (mode != Mode.VANISHED) {
				hide(level, now, 200);
			}
			return;
		}
		switch (mode) {
			case VANISHED -> {
				if (!this.isInvisible()) {
					this.setInvisible(true);
				}
				if (now >= modeUntil && !reappear(level, p, now)) {
					modeUntil = now + 100; // sem lugar bom agora: tenta de novo daqui a pouco
				}
			}
			case WATCHING -> tickWatching(level, p, now);
			case DELIVERING -> tickDelivering(level, p, now);
		}
	}

	private void tickWatching(ServerLevel level, ServerPlayer p, long now) {
		this.getLookControl().setLookAt(p, 30.0F, 30.0F);
		double dist = this.distanceTo(p);
		if (dist < 6.0 || dist > 72.0) {
			vanish(level, dist < 6.0);
			return;
		}
		if (isLookedAtBy(p)) {
			// Um breve vislumbre... e ela some.
			if (++seenTicks >= GLIMPSE_TICKS) {
				vanish(level, true);
			}
			return;
		}
		seenTicks = 0;
		if (now >= modeUntil && !inViewCone(p, this.getEyePosition(), 75)) {
			// Muda de esconderijo sem ser vista.
			hide(level, now, 20 + this.getRandom().nextInt(60));
		}
	}

	/** A jogadora está olhando para ela (perto do centro da visão e sem nada no meio)? */
	public boolean isLookedAtBy(ServerPlayer p) {
		double dist = this.distanceTo(p);
		double cone = dist > 20 ? 12 : dist > 12 ? 20 : 35;
		return inViewCone(p, this.getBoundingBox().getCenter(), cone) && p.hasLineOfSight(this);
	}

	/** O ponto está dentro de um cone de {@code degrees} graus em volta da direção do olhar da jogadora? */
	public static boolean inViewCone(ServerPlayer p, Vec3 point, double degrees) {
		Vec3 to = point.subtract(p.getEyePosition());
		double len = to.length();
		if (len < 1.0E-4) {
			return true;
		}
		double cos = p.getViewVector(1.0F).dot(to.scale(1.0 / len));
		return cos >= Math.cos(Math.toRadians(degrees));
	}

	/** Some: pétalas e um som baixinho; volta a aparecer em outro lugar mais tarde. */
	public void vanish(ServerLevel level, boolean noticed) {
		if (mode == Mode.VANISHED) {
			return;
		}
		if (!this.isInvisible()) {
			level.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + 1.0, getZ(), 14, 0.35, 0.6, 0.35, 0.02);
			level.sendParticles(ParticleTypes.SMOKE, getX(), getY() + 0.8, getZ(), 4, 0.2, 0.4, 0.2, 0.01);
			if (noticed) {
				level.playSound(null, getX(), getY(), getZ(), SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, SoundSource.NEUTRAL, 0.25F, 0.6F);
			}
		}
		if (mode == Mode.DELIVERING) {
			closeChest(level);
		}
		// Vista de verdade: demora mais para voltar.
		hide(level, level.getGameTime(), noticed ? 600 + this.getRandom().nextInt(1800) : 200 + this.getRandom().nextInt(400));
	}

	/** Fica invisível longe da vista (fora do campo de visão e sem atrapalhar cliques/flechas perto da jogadora). */
	private void hide(ServerLevel level, long now, int ticks) {
		mode = Mode.VANISHED;
		modeUntil = now + ticks;
		seenTicks = 0;
		deliverTo = null;
		this.setInvisible(true);
		this.setPose(Pose.STANDING);
		ServerPlayer p = ownerPlayer();
		if (p != null && p.level() == level) {
			BlockPos spot = pickSpot(level, p, false);
			if (spot != null) {
				this.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
			}
		}
	}

	private boolean reappear(ServerLevel level, ServerPlayer p, long now) {
		boolean close = this.getRandom().nextFloat() < CLOSE_CHANCE;
		BlockPos spot = pickSpot(level, p, close);
		if (spot == null && close) {
			spot = pickSpot(level, p, false);
		}
		if (spot == null) {
			return false;
		}
		this.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
		this.getLookControl().setLookAt(p, 360.0F, 360.0F);
		lookAtNow(p.getEyePosition());
		this.setInvisible(false);
		// Atrás de uma cobertura baixa, observa agachada.
		this.setPose(coverLevel(level, spot) >= 2 && this.getRandom().nextBoolean() ? Pose.CROUCHING : Pose.STANDING);
		mode = Mode.WATCHING;
		modeUntil = now + 800 + this.getRandom().nextInt(1200);
		seenTicks = 0;
		return true;
	}

	/**
	 * Esconderijo: longe (22–42 blocos) ou, raramente, perto (8–13 blocos, atrás da jogadora); sempre fora do campo de
	 * visão no momento em que ela surge, com linha de visão até a jogadora (é possível vê-la ao virar) e de preferência
	 * com cobertura ao lado (árvores, paredes, folhas).
	 */
	public @Nullable BlockPos pickSpot(ServerLevel level, ServerPlayer p, boolean close) {
		RandomSource r = this.getRandom();
		BlockPos best = null;
		int bestScore = Integer.MIN_VALUE;
		for (int attempt = 0; attempt < 48; attempt++) {
			double a;
			if (close) {
				a = Math.toRadians(p.getYRot() + 90 + 180 + (r.nextDouble() - 0.5) * 120); // atrás dela
			} else {
				a = r.nextDouble() * Math.PI * 2;
			}
			double d = close ? 8 + r.nextDouble() * 5 : 22 + r.nextDouble() * 20;
			int x = Mth.floor(p.getX() + Math.cos(a) * d);
			int z = Mth.floor(p.getZ() + Math.sin(a) * d);
			BlockPos column = new BlockPos(x, p.getBlockY(), z);
			if (!level.hasChunkAt(column)) {
				continue;
			}
			BlockPos feet = surface(level, x, z, p.getBlockY());
			if (feet == null) {
				continue;
			}
			Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.6, feet.getZ() + 0.5);
			if (inViewCone(p, eye, 75) || !clearSight(level, eye, p.getEyePosition())) {
				continue;
			}
			int score = coverLevel(level, feet) * 3 - (int) Math.abs(feet.getY() - p.getY());
			if (score > bestScore) {
				bestScore = score;
				best = feet;
			}
		}
		return best;
	}

	private static @Nullable BlockPos surface(ServerLevel level, int x, int z, int refY) {
		int top = Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), refY + 12);
		for (int y = top; y >= refY - 12; y--) {
			BlockPos feet = new BlockPos(x, y, z);
			if (SafeSpot.isSafeStand(level, feet, null) && !level.getBlockState(feet.below()).is(net.minecraft.world.level.block.Blocks.BARRIER)) {
				return feet;
			}
		}
		return null;
	}

	private boolean clearSight(ServerLevel level, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
	}

	/** Quantos blocos de cobertura (troncos, folhas, paredes) estão colados nela, na altura do corpo. */
	private static int coverLevel(ServerLevel level, BlockPos feet) {
		int n = 0;
		for (BlockPos p : new BlockPos[]{feet.north(), feet.south(), feet.east(), feet.west()}) {
			for (int dy = 0; dy <= 1; dy++) {
				BlockState s = level.getBlockState(p.above(dy));
				if (!s.isAir() && (!s.getCollisionShape(level, p.above(dy)).isEmpty() || s.is(net.minecraft.tags.BlockTags.LEAVES))) {
					n++;
				}
			}
		}
		return n;
	}

	private void lookAtNow(Vec3 at) {
		double dx = at.x - getX();
		double dz = at.z - getZ();
		float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		this.setYRot(yaw);
		this.setYHeadRot(yaw);
		this.yBodyRot = yaw;
	}

	// ------------------------------------------------------------------ presente

	/** Começa a entrega do presente no baú (chamado pelo gerenciador quando a jogadora está longe). */
	public boolean startDelivery(ServerLevel level, GlobalPos chest) {
		if (mode == Mode.DELIVERING || chest.dimension() != level.dimension()) {
			return false;
		}
		BlockPos spot = SafeSpot.findNear(level, chest.pos(), 1, 2, 2, this.getType());
		if (spot == null) {
			return false;
		}
		this.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
		lookAtNow(Vec3.atCenterOf(chest.pos()));
		this.setPose(Pose.STANDING);
		this.setInvisible(false);
		mode = Mode.DELIVERING;
		deliverTo = chest;
		deliverTicks = 0;
		return true;
	}

	private void tickDelivering(ServerLevel level, ServerPlayer p, long now) {
		if (deliverTo == null) {
			hide(level, now, 400);
			return;
		}
		BlockPos pos = deliverTo.pos();
		this.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		if (isLookedAtBy(p) || this.distanceTo(p) < 8) {
			// Flagrada: termina rápido e some (o presente fica se já estiver lá dentro).
			if (deliverTicks >= 20) {
				vanish(level, true);
				return;
			}
		}
		deliverTicks++;
		if (deliverTicks == 1) {
			openChest(level, true);
			lidOpen = true;
			level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.1F);
		} else if (deliverTicks == 12) {
			this.setPose(Pose.CROUCHING); // se inclina sobre o baú
		} else if (deliverTicks == 20) {
			if (YunoManager.putGift(level, pos)) {
				YunoData.get(level.getServer()).giftDelivered(owner, deliverTo);
				level.sendParticles(ParticleTypes.CHERRY_LEAVES, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 8, 0.25, 0.1, 0.25, 0.01);
			} else {
				YunoData.get(level.getServer()).forgetChest(owner, deliverTo); // cheio ou não é mais um baú
			}
		} else if (deliverTicks == 30) {
			this.setPose(Pose.STANDING);
			closeChest(level);
		} else if (deliverTicks >= 38) {
			hide(level, now, 600 + this.getRandom().nextInt(1200));
		}
	}

	private void openChest(ServerLevel level, boolean open) {
		if (deliverTo == null) {
			return;
		}
		BlockState s = level.getBlockState(deliverTo.pos());
		if (s.getBlock() instanceof ChestBlock) {
			level.blockEvent(deliverTo.pos(), s.getBlock(), 1, open ? 1 : 0); // anima a tampa
		}
	}

	private void closeChest(ServerLevel level) {
		if (deliverTo != null && lidOpen) {
			lidOpen = false;
			openChest(level, false);
			level.playSound(null, deliverTo.pos(), SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.1F);
		}
	}

	/** Teleporte para outra dimensão (o gerenciador a leva para onde a jogadora estiver). */
	public void moveTo(ServerLevel target, Vec3 at) {
		this.teleportTo(target, at.x, at.y, at.z, Set.<Relative>of(), getYRot(), getXRot(), false);
	}

	// ------------------------------------------------------------------ persistência

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		if (owner != null) {
			out.putString("YunoOwner", owner.toString());
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		String o = in.getStringOr("YunoOwner", "");
		try {
			owner = o.isEmpty() ? null : UUID.fromString(o);
		} catch (IllegalArgumentException e) {
			owner = null;
		}
		// Ao carregar, nunca aparece onde estava: volta escondida e reaparece fora da vista.
		mode = Mode.VANISHED;
		modeUntil = 0;
		this.setInvisible(true);
	}

	/** A maçã dourada comum (não encantada) que ela deixa. */
	public static ItemStack gift() {
		return new ItemStack(Items.GOLDEN_APPLE);
	}
}
