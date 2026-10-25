package dev.autonomouscompanions.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.autonomouscompanions.AutonomousCompanions;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Carrega e salva {@link CompanionConfig} em JSON no diretório de configuração do Fabric. */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static volatile CompanionConfig config = new CompanionConfig();

	private ConfigManager() {
	}

	public static CompanionConfig get() {
		return config;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(AutonomousCompanions.MOD_ID + ".json");
	}

	public static synchronized void load() {
		Path file = path();
		CompanionConfig loaded = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, CompanionConfig.class);
			} catch (IOException | JsonParseException e) {
				AutonomousCompanions.LOGGER.error("Falha ao ler {}: {}. Usando valores padrão.", file, e.getMessage());
			}
		}
		if (loaded == null) {
			loaded = new CompanionConfig();
		}
		loaded.validate();
		config = loaded;
		save();
	}

	public static synchronized void save() {
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			AutonomousCompanions.LOGGER.error("Falha ao salvar {}: {}", file, e.getMessage());
		}
	}

	/** Substitui a configuração em memória (usado por testes e pelo comando de ajuste). */
	public static synchronized void set(CompanionConfig newConfig) {
		newConfig.validate();
		config = newConfig;
	}

	public static String toJson() {
		return GSON.toJson(config);
	}

	public static synchronized boolean setField(String key, String value) {
		try {
			var field = CompanionConfig.class.getField(key);
			if (key.startsWith("_")) {
				return false;
			}
			Class<?> type = field.getType();
			CompanionConfig copy = GSON.fromJson(GSON.toJson(config), CompanionConfig.class);
			if (type == int.class) {
				field.setInt(copy, Integer.parseInt(value));
			} else if (type == double.class) {
				field.setDouble(copy, Double.parseDouble(value));
			} else if (type == float.class) {
				field.setFloat(copy, Float.parseFloat(value));
			} else if (type == boolean.class) {
				if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
					return false;
				}
				field.setBoolean(copy, Boolean.parseBoolean(value));
			} else if (type == String.class) {
				field.set(copy, value);
			} else {
				return false;
			}
			copy.validate();
			config = copy;
			save();
			return true;
		} catch (ReflectiveOperationException | NumberFormatException e) {
			return false;
		}
	}
}
