package io.github.roorogeo.griefwatch.output;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.roorogeo.griefwatch.config.GriefWatchConfig;
import io.github.roorogeo.griefwatch.core.DedupTracker;
import io.github.roorogeo.griefwatch.core.GriefEvent;
import io.github.roorogeo.griefwatch.core.PlayerSnapshot;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Turns log entries into text lines and Discord embeds and hands them to the file and webhook
 * workers. Called on the server thread; only does string formatting and queueing.
 */
public final class LogDispatcher {
	private static final int MAX_DESCRIPTION = 4000; // Discord allows 4096

	private final Supplier<GriefWatchConfig> config;
	private final FileLogger file;
	private final DiscordWebhook discord;
	private final Logger console;

	public LogDispatcher(Supplier<GriefWatchConfig> config, FileLogger file, DiscordWebhook discord, Logger console) {
		this.config = config;
		this.file = file;
		this.discord = discord;
		this.console = console;
	}

	public void playerList(List<PlayerSnapshot> players, Instant now) {
		GriefWatchConfig cfg = config.get();
		String line;
		if (players.isEmpty()) {
			line = "[PLAYERS] No players online";
		} else {
			StringBuilder sb = new StringBuilder("[PLAYERS] ").append(players.size()).append(" online: ");
			for (int i = 0; i < players.size(); i++) {
				PlayerSnapshot p = players.get(i);
				if (i > 0) sb.append(", ");
				sb.append(p.name()).append(" (").append(p.id()).append(')');
			}
			line = sb.toString();
		}
		toFile(cfg, line);

		if (cfg.discord.enabled && cfg.discord.sendPlayerList) {
			String url = cfg.discord.playerListUrl();
			if (!url.isEmpty()) {
				JsonObject embed = embed(players.isEmpty() ? "No players online" : "Online players: " + players.size(),
						cfg.discord.playerListColor, now);
				if (!players.isEmpty()) {
					StringBuilder desc = new StringBuilder();
					for (int i = 0; i < players.size(); i++) {
						PlayerSnapshot p = players.get(i);
						String entry = "**" + escape(p.name()) + "** `" + p.id() + "`\n";
						if (desc.length() + entry.length() > MAX_DESCRIPTION - 40) {
							desc.append("... and ").append(players.size() - i).append(" more");
							break;
						}
						desc.append(entry);
					}
					embed.addProperty("description", desc.toString().trim());
				}
				discord.enqueue(url, embed);
			}
		}
	}

	public void griefEvent(GriefEvent e) {
		GriefWatchConfig cfg = config.get();
		String dim = GriefEvent.shortDimension(e.dimension());
		String line = String.format("[GRIEF] %s (%s) %s %s [%s] at %d, %d, %d in %s",
				e.playerName(), e.playerId(), e.action(), e.target(), e.targetId(), e.x(), e.y(), e.z(), dim);
		toFile(cfg, line);
		if (cfg.griefLog.logToConsole) {
			console.info("[GriefWatch] {}", line);
		}

		if (cfg.discord.enabled && cfg.discord.sendGriefAlerts) {
			String url = cfg.discord.griefUrl();
			if (!url.isEmpty()) {
				JsonObject embed = embed(e.playerName() + " " + e.action() + " " + e.target(), cfg.discord.griefAlertColor, e.timestamp());
				JsonArray fields = new JsonArray();
				fields.add(field("Player", escape(e.playerName()), true));
				fields.add(field("UUID", "`" + e.playerId() + "`", true));
				fields.add(field("Action", e.action() + " `" + e.targetId() + "`", true));
				fields.add(field("Dimension", dim, true));
				fields.add(field("Coordinates", "`" + e.x() + ", " + e.y() + ", " + e.z() + "`", true));
				embed.add("fields", fields);
				discord.enqueue(url, embed);
			}
		}
	}

	public void griefSummary(DedupTracker.Summary s) {
		GriefWatchConfig cfg = config.get();
		GriefEvent e = s.first();
		String dim = GriefEvent.shortDimension(e.dimension());
		long seconds = Math.max(1, Math.round(s.durationMillis() / 1000.0));
		String text = String.format("%s %s %d %s near %d, %d, %d in %s over %ds",
				e.playerName(), e.action(), s.count(), e.target(), s.centerX(), s.centerY(), s.centerZ(), dim, seconds);
		toFile(cfg, "[GRIEF-SUMMARY] " + text + " (" + e.playerId() + ", " + e.targetId() + ")");
		if (cfg.griefLog.logToConsole) {
			console.info("[GriefWatch] [GRIEF-SUMMARY] {}", text);
		}

		if (cfg.discord.enabled && cfg.discord.sendGriefAlerts && cfg.discord.sendSummaries) {
			String url = cfg.discord.griefUrl();
			if (!url.isEmpty()) {
				JsonObject embed = embed("Summary: " + e.playerName() + " " + e.action() + " " + s.count() + " " + e.target(),
						cfg.discord.griefSummaryColor, Instant.now());
				embed.addProperty("description", escape(text));
				JsonArray fields = new JsonArray();
				fields.add(field("UUID", "`" + e.playerId() + "`", true));
				fields.add(field("Count", String.valueOf(s.count()), true));
				fields.add(field("Center", "`" + s.centerX() + ", " + s.centerY() + ", " + s.centerZ() + "`", true));
				embed.add("fields", fields);
				discord.enqueue(url, embed);
			}
		}
	}

	/** Plain line for the file only (startup, reload notes). */
	public void note(String text) {
		toFile(config.get(), "[INFO] " + text);
	}

	private void toFile(GriefWatchConfig cfg, String line) {
		if (cfg.fileLog.enabled) {
			file.log(line);
		}
	}

	private static JsonObject embed(String title, int color, Instant timestamp) {
		JsonObject embed = new JsonObject();
		embed.addProperty("title", abbreviate(escape(title), 256));
		embed.addProperty("color", color & 0xFFFFFF);
		embed.addProperty("timestamp", timestamp.toString());
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "GriefWatch");
		embed.add("footer", footer);
		return embed;
	}

	private static JsonObject field(String name, String value, boolean inline) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("value", abbreviate(value, 1024));
		f.addProperty("inline", inline);
		return f;
	}

	/** Player names can contain underscores, which Discord would render as italics. */
	static String escape(String s) {
		StringBuilder out = new StringBuilder(s.length() + 8);
		for (char c : s.toCharArray()) {
			if ("\\*_~`|>".indexOf(c) >= 0) {
				out.append('\\');
			}
			out.append(c);
		}
		return out.toString();
	}

	private static String abbreviate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 3) + "...";
	}
}
