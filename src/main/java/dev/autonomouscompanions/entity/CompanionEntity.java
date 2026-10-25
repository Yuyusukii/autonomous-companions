package dev.autonomouscompanions.entity;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.ai.CompanionBrain;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.config.ConfigManager;
import dev.autonomouscompanions.inventory.CompanionInventory;
import dev.autonomouscompanions.network.ServerNetworking;
import dev.autonomouscompanions.persist.CompanionWorldData;
import dev.autonomouscompanions.survival.CompanionNeeds;
import dev.autonomouscompanions.survival.DeathRecovery;
import dev.autonomouscompanions.task.OrderBook;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Companheira autônoma. A lógica de decisão fica em {@link CompanionBrain}; esta classe concentra identidade,
 * dados persistentes, sincronização com o cliente e integração com os sistemas do jogo (dano, morte, interação).
 */
public class CompanionEntity extends PathfinderMob implements OwnableEntity {
	private static final EntityDataAccessor<Integer> DATA_VARIANT = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> DATA_NAME = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Optional<EntityReference<LivingEntity>>> DATA_OWNER = SynchedEntityData.defineId(
		CompanionEntity.class, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE
	);
	private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_DEMON = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);

	/** Quanto a ajudante é mais rápida que as companheiras. */
	public static final double HELPER_SPEED_FACTOR = 1.3;

	private final CompanionInventory inventory = new CompanionInventory();
	private final CompanionNeeds needs = new CompanionNeeds();
	private final OrderBook orders = new OrderBook();
	private CompanionSettings settings = new CompanionSettings();
	private CompanionRole role = CompanionRole.NONE;
	private final CompanionStats stats = new CompanionStats();
	private final dev.autonomouscompanions.demon.DemonMode demon = new dev.autonomouscompanions.demon.DemonMode(this);
	/** Cliente: tick em que o estado do Modo Demônio mudou (para as animações). */
	private int demonSince;
	private @Nullable CompanionBrain brain;
	private String ownerName = "";
	private Component stateDetail = Component.empty();
	private int starterIndex = -1;
	private int activityLevel;
	private int stateSetTick;
	private long lastOwnerSeenTick;
	private int lastAttackedByTick = -1000;

	public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
		super(type, level);
		this.inventory.setReservationProvider(this.orders::reserved);
		this.inventory.setWorkingProvider(this.orders::working);
		this.setPersistenceRequired();
		this.setCanPickUpLoot(false);
		this.setCustomNameVisible(true);
		if (this.getNavigation() instanceof GroundPathNavigation nav) {
			nav.setCanOpenDoors(true);
			nav.setCanFloat(true);
		}
		this.setPathfindingMalus(PathType.WATER, 8.0F);
		this.setPathfindingMalus(PathType.WATER_BORDER, 4.0F);
		this.setPathfindingMalus(PathType.FIRE_IN_NEIGHBOR, 16.0F);
		this.setPathfindingMalus(PathType.DAMAGING_IN_NEIGHBOR, 12.0F);
		this.setPathfindingMalus(PathType.FIRE, -1.0F);
		this.setPathfindingMalus(PathType.LAVA, -1.0F);
		this.setPathfindingMalus(PathType.POWDER_SNOW, -1.0F);
		this.setPathfindingMalus(PathType.DOOR_WOOD_CLOSED, 0.0F);
		this.setPathfindingMalus(PathType.LEAVES, -1.0F);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 20.0)
			.add(Attributes.MOVEMENT_SPEED, 0.30)
			.add(Attributes.ATTACK_DAMAGE, 1.0)
			.add(Attributes.ATTACK_KNOCKBACK, 0.2)
			.add(Attributes.ATTACK_SPEED, 4.0)
			.add(Attributes.FOLLOW_RANGE, 48.0)
			.add(Attributes.ARMOR, 0.0)
			.add(Attributes.SAFE_FALL_DISTANCE, 3.0)
			.add(Attributes.BLOCK_INTERACTION_RANGE, 4.5)
			.add(Attributes.ENTITY_INTERACTION_RANGE, 3.0)
			// Atributos de mineração do jogador (a quebra de blocos usa a mesma fórmula; encantamentos alteram estes valores).
			.add(Attributes.MINING_EFFICIENCY, 0.0)
			.add(Attributes.BLOCK_BREAK_SPEED, 1.0)
			.add(Attributes.SUBMERGED_MINING_SPEED, 0.2);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
	}

	@Override
	protected PathNavigation createNavigation(Level level) {
		GroundPathNavigation nav = new GroundPathNavigation(this, level);
		nav.setCanOpenDoors(true);
		nav.setCanFloat(true);
		return nav;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_VARIANT, 0);
		builder.define(DATA_NAME, "");
		builder.define(DATA_OWNER, Optional.empty());
		builder.define(DATA_STATE, CompanionState.WAITING_INTERACTION.ordinal());
		builder.define(DATA_DEMON, 0);
	}

	// ------------------------------------------------------------------ identidade

	public CompanionVariant variant() {
		return CompanionVariant.byIndex(this.entityData.get(DATA_VARIANT));
	}

	public void setVariant(CompanionVariant variant) {
		this.entityData.set(DATA_VARIANT, variant.ordinal());
	}

	public String companionName() {
		return this.entityData.get(DATA_NAME);
	}

	/** A ajudante: faz a segurança das outras três (ou as abastece, nas regras antigas) e não recebe ordens da dona. */
	public boolean isHelper() {
		return this.getType() == dev.autonomouscompanions.registry.ModEntities.HELPER;
	}

	// Regras de sobrevivência (pedido da jogadora em 18/10/2026; cada uma tem uma opção na configuração). O "modo clássico"
	// de CompanionSettings volta às regras antigas para esta companheira (usado pelos testes dessas regras).

	/** Vida infinita: dano comum não a fere. */
	public boolean infiniteLife() {
		return !settings.classicSurvival && ConfigManager.get().vidaInfinita;
	}

	/** Sem fome: a fome fica sempre cheia. */
	public boolean noHunger() {
		return !settings.classicSurvival && ConfigManager.get().semFome;
	}

	/** Sem cansaço: a disposição nunca cai. */
	public boolean noFatigue() {
		return !settings.classicSurvival && ConfigManager.get().semCansaco;
	}

	/** Modo Demônio toda noite (só tem efeito na companheira de cabelo vermelho). */
	public boolean demonEveryNight() {
		return !settings.classicSurvival && ConfigManager.get().modoDemonioTodaNoite;
	}

	/** A ajudante como segurança das outras (no lugar de abastecê-las). */
	public boolean guardsAllies() {
		return isHelper() && !settings.classicSurvival && ConfigManager.get().ajudanteSeguranca;
	}

	public boolean isNamed() {
		return !companionName().isEmpty();
	}

	/** Define o nome persistente e sincroniza o nome exibido acima da personagem. */
	public void setCompanionName(String name) {
		this.entityData.set(DATA_NAME, name);
		refreshDisplayName();
	}

	private void refreshDisplayName() {
		String name = companionName();
		if (name.isEmpty()) {
			this.setCustomName(Component.translatable("entity.autonomouscompanions.companion.unnamed"));
		} else {
			this.setCustomName(Component.literal(name));
		}
		this.setCustomNameVisible(true);
	}

	public int starterIndex() {
		return starterIndex;
	}

	public void setStarterIndex(int starterIndex) {
		this.starterIndex = starterIndex;
	}

	// ------------------------------------------------------------------ dono

	@Override
	public @Nullable EntityReference<LivingEntity> getOwnerReference() {
		return this.entityData.get(DATA_OWNER).orElse(null);
	}

	public @Nullable UUID ownerUUID() {
		EntityReference<LivingEntity> ref = getOwnerReference();
		return ref == null ? null : ref.getUUID();
	}

	public void setOwner(@Nullable Player player) {
		this.entityData.set(DATA_OWNER, Optional.ofNullable(player).map(EntityReference::<LivingEntity>of));
		if (player != null) {
			this.ownerName = player.getGameProfile().name();
		}
	}

	public void setOwnerUUID(@Nullable UUID uuid) {
		this.entityData.set(DATA_OWNER, Optional.ofNullable(uuid).map(EntityReference::<LivingEntity>of));
	}

	public boolean hasOwner() {
		return getOwnerReference() != null;
	}

	public boolean isOwnedBy(@Nullable Entity entity) {
		UUID owner = ownerUUID();
		return entity != null && owner != null && owner.equals(entity.getUUID());
	}

	/** Dono conectado e na mesma dimensão (ou nulo). */
	public @Nullable ServerPlayer ownerInLevel() {
		UUID owner = ownerUUID();
		if (owner == null || !(this.level() instanceof ServerLevel serverLevel)) {
			return null;
		}
		Player p = serverLevel.getPlayerByUUID(owner);
		return p instanceof ServerPlayer sp && sp.isAlive() && !sp.isSpectator() ? sp : null;
	}

	/** Dono conectado em qualquer dimensão (ou nulo). */
	public @Nullable ServerPlayer ownerAnywhere() {
		UUID owner = ownerUUID();
		if (owner == null || !(this.level() instanceof ServerLevel serverLevel)) {
			return null;
		}
		return serverLevel.getServer().getPlayerList().getPlayer(owner);
	}

	public String ownerName() {
		return ownerName;
	}

	public void markOwnerSeen() {
		this.lastOwnerSeenTick = this.level().getGameTime();
	}

	public long lastOwnerSeenTick() {
		return lastOwnerSeenTick;
	}

	// ------------------------------------------------------------------ sistemas

	public CompanionInventory inventory() {
		return inventory;
	}

	public CompanionNeeds needs() {
		return needs;
	}

	public OrderBook orders() {
		return orders;
	}

	public CompanionSettings settings() {
		return settings;
	}

	public CompanionRole role() {
		return role;
	}

	public void setRole(CompanionRole role) {
		this.role = role;
		if (role == CompanionRole.GUARD) {
			settings.proactiveDefense = true;
		}
	}

	public CompanionStats stats() {
		return stats;
	}

	public dev.autonomouscompanions.demon.DemonMode demon() {
		return demon;
	}

	/** Estado do Modo Demônio visto pelos dois lados (sincronizado com o cliente). */
	public dev.autonomouscompanions.demon.DemonState demonState() {
		return dev.autonomouscompanions.demon.DemonState.byIndex(this.entityData.get(DATA_DEMON));
	}

	public void setDemonStateSynced(dev.autonomouscompanions.demon.DemonState s) {
		this.entityData.set(DATA_DEMON, s.ordinal());
	}

	/** Cliente: ticks (com fração) desde a última mudança de estado do Modo Demônio. */
	public float demonAge(float partialTick) {
		return this.tickCount - demonSince + partialTick;
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (DATA_DEMON.equals(accessor)) {
			demonSince = this.tickCount;
		}
	}

	public CompanionBrain brain() {
		if (brain == null) {
			brain = new CompanionBrain(this);
		}
		return brain;
	}

	public CompanionState companionState() {
		return CompanionState.byIndex(this.entityData.get(DATA_STATE));
	}

	public void setCompanionState(CompanionState state, Component detail) {
		if (companionState() != state) {
			this.entityData.set(DATA_STATE, state.ordinal());
		}
		this.stateDetail = detail;
		this.stateSetTick = this.tickCount;
	}

	/** Tick em que o estado foi definido pela última vez (permite a etapas detalharem sem serem sobrescritas). */
	public int stateSetTick() {
		return stateSetTick;
	}

	@Override
	protected void completeUsingItem() {
		ItemStack using = this.getUseItem();
		net.minecraft.world.food.FoodProperties food = using.get(net.minecraft.core.component.DataComponents.FOOD);
		boolean wasUsing = this.isUsingItem();
		super.completeUsingItem();
		if (wasUsing && food != null && !this.level().isClientSide()) {
			needs.eat(food);
			stats.meals++;
			this.heal(0.0F);
		}
	}

	@Override
	public void handleExtraItemsCreatedOnUse(ItemStack extra) {
		// Ex.: tigela de ensopado volta para o inventário.
		ItemStack rest = inventory.insert(extra);
		if (!rest.isEmpty()) {
			this.spawnAtLocation((ServerLevel) this.level(), rest);
		}
	}

	public Component stateDetail() {
		return stateDetail;
	}

	/** Nível de atividade do tick atual (0 parada .. 3 combate), usado pelo gasto de fome. */
	/** Atividade neste tick: 0 parada, 1 andando, 2 trabalhando (quebrando, fabricando), 3 lutando. */
	public int activityLevel() {
		return activityLevel;
	}

	public void setActivityLevel(int level) {
		this.activityLevel = Math.max(this.activityLevel, level);
	}

	public int lastAttackedByTick() {
		return lastAttackedByTick;
	}

	// ------------------------------------------------------------------ ciclo de vida

	@Override
	public @Nullable SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason spawnReason,
		@Nullable SpawnGroupData groupData) {
		SpawnGroupData data = super.finalizeSpawn(level, difficulty, spawnReason, groupData);
		applyConfiguredAttributes();
		this.setHealth(this.getMaxHealth());
		refreshDisplayName();
		return data;
	}

	public void applyConfiguredAttributes() {
		var cfg = ConfigManager.get();
		var maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null && maxHealth.getBaseValue() != cfg.vidaMaxima) {
			maxHealth.setBaseValue(cfg.vidaMaxima);
			if (this.getHealth() > this.getMaxHealth()) {
				this.setHealth(this.getMaxHealth());
			}
		}
		var damage = this.getAttribute(Attributes.ATTACK_DAMAGE);
		if (damage != null) {
			damage.setBaseValue(cfg.danoBase);
		}
		var speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			// A ajudante é bem mais ágil (caça e entregas rápidas).
			speed.setBaseValue(cfg.velocidadeMovimento * (isHelper() ? HELPER_SPEED_FACTOR : 1.0));
		}
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		this.activityLevel = this.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4 ? 1 : 0;
		if (!demon.engaged()) {
			demon.checkNight(level);
		}
		if (demon.engaged()) {
			demon.tick(level);
		} else {
			long t0 = System.nanoTime();
			brain().tick(level);
			brain().metrics().addTick(System.nanoTime() - t0, companionState());
		}
		needs.setSustained(noHunger(), noFatigue());
		float effect = needs.tick(activityLevel, isSleeping(), companionState() == CompanionState.RESTING, getHealth(), getMaxHealth(), level.getDifficulty(),
			level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.NATURAL_HEALTH_REGENERATION));
		if (effect < 0) {
			this.heal(-effect);
		} else if (effect > 0) {
			this.hurtServer(level, this.damageSources().starve(), effect);
		}
		if (this.tickCount % 40 == 0) {
			adoptInSingleplayer(level);
			CompanionWorldData.get(level.getServer()).updateCompanion(this);
		}
	}

	/**
	 * Mundo de um jogador só: se a dona registrada não está conectada e quem joga é outra conta (outro nome no modo
	 * offline gera outra identidade — a janela de desenvolvimento sorteia "PlayerNNN" a cada abertura), quem está
	 * jogando passa a ser a dona. Antes as companheiras não aceitavam nome nem ordens dela. Nunca em servidor nem em
	 * mundo aberto para LAN.
	 */
	private void adoptInSingleplayer(ServerLevel level) {
		var server = level.getServer();
		if (!server.isSingleplayer() || server.isPublished() || server.getSingleplayerProfile() == null) {
			return;
		}
		UUID host = server.getSingleplayerProfile().id();
		UUID owner = ownerUUID();
		if (owner == null || owner.equals(host) || server.getPlayerList().getPlayer(owner) != null) {
			return;
		}
		ServerPlayer hostPlayer = server.getPlayerList().getPlayer(host);
		if (hostPlayer != null) {
			setOwner(hostPlayer);
			debug("Dona anterior não está no mundo (jogo individual): agora pertence a " + hostPlayer.getGameProfile().name() + ".");
		}
	}

	@Override
	public void tick() {
		double px = this.getX();
		double pz = this.getZ();
		super.tick();
		if (!this.level().isClientSide()) {
			double dx = this.getX() - px;
			double dz = this.getZ() - pz;
			double moved = Math.sqrt(dx * dx + dz * dz);
			if (moved > 0 && moved < 2.0) {
				stats.distanceTraveled += moved;
				brain().onMoved(moved);
			}
		}
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
		// Companheiras nunca desaparecem por distância ou dificuldade pacífica.
	}

	@Override
	public boolean isPushable() {
		// Flutuando, caçando ou dormindo no Modo Demônio, nada a empurra.
		return (demon == null || !demon.engaged()) && super.isPushable();
	}

	@Override
	public boolean canBeAffected(net.minecraft.world.effect.MobEffectInstance effect) {
		return (demon == null || !demon.blocksEffect(effect)) && super.canBeAffected(effect);
	}

	@Override
	protected void doPush(Entity entity) {
		// Não empurra jogadores nem outras companheiras: evita bloquear o dono em passagens estreitas.
		if (entity instanceof Player || entity instanceof CompanionEntity) {
			return;
		}
		super.doPush(entity);
	}

	@Override
	protected boolean considersEntityAsAlly(Entity other) {
		if (isOwnedBy(other)) {
			return true;
		}
		if (other instanceof CompanionEntity c && c.ownerUUID() != null && c.ownerUUID().equals(this.ownerUUID())) {
			return true;
		}
		if (other instanceof dev.autonomouscompanions.yuno.YunoEntity yuno && ownerUUID() != null && ownerUUID().equals(yuno.owner())) {
			return true; // a Yuno da mesma dona nunca é alvo
		}
		if (other instanceof OwnableEntity pet && pet.getOwnerReference() != null && ownerUUID() != null
			&& pet.getOwnerReference().getUUID().equals(ownerUUID())) {
			return true;
		}
		return super.considersEntityAsAlly(other);
	}

	@Override
	public boolean canAttack(LivingEntity target) {
		return !this.isAlliedTo(target) && super.canAttack(target);
	}

	// ------------------------------------------------------------------ interação

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		if (this.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		ServerPlayer sp = (ServerPlayer) player;
		if (hasOwner() && !isOwnedBy(player)) {
			sp.sendSystemMessage(Component.translatable("message.autonomouscompanions.not_owner", this.getDisplayName()), true);
			return InteractionResult.CONSUME;
		}
		if (demon.engaged()) {
			// Durante o Modo Demônio e o sono que vem depois, ela não atende (nem acorda).
			sp.sendSystemMessage(Component.translatable("message.autonomouscompanions.demon_busy", this.getDisplayName(), stateDetail()), true);
			return InteractionResult.CONSUME;
		}
		if (this.isSleeping()) {
			this.stopSleeping();
		}
		this.getNavigation().stop();
		this.getLookControl().setLookAt(player, 30.0F, 30.0F);
		// Shift + botão direito com um item na mão: entrega a pilha inteira para ela.
		ItemStack held = player.getItemInHand(hand);
		if (player.isShiftKeyDown() && isNamed() && !held.isEmpty() && !held.is(dev.autonomouscompanions.registry.ModItems.WHISTLE)) {
			ServerNetworking.giveFromPlayer(sp, this, sp.getInventory().getSelectedSlot(), held.getCount());
			return InteractionResult.CONSUME;
		}
		if (isHelper()) {
			// Ela não recebe ordens: só mostra o que está fazendo.
			Component doing = stateDetail().getString().isEmpty() ? Component.translatable(companionState().translationKey()) : stateDetail();
			sp.sendSystemMessage(Component.translatable("message.autonomouscompanions.helper_status", this.getDisplayName(), doing), true);
			return InteractionResult.CONSUME;
		}
		if (!isNamed()) {
			ServerNetworking.openNaming(sp, this);
		} else {
			ServerNetworking.openPanel(sp, this);
		}
		return InteractionResult.CONSUME;
	}

	// ------------------------------------------------------------------ dano e morte

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (demon != null && demon.blocks(source)) {
			return false; // Modo Demônio e sono: imune a dano comum
		}
		if (infiniteLife() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			// Vida infinita: nada a fere, mas ela percebe quem a atacou e se defende.
			if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker && attacker != this && !isAlliedTo(attacker)) {
				this.lastAttackedByTick = this.tickCount;
				if (this.isSleeping()) {
					this.stopSleeping();
				}
				brain().onHurt(source);
			}
			return false;
		}
		if (infiniteLife() && source.is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)) {
			// Caiu no vazio: com vida infinita, em vez de morrer ela volta para perto da dona (/kill continua valendo).
			ServerPlayer owner = ownerAnywhere();
			if (owner != null && brain().bringNearOwner(owner)) {
				return false;
			}
		}
		if (source.getEntity() != null && isAlliedTo(source.getEntity()) && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			// Fogo amigo do dono ou de outra companheira não causa dano.
			return false;
		}
		boolean hurt = super.hurtServer(level, source, damage);
		if (hurt) {
			this.lastAttackedByTick = this.tickCount;
			if (this.isSleeping()) {
				this.stopSleeping();
			}
			brain().onHurt(source);
		}
		if (hurt) {
			demon.afterHurt(source);
		}
		return hurt;
	}

	@Override
	public void die(DamageSource source) {
		if (!this.level().isClientSide() && demon.preventDeath(source)) {
			return; // golpe fatal em combate: o Modo Demônio desperta no lugar da morte
		}
		if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverLevel) {
			DeathRecovery.onCompanionDeath(serverLevel, this, source);
		}
		super.die(source);
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
		// Itens e equipamentos são tratados por DeathRecovery (guardados no registro ou largados no local).
	}

	@Override
	protected void dropEquipment(ServerLevel level) {
	}

	@Override
	public void remove(RemovalReason reason) {
		if (brain != null) {
			brain.release();
		}
		super.remove(reason);
	}

	// ------------------------------------------------------------------ persistência

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("CompanionVariant", this.entityData.get(DATA_VARIANT));
		output.putString("CompanionName", companionName());
		EntityReference.store(getOwnerReference(), output, "CompanionOwner");
		output.putString("CompanionOwnerName", ownerName);
		output.putInt("StarterIndex", starterIndex);
		output.putString("Role", role.id());
		output.putInt("State", this.entityData.get(DATA_STATE));
		output.store("StateDetail", ComponentSerialization.CODEC, stateDetail);
		output.store("Needs", CompanionNeeds.CODEC, needs);
		output.store("Settings", CompanionSettings.CODEC, settings);
		output.store("Orders", OrderBook.LIST_CODEC, orders.all());
		output.store("Stats", CompanionStats.CODEC, stats);
		inventory.storeAsItemList(output.list("CompanionInventory", ItemStack.CODEC));
		demon.save(output.child("DemonMode"));
		if (brain != null) {
			brain.save(output.child("CompanionBrain")); // "Brain" é usado pelo próprio jogo
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.entityData.set(DATA_VARIANT, input.getIntOr("CompanionVariant", 0));
		this.entityData.set(DATA_NAME, input.getStringOr("CompanionName", ""));
		EntityReference<LivingEntity> owner = EntityReference.read(input, "CompanionOwner");
		this.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
		this.ownerName = input.getStringOr("CompanionOwnerName", "");
		this.starterIndex = input.getIntOr("StarterIndex", -1);
		this.role = CompanionRole.byId(input.getStringOr("Role", "nenhuma"));
		this.entityData.set(DATA_STATE, input.getIntOr("State", CompanionState.WAITING_INTERACTION.ordinal()));
		this.stateDetail = input.read("StateDetail", ComponentSerialization.CODEC).orElse(Component.empty());
		input.read("Needs", CompanionNeeds.CODEC).ifPresent(n -> {
			this.needs.setHunger(n.hunger());
			this.needs.setFatigue(n.fatigue());
		});
		this.settings = input.read("Settings", CompanionSettings.CODEC).orElseGet(CompanionSettings::new);
		this.orders.load(input.read("Orders", OrderBook.LIST_CODEC).orElse(java.util.List.of()));
		input.read("Stats", CompanionStats.CODEC).ifPresent(this.stats::copyFrom);
		this.inventory.clearContent();
		this.inventory.fromItemList(input.listOrEmpty("CompanionInventory", ItemStack.CODEC));
		input.child("CompanionBrain").ifPresent(b -> brain().load(b));
		applyConfiguredAttributes();
		input.child("DemonMode").ifPresent(demon::load);
		refreshDisplayName();
	}

	/** Usado pela recuperação após morte: restaura vida e limpa estados perigosos. */
	public void prepareAfterRevive() {
		this.setHealth(Math.max(1.0F, this.getMaxHealth() * 0.6F));
		this.clearFire();
		this.removeAllEffects();
		this.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		this.fallDistance = 0;
		this.deathTime = 0;
		this.needs.setHunger(Math.max(this.needs.hunger(), 14));
	}

	public ItemStack mainHandItem() {
		return this.getItemBySlot(EquipmentSlot.MAINHAND);
	}

	public void debug(String message) {
		if (settings.debug || ConfigManager.get().depuracao) {
			AutonomousCompanions.LOGGER.info("[{}] {}", isNamed() ? companionName() : getStringUUID(), message);
			ServerPlayer owner = ownerAnywhere();
			if (owner != null && settings.debug) {
				owner.sendSystemMessage(Component.literal("§7[" + companionName() + "] " + message));
			}
		}
	}

	public BlockPos feetPos() {
		return this.blockPosition();
	}
}
