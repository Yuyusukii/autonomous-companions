package dev.autonomouscompanions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.autonomouscompanions.ai.CompanionState;
import dev.autonomouscompanions.build.Blueprint;
import dev.autonomouscompanions.build.BuildProject;
import dev.autonomouscompanions.build.HouseSize;
import dev.autonomouscompanions.build.HouseStyle;
import dev.autonomouscompanions.build.WoodPalette;
import dev.autonomouscompanions.entity.CompanionRole;
import dev.autonomouscompanions.entity.CompanionVariant;
import dev.autonomouscompanions.task.OrderEnums.GatherMode;
import dev.autonomouscompanions.task.OrderEnums.OrderStatus;
import dev.autonomouscompanions.task.OrderEnums.OrderType;
import dev.autonomouscompanions.task.OrderEnums.SearchProfile;
import dev.autonomouscompanions.world.ResourceKnowledge;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** As duas traduções cobrem todas as chaves geradas dinamicamente e têm os mesmos parâmetros. */
class LangTest {
	private static JsonObject ptBr;
	private static JsonObject enUs;

	@BeforeAll
	static void load() throws Exception {
		McBootstrap.init();
		ptBr = read("pt_br");
		enUs = read("en_us");
	}

	private static JsonObject read(String lang) throws Exception {
		var in = LangTest.class.getResourceAsStream("/assets/autonomouscompanions/lang/" + lang + ".json");
		assertNotNull(in, "arquivo de tradução ausente: " + lang);
		try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		}
	}

	private static int placeholders(String s) {
		s = s.replace("%%", "");
		Matcher pos = Pattern.compile("%(\\d+)\\$s").matcher(s);
		int max = 0;
		boolean positional = false;
		while (pos.find()) {
			positional = true;
			max = Math.max(max, Integer.parseInt(pos.group(1)));
		}
		if (positional) {
			return max;
		}
		Matcher seq = Pattern.compile("%s").matcher(s);
		int n = 0;
		while (seq.find()) {
			n++;
		}
		return n;
	}

	@Test
	void bothLanguagesHaveTheSameKeysAndPlaceholders() {
		assertEquals(ptBr.keySet(), enUs.keySet());
		for (String key : ptBr.keySet()) {
			assertEquals(placeholders(ptBr.get(key).getAsString()), placeholders(enUs.get(key).getAsString()), "parâmetros diferentes em " + key);
		}
	}

	@Test
	void everyDynamicKeyIsTranslated() {
		List<String> keys = new ArrayList<>();
		keys.add("entity.autonomouscompanions.companion");
		for (CompanionState s : CompanionState.values()) {
			keys.add(s.translationKey());
		}
		for (CompanionRole r : CompanionRole.values()) {
			keys.add(r.translationKey());
			keys.add(r.descriptionKey());
		}
		for (CompanionVariant v : CompanionVariant.values()) {
			keys.add(v.translationKey());
		}
		for (OrderType t : OrderType.values()) {
			keys.add(t.translationKey());
		}
		for (OrderStatus t : OrderStatus.values()) {
			keys.add(t.translationKey());
		}
		for (GatherMode t : GatherMode.values()) {
			keys.add(t.translationKey());
		}
		for (SearchProfile t : SearchProfile.values()) {
			keys.add(t.translationKey());
		}
		for (HouseStyle s : HouseStyle.values()) {
			keys.add(s.translationKey());
			keys.add(s.descriptionKey());
		}
		for (HouseSize s : HouseSize.values()) {
			keys.add(s.translationKey());
		}
		for (Blueprint.Phase p : Blueprint.Phase.values()) {
			keys.add(p.translationKey());
		}
		for (BuildProject.Status s : BuildProject.Status.values()) {
			keys.add(s.translationKey());
		}
		for (WoodPalette w : WoodPalette.ALL) {
			keys.add(w.translationKey());
		}
		for (ResourceKnowledge.Dim d : ResourceKnowledge.Dim.values()) {
			keys.add(d.translationKey());
		}
		for (String tab : new String[]{"collect", "build", "combat", "food", "roles", "inventory", "status", "settings"}) {
			keys.add("gui.autonomouscompanions.tab." + tab);
			keys.add("gui.autonomouscompanions.tab." + tab + ".title");
		}
		for (String d : new String[]{"north", "south", "east", "west"}) {
			keys.add("gui.autonomouscompanions.dir." + d);
		}
		for (String reason : new String[]{"none", "fire", "drowning", "creeper", "low_health", "overwhelmed"}) {
			keys.add("detail.autonomouscompanions.flee." + reason);
		}
		for (String key : keys) {
			assertTrue(ptBr.has(key), "falta em pt_br: " + key);
			assertTrue(enUs.has(key), "falta em en_us: " + key);
		}
	}
}
