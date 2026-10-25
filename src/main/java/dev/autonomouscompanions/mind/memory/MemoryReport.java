package dev.autonomouscompanions.mind.memory;

import dev.autonomouscompanions.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/** A memória de uma companheira em texto (comando "memoria" e conversa), sempre dizendo o tipo de cada lembrança. */
public final class MemoryReport {
	private MemoryReport() {
	}

	public static List<String> lines(CompanionEntity c) {
		ServerLevel level = (ServerLevel) c.level();
		WorldMemory memory = WorldMemory.get(level.getServer());
		WorldMemory.Mind mind = memory.mind(c.getUUID());
		long now = level.getGameTime();
		List<String> out = new ArrayList<>();
		out.add("== Memória de " + c.companionName());
		memory.base(c.getUUID()).ifPresentOrElse(
			b -> out.add("Base (" + b.certainty().label() + "): " + b.pos().toShortString() + " em " + b.dimension().identifier().getPath() + "."),
			() -> out.add("Base: nenhuma marcada (use /companheiras base, ou diga a ela \"aqui é a base\")."));
		var seen = memory.sightingSummary(level.dimension());
		if (seen.isEmpty()) {
			out.add("Recursos vistos pela equipe (observações): nenhum ainda.");
		} else {
			out.add("Recursos vistos pela equipe (observações, conferidas antes de usar): " + String.join(", ", seen.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).limit(10)
				.map(e -> e.getValue() + "× " + BuiltInRegistries.BLOCK.getValue(e.getKey()).getName().getString()).toList()) + ".");
		}
		var regions = memory.regions(level.dimension()).stream().filter(WorldMemory.Region::usable)
			.sorted(Comparator.comparingDouble(r -> r.center().distSqr(c.blockPosition()))).limit(5).toList();
		for (var r : regions) {
			out.add(String.format("Hipótese: costuma ter %s perto de %s (%d observações, confiança %.2f).",
				BuiltInRegistries.BLOCK.getValue(r.block()).getName().getString(), r.center().toShortString(), r.observations(), r.confidence()));
		}
		int marks = memory.unreachableNear(c.getUUID(), level.dimension(), c.blockPosition(), 100000, now).size();
		if (marks > 0) {
			out.add("Pontos sem acesso lembrados (observação temporária, expira em 10 min): " + marks + ".");
		}
		var stats = mind.stats().values().stream().sorted(Comparator.comparingInt(WorldMemory.Stat::attempts).reversed()).limit(6).toList();
		for (var s : stats) {
			long avg = s.averageSuccessTicks();
			out.add(String.format("Aprendido — %s: %d tentativa(s), %d sucesso(s)%s%s.", s.key().replace('|', ' '), s.attempts(), s.successes(),
				avg >= 0 ? String.format(", tempo típico %.1f min", avg / 1200.0) : "", s.lastReason().isEmpty() ? "" : "; última falha: " + s.lastReason()));
		}
		var exps = mind.experiences();
		for (int i = Math.max(0, exps.size() - 5); i < exps.size(); i++) {
			var e = exps.get(i);
			out.add(String.format("Experiência (há %.1f min): %s %s → %s%s.", (now - e.time()) / 1200.0, e.activity(), e.target(), e.success() ? "sucesso" : "falha",
				e.reason().isEmpty() ? "" : " (" + e.reason() + ")"));
		}
		if (!mind.preferences().isEmpty()) {
			out.add("Preferências da dona: " + mind.preferences() + ".");
		}
		return out;
	}
}
