package dev.autonomouscompanions.registry;

import dev.autonomouscompanions.AutonomousCompanions;
import dev.autonomouscompanions.entity.CompanionEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Registro dos tipos de entidade do mod. */
public final class ModEntities {
	public static final ResourceKey<EntityType<?>> COMPANION_KEY = ResourceKey.create(Registries.ENTITY_TYPE, AutonomousCompanions.id("companion"));
	public static final ResourceKey<EntityType<?>> HELPER_KEY = ResourceKey.create(Registries.ENTITY_TYPE, AutonomousCompanions.id("helper"));

	public static final EntityType<CompanionEntity> COMPANION = Registry.register(BuiltInRegistries.ENTITY_TYPE, COMPANION_KEY,
		EntityType.Builder.of(CompanionEntity::new, MobCategory.MISC)
			.sized(0.6F, 1.8F)
			.eyeHeight(1.62F)
			.clientTrackingRange(10)
			.build(COMPANION_KEY));

	/**
	 * A ajudante usa a mesma classe (mesma IA, inventário e persistência) com outro tipo: assim ela tem renderizador
	 * próprio (skin de braços largos), comportamentos de abastecimento e fica fora das buscas pelas três companheiras.
	 */
	public static final EntityType<CompanionEntity> HELPER = Registry.register(BuiltInRegistries.ENTITY_TYPE, HELPER_KEY,
		EntityType.Builder.of(CompanionEntity::new, MobCategory.MISC)
			.sized(0.6F, 1.8F)
			.eyeHeight(1.62F)
			.clientTrackingRange(10)
			.build(HELPER_KEY));

	public static final ResourceKey<EntityType<?>> YUNO_KEY = ResourceKey.create(Registries.ENTITY_TYPE, AutonomousCompanions.id("yuno"));

	/** Yuno: observa de longe e deixa presentes; nunca é companheira nem ameaça. */
	public static final EntityType<dev.autonomouscompanions.yuno.YunoEntity> YUNO = Registry.register(BuiltInRegistries.ENTITY_TYPE, YUNO_KEY,
		EntityType.Builder.of(dev.autonomouscompanions.yuno.YunoEntity::new, MobCategory.MISC)
			.sized(0.6F, 1.8F)
			.eyeHeight(1.62F)
			.clientTrackingRange(10)
			.build(YUNO_KEY));

	private ModEntities() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(COMPANION, CompanionEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(HELPER, CompanionEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(YUNO, dev.autonomouscompanions.yuno.YunoEntity.createAttributes());
	}

	public static EntityType<CompanionEntity> typeFor(int variant) {
		return variant == dev.autonomouscompanions.entity.CompanionVariant.AJUDANTE.ordinal() ? HELPER : COMPANION;
	}
}
