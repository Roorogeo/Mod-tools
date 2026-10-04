package io.github.roorogeo.griefwatch.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Root of config/griefwatch.json. Gson fills these fields; any field missing from the
 * file keeps the default assigned here.
 */
public final class GriefWatchConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Set<String> TRIGGERS = Set.of(WatchRule.TRIGGER_PLACE, WatchRule.TRIGGER_USE, WatchRule.TRIGGER_INTERACT);

	public PlayerLog playerLog = new PlayerLog();
	public GriefLog griefLog = new GriefLog();
	public Deduplication deduplication = new Deduplication();
	public Discord discord = new Discord();
	public FileLog fileLog = new FileLog();

	public static final class PlayerLog {
		public boolean enabled = true;
		public int intervalSeconds = 300;
		/** When false, intervals with nobody online are skipped instead of logging "no players online". */
		public boolean logWhenEmpty = true;
	}

	public static final class GriefLog {
		public boolean enabled = true;
		/** Also echo grief entries into the main server log. */
		public boolean logToConsole = true;
		public List<WatchRule> watched = WatchRule.defaults();
	}

	public static final class Deduplication {
		public boolean enabled = true;
		public double radius = 16.0;
		/** A group closes once no matching event has happened for this many seconds. */
		public int windowSeconds = 30;
		/** Hard cap on how long one group may stay open, so a non-stop griefer still gets periodic summaries. */
		public int maxGroupSeconds = 600;
	}

	public static final class Discord {
		public boolean enabled = true;
		/** Shared webhook, used for any log type that has no dedicated URL below. */
		public String webhookUrl = "";
		public String playerListWebhookUrl = "";
		public String griefWebhookUrl = "";
		public boolean sendPlayerList = true;
		public boolean sendGriefAlerts = true;
		public boolean sendSummaries = true;
		public String username = "GriefWatch";
		public String avatarUrl = "";
		/** Messages waiting to be sent; when full, the oldest is dropped. */
		public int maxQueueSize = 200;
		public int playerListColor = 0x3498DB;
		public int griefAlertColor = 0xE74C3C;
		public int griefSummaryColor = 0xE67E22;

		public String playerListUrl() {
			return firstNonBlank(playerListWebhookUrl, webhookUrl);
		}

		public String griefUrl() {
			return firstNonBlank(griefWebhookUrl, webhookUrl);
		}
	}

	public static final class FileLog {
		public boolean enabled = true;
		/** Relative to the server directory. */
		public String directory = "logs/griefwatch";
	}

	/** Result of {@link #load}: the config to use plus human-readable warnings. */
	public record LoadResult(GriefWatchConfig config, List<String> warnings, boolean createdDefault, boolean failed) {
	}

	/**
	 * Reads the config, creating it with defaults if missing. A file that cannot be parsed is
	 * never overwritten; {@code fallback} is returned instead so a typo during /griefwatch reload
	 * keeps the previous settings.
	 */
	public static LoadResult load(Path file, GriefWatchConfig fallback) {
		List<String> warnings = new ArrayList<>();
		if (Files.notExists(file)) {
			GriefWatchConfig defaults = new GriefWatchConfig();
			try {
				write(file, defaults);
			} catch (IOException e) {
				warnings.add("Could not write default config to " + file + ": " + e.getMessage());
			}
			return new LoadResult(defaults, warnings, true, false);
		}

		GriefWatchConfig config;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			config = GSON.fromJson(reader, GriefWatchConfig.class);
		} catch (IOException | JsonParseException e) {
			warnings.add("Could not read " + file + " (" + e.getMessage() + "); keeping previous settings.");
			return new LoadResult(fallback, warnings, false, true);
		}
		if (config == null) {
			warnings.add(file + " is empty; keeping previous settings.");
			return new LoadResult(fallback, warnings, false, true);
		}
		config.sanitize(warnings);
		return new LoadResult(config, warnings, false, false);
	}

	public static void write(Path file, GriefWatchConfig config) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(config, writer);
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	private void sanitize(List<String> warnings) {
		if (playerLog == null) playerLog = new PlayerLog();
		if (griefLog == null) griefLog = new GriefLog();
		if (deduplication == null) deduplication = new Deduplication();
		if (discord == null) discord = new Discord();
		if (fileLog == null) fileLog = new FileLog();
		if (griefLog.watched == null) griefLog.watched = new ArrayList<>();

		if (playerLog.intervalSeconds < 10) {
			warnings.add("playerLog.intervalSeconds must be at least 10; using 10.");
			playerLog.intervalSeconds = 10;
		}
		if (!(deduplication.radius >= 0)) {
			warnings.add("deduplication.radius must be >= 0; using 16.");
			deduplication.radius = 16.0;
		}
		if (deduplication.windowSeconds < 1) {
			warnings.add("deduplication.windowSeconds must be >= 1; using 30.");
			deduplication.windowSeconds = 30;
		}
		if (deduplication.maxGroupSeconds < deduplication.windowSeconds) {
			deduplication.maxGroupSeconds = Math.max(deduplication.windowSeconds, 600);
		}
		if (discord.maxQueueSize < 1) {
			discord.maxQueueSize = 200;
		}
		if (fileLog.directory == null || fileLog.directory.isBlank()) {
			fileLog.directory = "logs/griefwatch";
		}
		discord.webhookUrl = checkUrl("discord.webhookUrl", discord.webhookUrl, warnings);
		discord.playerListWebhookUrl = checkUrl("discord.playerListWebhookUrl", discord.playerListWebhookUrl, warnings);
		discord.griefWebhookUrl = checkUrl("discord.griefWebhookUrl", discord.griefWebhookUrl, warnings);

		List<WatchRule> valid = new ArrayList<>();
		for (WatchRule rule : griefLog.watched) {
			if (rule == null || rule.id == null || rule.id.isBlank()) {
				warnings.add("Ignoring a watched entry with no id.");
				continue;
			}
			rule.id = rule.id.trim();
			rule.trigger = rule.trigger == null ? WatchRule.TRIGGER_PLACE : rule.trigger.trim().toLowerCase();
			if (!TRIGGERS.contains(rule.trigger)) {
				warnings.add("Ignoring watched entry " + rule.id + ": unknown trigger '" + rule.trigger + "' (use place, use or interact).");
				continue;
			}
			if (rule.dimensions == null) {
				rule.dimensions = new ArrayList<>();
			}
			valid.add(rule);
		}
		griefLog.watched = valid;
	}

	/** Blank stays blank (Discord off for that type); an unusable URL is reported and disabled. */
	private static String checkUrl(String name, String url, List<String> warnings) {
		if (url == null || url.isBlank()) {
			return "";
		}
		String trimmed = url.trim();
		try {
			URI uri = URI.create(trimmed);
			String scheme = uri.getScheme();
			if (uri.getHost() == null || scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
				throw new IllegalArgumentException("not an http(s) URL");
			}
		} catch (IllegalArgumentException e) {
			warnings.add(name + " is not a valid URL (" + e.getMessage() + "); Discord disabled for it.");
			return "";
		}
		return trimmed;
	}

	private static String firstNonBlank(String a, String b) {
		if (a != null && !a.isBlank()) return a;
		if (b != null && !b.isBlank()) return b;
		return "";
	}
}
