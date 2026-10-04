package io.github.roorogeo.griefwatch;

import io.github.roorogeo.griefwatch.command.GriefWatchCommand;
import io.github.roorogeo.griefwatch.config.GriefWatchConfig;
import io.github.roorogeo.griefwatch.core.DedupTracker;
import io.github.roorogeo.griefwatch.core.GriefEvent;
import io.github.roorogeo.griefwatch.core.PlayerSnapshot;
import io.github.roorogeo.griefwatch.core.RuleMatcher;
import io.github.roorogeo.griefwatch.output.DiscordWebhook;
import io.github.roorogeo.griefwatch.output.FileLogger;
import io.github.roorogeo.griefwatch.output.LogDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class GriefWatch implements ModInitializer {
	public static final String MOD_ID = "griefwatch";
	public static final Logger LOGGER = LoggerFactory.getLogger("GriefWatch");

	private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
	private static final double USE_ITEM_REACH = 5.0;

	private static volatile GriefWatchConfig config = new GriefWatchConfig();
	private static FileLogger fileLogger;
	private static DiscordWebhook discord;
	private static LogDispatcher dispatcher;

	// Server-thread state.
	private static final DedupTracker dedup = new DedupTracker();
	private static RuleMatcher matcher = RuleMatcher.compile(List.of(), new ArrayList<>());
	private static DedupTracker.Settings dedupSettings = settingsOf(config);
	private static volatile boolean watching;
	private static volatile boolean watchingItemUse;
	private static int tickCounter;
	private static long nextPlayerLogAt;

	@Override
	public void onInitialize() {
		GriefWatchConfig.LoadResult loaded = GriefWatchConfig.load(CONFIG_FILE, new GriefWatchConfig());
		config = loaded.config();
		loaded.warnings().forEach(w -> LOGGER.warn("[GriefWatch] {}", w));
		if (loaded.createdDefault()) {
			LOGGER.info("[GriefWatch] Created default config at {}", CONFIG_FILE);
		}

		fileLogger = new FileLogger(logDirectory(config), LOGGER);
		discord = new DiscordWebhook(LOGGER);
		discord.configure(config.discord.maxQueueSize, config.discord.username, config.discord.avatarUrl);
		dispatcher = new LogDispatcher(() -> config, fileLogger, discord, LOGGER);
		fileLogger.start();
		discord.start();

		ServerLifecycleEvents.SERVER_STARTED.register(GriefWatch::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(GriefWatch::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(GriefWatch::onServerStopped);
		ServerTickEvents.END_SERVER_TICK.register(GriefWatch::onEndTick);
		CommandRegistrationCallback.EVENT.register((commandDispatcher, registryAccess, environment) ->
				GriefWatchCommand.register(commandDispatcher));
	}

	// ---------------------------------------------------------------- lifecycle

	private static void onServerStarted(MinecraftServer startedServer) {
		List<String> warnings = new ArrayList<>();
		apply(config, warnings);
		warnings.forEach(w -> LOGGER.warn("[GriefWatch] {}", w));
		dispatcher.note("Server started; watching " + matcher.size() + " rule(s).");
		LOGGER.info("[GriefWatch] Active with {} watched rule(s). Logs: {}", matcher.size(), logDirectory(config));
	}

	private static void onServerStopping(MinecraftServer stoppingServer) {
		List<DedupTracker.Summary> closed = new ArrayList<>();
		dedup.closeAll(closed);
		closed.forEach(dispatcher::griefSummary);
		dispatcher.note("Server stopping.");
		watching = false;
		watchingItemUse = false;
	}

	private static void onServerStopped(MinecraftServer stoppedServer) {
		if (stoppedServer.isDedicatedServer()) {
			// The JVM is about to exit: give queued lines and webhooks a moment to go out.
			// (On an integrated server the workers stay up for the next world.)
			fileLogger.shutdown(3000);
			discord.shutdown(5000);
		}
	}

	private static void onEndTick(MinecraftServer tickingServer) {
		if (++tickCounter < 20) {
			return;
		}
		tickCounter = 0;
		try {
			long now = System.currentTimeMillis();
			List<DedupTracker.Summary> closed = new ArrayList<>();
			dedup.sweep(now, dedupSettings, closed);
			closed.forEach(dispatcher::griefSummary);

			GriefWatchConfig cfg = config;
			if (cfg.playerLog.enabled && now >= nextPlayerLogAt) {
				nextPlayerLogAt = now + cfg.playerLog.intervalSeconds * 1000L;
				List<ServerPlayer> online = tickingServer.getPlayerList().getPlayers();
				if (!online.isEmpty() || cfg.playerLog.logWhenEmpty) {
					List<PlayerSnapshot> players = new ArrayList<>(online.size());
					for (ServerPlayer p : online) {
						players.add(new PlayerSnapshot(p.getName().getString(), p.getUUID()));
					}
					dispatcher.playerList(players, Instant.now());
				}
			}
		} catch (RuntimeException e) {
			LOGGER.error("[GriefWatch] Error in tick handler", e);
		}
	}

	// ---------------------------------------------------------------- hooks from the mixin

	public static boolean isWatching() {
		return watching;
	}

	public static boolean isWatchingItemUse() {
		return watchingItemUse;
	}

	public static void onUseItemOn(ServerPlayer player, Level level, ItemStack before, BlockState stateBefore,
			BlockHitResult hit, InteractionResult result) {
		try {
			List<GriefEvent> events = new ArrayList<>(1);
			matcher.onUseItemOn(player, level, before, stateBefore, hit.getBlockPos(), hit.getDirection(),
					result.consumesAction(), events);
			events.forEach(GriefWatch::record);
		} catch (RuntimeException e) {
			LOGGER.error("[GriefWatch] Error while checking a block interaction", e);
		}
	}

	public static void onUseItem(ServerPlayer player, Level level, ItemStack before, InteractionResult result) {
		if (!result.consumesAction()) {
			return;
		}
		try {
			// Use the block the player was looking at (where a bucket was emptied); fall back to their feet.
			BlockPos where = player.blockPosition();
			HitResult look = player.pick(USE_ITEM_REACH, 1.0f, true);
			if (look instanceof BlockHitResult blockHit && look.getType() == HitResult.Type.BLOCK) {
				where = blockHit.getBlockPos().relative(blockHit.getDirection());
			}
			List<GriefEvent> events = new ArrayList<>(1);
			matcher.onUseItem(player, level, before, where, events);
			events.forEach(GriefWatch::record);
		} catch (RuntimeException e) {
			LOGGER.error("[GriefWatch] Error while checking an item use", e);
		}
	}

	private static void record(GriefEvent event) {
		List<DedupTracker.Summary> closed = new ArrayList<>();
		boolean isNew = dedup.submit(event, System.currentTimeMillis(), dedupSettings, closed);
		closed.forEach(dispatcher::griefSummary);
		if (isNew) {
			dispatcher.griefEvent(event);
		}
	}

	// ---------------------------------------------------------------- config

	/**
	 * Reads the config on a background thread, then applies it on the server thread.
	 * {@code feedback} receives one message per line and is invoked on the server thread.
	 */
	public static void reloadAsync(MinecraftServer srv, Consumer<String> feedback, Consumer<String> failure) {
		GriefWatchConfig previous = config;
		CompletableFuture.supplyAsync(() -> GriefWatchConfig.load(CONFIG_FILE, previous))
				.whenComplete((loaded, error) -> srv.execute(() -> {
					if (error != null) {
						LOGGER.error("[GriefWatch] Reload failed", error);
						failure.accept("GriefWatch reload failed: " + error.getMessage());
						return;
					}
					loaded.warnings().forEach(w -> LOGGER.warn("[GriefWatch] {}", w));
					if (loaded.failed()) {
						loaded.warnings().forEach(failure);
						return;
					}
					List<String> warnings = new ArrayList<>();
					apply(loaded.config(), warnings);
					warnings.forEach(w -> LOGGER.warn("[GriefWatch] {}", w));
					loaded.warnings().forEach(failure);
					warnings.forEach(failure);
					dispatcher.note("Config reloaded; watching " + matcher.size() + " rule(s).");
					feedback.accept("GriefWatch config reloaded: " + matcher.size() + " watched rule(s), "
							+ (loaded.warnings().size() + warnings.size()) + " warning(s).");
				}));
	}

	/** Server thread only. */
	private static void apply(GriefWatchConfig cfg, List<String> warnings) {
		GriefWatchConfig old = config;
		config = cfg;
		matcher = RuleMatcher.compile(cfg.griefLog.watched, warnings);
		DedupTracker.Settings newSettings = settingsOf(cfg);
		if (!newSettings.equals(dedupSettings)) {
			// Close groups opened under the old radius/window so their summaries stay meaningful.
			List<DedupTracker.Summary> closed = new ArrayList<>();
			dedup.closeAll(closed);
			closed.forEach(dispatcher::griefSummary);
		}
		dedupSettings = newSettings;
		watching = cfg.griefLog.enabled && !matcher.isEmpty();
		watchingItemUse = watching && matcher.hasUseRules();
		fileLogger.setDirectory(logDirectory(cfg));
		discord.configure(cfg.discord.maxQueueSize, cfg.discord.username, cfg.discord.avatarUrl);
		if (old.playerLog.intervalSeconds != cfg.playerLog.intervalSeconds || nextPlayerLogAt == 0) {
			nextPlayerLogAt = System.currentTimeMillis() + cfg.playerLog.intervalSeconds * 1000L;
		}
		if (cfg.discord.enabled && cfg.discord.playerListUrl().isEmpty() && cfg.discord.griefUrl().isEmpty()) {
			LOGGER.info("[GriefWatch] No Discord webhook configured; logging to file only.");
		}
	}

	public static String status() {
		GriefWatchConfig cfg = config;
		return "GriefWatch: " + matcher.size() + " watched rule(s), grief log " + onOff(cfg.griefLog.enabled)
				+ ", player log " + onOff(cfg.playerLog.enabled) + " (every " + cfg.playerLog.intervalSeconds + "s)"
				+ ", dedup " + onOff(cfg.deduplication.enabled) + " (" + cfg.deduplication.radius + " blocks / "
				+ cfg.deduplication.windowSeconds + "s, " + dedup.openGroups() + " open group(s))"
				+ ", Discord " + onOff(cfg.discord.enabled) + " (" + discord.queued() + " queued)";
	}

	private static String onOff(boolean b) {
		return b ? "on" : "off";
	}

	private static DedupTracker.Settings settingsOf(GriefWatchConfig cfg) {
		return new DedupTracker.Settings(cfg.deduplication.enabled, cfg.deduplication.radius,
				cfg.deduplication.windowSeconds * 1000L, cfg.deduplication.maxGroupSeconds * 1000L);
	}

	private static Path logDirectory(GriefWatchConfig cfg) {
		return FabricLoader.getInstance().getGameDir().resolve(cfg.fileLog.directory).normalize();
	}
}
