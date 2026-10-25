package dev.autonomouscompanions.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.autonomouscompanions.config.ConfigManager;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/** Preferências individuais configuráveis pelo dono na interface. */
public final class CompanionSettings {
	public static final Codec<CompanionSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.optionalFieldOf("followMin", 3).forGetter(s -> s.followMin),
		Codec.INT.optionalFieldOf("followMax", 7).forGetter(s -> s.followMax),
		Codec.INT.optionalFieldOf("idleRadius", 24).forGetter(s -> s.idleRadius),
		Codec.BOOL.optionalFieldOf("idleExplore", true).forGetter(s -> s.idleExplore),
		Codec.BOOL.optionalFieldOf("protectOwner", true).forGetter(s -> s.protectOwner),
		Codec.BOOL.optionalFieldOf("proactiveDefense", false).forGetter(s -> s.proactiveDefense),
		Codec.BOOL.optionalFieldOf("allowTerrainChanges", true).forGetter(s -> s.allowTerrainChanges),
		Codec.BOOL.optionalFieldOf("allowCooperation", true).forGetter(s -> s.allowCooperation),
		Codec.INT.optionalFieldOf("maxSearchDistance", 0).forGetter(s -> s.maxSearchDistance),
		Codec.BOOL.optionalFieldOf("autoEquip", true).forGetter(s -> s.autoEquip),
		Codec.BOOL.optionalFieldOf("stay", false).forGetter(s -> s.stay),
		BlockPos.CODEC.optionalFieldOf("stayPos").forGetter(s -> Optional.ofNullable(s.stayPos)),
		Codec.BOOL.optionalFieldOf("roleWork", true).forGetter(s -> s.roleWork),
		Codec.BOOL.optionalFieldOf("debug", false).forGetter(s -> s.debug),
		Codec.BOOL.optionalFieldOf("classicSurvival", false).forGetter(s -> s.classicSurvival)
	).apply(i, CompanionSettings::new));

	public int followMin;
	public int followMax;
	public int idleRadius;
	public boolean idleExplore;
	public boolean protectOwner;
	public boolean proactiveDefense;
	public boolean allowTerrainChanges;
	public boolean allowCooperation;
	public int maxSearchDistance;
	public boolean autoEquip;
	public boolean stay;
	public @Nullable BlockPos stayPos;
	public boolean roleWork;
	public boolean debug;
	/**
	 * Regras antigas de sobrevivência para esta companheira: pode morrer, sente fome e cansaço, o Modo Demônio só desperta
	 * no golpe fatal e a ajudante abastece as outras (em vez de fazer a segurança). Só no servidor (não vai para a tela);
	 * usado pelos testes que verificam essas regras, que continuam existindo atrás das opções da configuração.
	 */
	public boolean classicSurvival;

	public CompanionSettings() {
		this(ConfigManager.get().distanciaMinimaAcompanhamento, ConfigManager.get().distanciaMaximaAcompanhamento, 24, true, true, false, true, true, 0,
			true, false, Optional.empty(), true, false, false);
	}

	private CompanionSettings(int followMin, int followMax, int idleRadius, boolean idleExplore, boolean protectOwner, boolean proactiveDefense,
		boolean allowTerrainChanges, boolean allowCooperation, int maxSearchDistance, boolean autoEquip, boolean stay, Optional<BlockPos> stayPos,
		boolean roleWork, boolean debug, boolean classicSurvival) {
		this.followMin = followMin;
		this.followMax = followMax;
		this.idleRadius = idleRadius;
		this.idleExplore = idleExplore;
		this.protectOwner = protectOwner;
		this.proactiveDefense = proactiveDefense;
		this.allowTerrainChanges = allowTerrainChanges;
		this.allowCooperation = allowCooperation;
		this.maxSearchDistance = maxSearchDistance;
		this.autoEquip = autoEquip;
		this.stay = stay;
		this.stayPos = stayPos.orElse(null);
		this.roleWork = roleWork;
		this.debug = debug;
		this.classicSurvival = classicSurvival;
		sanitize();
	}

	public void sanitize() {
		followMin = Mth.clamp(followMin, 1, 16);
		followMax = Mth.clamp(followMax, followMin + 1, 32);
		idleRadius = Mth.clamp(idleRadius, 4, ConfigManager.get().limiteExploracaoOciosa);
		maxSearchDistance = Mth.clamp(maxSearchDistance, 0, ConfigManager.get().alcanceBuscaMaximoAbsoluto);
	}

	public void write(RegistryFriendlyByteBuf buf) {
		buf.writeVarInt(followMin);
		buf.writeVarInt(followMax);
		buf.writeVarInt(idleRadius);
		buf.writeBoolean(idleExplore);
		buf.writeBoolean(protectOwner);
		buf.writeBoolean(proactiveDefense);
		buf.writeBoolean(allowTerrainChanges);
		buf.writeBoolean(allowCooperation);
		buf.writeVarInt(maxSearchDistance);
		buf.writeBoolean(autoEquip);
		buf.writeBoolean(stay);
		buf.writeBoolean(roleWork);
		buf.writeBoolean(debug);
	}

	public static CompanionSettings read(RegistryFriendlyByteBuf buf) {
		CompanionSettings s = new CompanionSettings();
		s.followMin = buf.readVarInt();
		s.followMax = buf.readVarInt();
		s.idleRadius = buf.readVarInt();
		s.idleExplore = buf.readBoolean();
		s.protectOwner = buf.readBoolean();
		s.proactiveDefense = buf.readBoolean();
		s.allowTerrainChanges = buf.readBoolean();
		s.allowCooperation = buf.readBoolean();
		s.maxSearchDistance = buf.readVarInt();
		s.autoEquip = buf.readBoolean();
		s.stay = buf.readBoolean();
		s.roleWork = buf.readBoolean();
		s.debug = buf.readBoolean();
		return s;
	}

	/** Copia somente os campos editáveis pela interface (a posição de espera é definida pelo servidor). */
	public void applyFromClient(CompanionSettings other) {
		this.followMin = other.followMin;
		this.followMax = other.followMax;
		this.idleRadius = other.idleRadius;
		this.idleExplore = other.idleExplore;
		this.protectOwner = other.protectOwner;
		this.proactiveDefense = other.proactiveDefense;
		this.allowTerrainChanges = other.allowTerrainChanges;
		this.allowCooperation = other.allowCooperation;
		this.maxSearchDistance = other.maxSearchDistance;
		this.autoEquip = other.autoEquip;
		this.roleWork = other.roleWork;
		this.debug = other.debug;
		sanitize();
	}
}
