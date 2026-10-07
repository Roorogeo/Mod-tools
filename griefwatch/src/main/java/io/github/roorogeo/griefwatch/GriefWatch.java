package io.github.roorogeo.griefwatch;

import io.github.roorogeo.griefwatch.command.GriefWatchCommand;
import io.github.roorogeo.griefwatch.config.GriefWatchConfig;
import io.github.roorogeo.griefwatch.core.AreaActivityTracker;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
	private static final AreaActivityTracker fires = new AreaActivityTracker("fire");
	private static final AreaActivityTracker casts = new AreaActivityTracker("cast");
	private static AreaActivityTracker.Settings fireSettings = areaSettingsOf(config.fireLog);
	private static AreaActivityTracker.Settings castSettings = areaSettingsOf(config.castLog);
	private static Set<String> fireSourceItems = Set.of();
	private static Set<String> castSourceItems = Set.of();
	private static Set<String> castBlocks = Set.of();
	private static volatile boolean watching;
	private static volatile boolean watchingItemUse;
	private static volatile boolean watchingFire;
	private static volatile boolean watchingCasts;
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
		LOGGER.info("[GriefWatch] Active with {} watched rule(s), fire log {}, cast log {}. Logs: {}", matcher.size(),
				onOff(config.fireLog.enabled), onOff(config.castLog.enabled), logDirectory(config));
		loadHookedClasses();
	}

	/**
	 * Mixins are applied when their target class loads. ServerPlayerGameMode only loads when the
	 * first player joins, so load every target now: a hook that no longer fits the game then
	 * fails at startup, where it is noticed, instead of when someone logs in.
	 */
	private static void loadHookedClasses() {
		ClassLoader loader = GriefWatch.class.getClassLoader();
		for (String name : List.of("net.minecraft.server.level.ServerPlayerGameMode",
				"net.minecraft.world.level.block.FireBlock", "net.minecraft.server.level.ServerLevel")) {
			try {
				Class.forName(name, false, loader);
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException("[GriefWatch] Missing game class " + name, e);
			}
		}
		LOGGER.info("[GriefWatch] Hooks applied.");
	}

	private static void onServerStopping(MinecraftServer stoppingServer) {
		List<DedupTracker.Summary> closed = new ArrayList<>();
		dedup.closeAll(closed);
		closed.forEach(dispatcher::griefSummary);
		List<AreaActivityTracker.Report> reports = new ArrayList<>();
		fires.closeAll(reports);
		casts.closeAll(reports);
		reports.forEach(GriefWatch::report);
		dispatcher.note("Server stopping.");
		watching = false;
		watchingItemUse = false;
		watchingFire = false;
		watchingCasts = false;
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
			List<AreaActivityTracker.Report> reports = new ArrayList<>();
			fires.sweep(now, fireSettings, reports);
			casts.sweep(now, castSettings, reports);
			reports.forEach(GriefWatch::report);

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

	public static boolean isWatchingFire() {
		return watchingFire;
	}

	public static boolean isWatchingCasts() {
		return watchingCasts;
	}

	/** Fire destroyed {@code before} at {@code pos} (removed it or turned it into fire). */
	public static void onBlockBurned(Level level, BlockPos pos, BlockState before) {
		if (before.getBlock() instanceof BaseFireBlock) {
			return;
		}
		try {
			List<AreaActivityTracker.Report> reports = new ArrayList<>(1);
			fires.submit(dimensionOf(level), pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis(), fireSettings, reports);
			reports.forEach(GriefWatch::report);
		} catch (RuntimeException e) {
			LOGGER.error("[GriefWatch] Error while tracking a burned block", e);
		}
	}

	/** Lava and water met at {@code pos}; the block they made is already in place. */
	public static void onLavaFizz(ServerLevel level, BlockPos pos) {
		try {
			String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
			if (!castBlocks.contains(id)) {
				return;
			}
			List<AreaActivityTracker.Report> reports = new ArrayList<>(1);
			casts.submit(dimensionOf(level), pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis(), castSettings, reports);
			reports.forEach(GriefWatch::report);
		} catch (RuntimeException e) {
			LOGGER.error("[GriefWatch] Error while tracking a lava cast", e);
		}
	}

	/** Remembers players who lit fires or poured fluids, to blame later fires/casts on. */
	private static void recordSources(ServerPlayer player, Level level, ItemStack used, BlockPos pos) {
		if (used.isEmpty() || !(watchingFire || watchingCasts)) {
			return;
		}
		String id = BuiltInRegistries.ITEM.getKey(used.getItem()).toString();
		boolean fire = watchingFire && fireSourceItems.contains(id);
		boolean cast = watchingCasts && castSourceItems.contains(id);
		if (!fire && !cast) {
			return;
		}
		String name = player.getName().getString();
		String dim = dimensionOf(level);
		long now = System.currentTimeMillis();
		if (fire) fires.recordSource(name, player.getUUID(), dim, pos.getX(), pos.getY(), pos.getZ(), now);
		if (cast) casts.recordSource(name, player.getUUID(), dim, pos.getX(), pos.getY(), pos.getZ(), now);
	}

	private static void report(AreaActivityTracker.Report r) {
		boolean fire = "fire".equals(r.kind());
		boolean logUnknown = fire ? config.fireLog.logUnattributed : config.castLog.logUnattributed;
		if (r.attribution().playerName() == null && !logUnknown) {
			return;
		}
		dispatcher.areaReport(r);
	}

	private static String dimensionOf(Level level) {
		return level.dimension().identifier().toString();
	}

	public static void onUseItemOn(ServerPlayer player, Level level, ItemStack before, BlockState stateBefore,
			BlockHitResult hit, InteractionResult result) {
		try {
			List<GriefEvent> events = new ArrayList<>(1);
			matcher.onUseItemOn(player, level, before, stateBefore, hit.getBlockPos(), hit.getDirection(),
					result.consumesAction(), events);
			events.forEach(GriefWatch::record);
			if (result.consumesAction()) {
				recordSources(player, level, before, hit.getBlockPos().relative(hit.getDirection()));
			}
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
			recordSources(player, level, before, where);
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
		matcher = RuleMatcher.compile(cfg.griefLog.enabled ? cfg.griefLog.watched : List.of(), warnings);
		DedupTracker.Settings newSettings = settingsOf(cfg);
		if (!newSettings.equals(dedupSettings)) {
			// Close groups opened under the old radius/window so their summaries stay meaningful.
			List<DedupTracker.Summary> closed = new ArrayList<>();
			dedup.closeAll(closed);
			closed.forEach(dispatcher::griefSummary);
		}
		dedupSettings = newSettings;

		AreaActivityTracker.Settings newFire = areaSettingsOf(cfg.fireLog);
		AreaActivityTracker.Settings newCast = areaSettingsOf(cfg.castLog);
		List<AreaActivityTracker.Report> reports = new ArrayList<>();
		if (!newFire.equals(fireSettings)) fires.closeAll(reports);
		if (!newCast.equals(castSettings)) casts.closeAll(reports);
		reports.forEach(GriefWatch::report);
		fireSettings = newFire;
		castSettings = newCast;
		fireSourceItems = Set.copyOf(cfg.fireLog.sourceItems);
		castSourceItems = Set.copyOf(cfg.castLog.sourceItems);
		castBlocks = Set.copyOf(cfg.castLog.castBlocks);
		warnUnknownIds("fireLog.sourceItems", cfg.fireLog.sourceItems, false, warnings);
		warnUnknownIds("castLog.sourceItems", cfg.castLog.sourceItems, false, warnings);
		warnUnknownIds("castLog.castBlocks", cfg.castLog.castBlocks, true, warnings);

		watchingFire = cfg.fireLog.enabled;
		watchingCasts = cfg.castLog.enabled && !castBlocks.isEmpty();
		boolean needSources = (watchingFire && !fireSourceItems.isEmpty()) || (watchingCasts && !castSourceItems.isEmpty());
		watching = !matcher.isEmpty() || needSources;
		watchingItemUse = matcher.hasUseRules() || needSources;
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
				+ ", fire log " + onOff(cfg.fireLog.enabled) + " (" + fires.openClusters() + " active)"
				+ ", cast log " + onOff(cfg.castLog.enabled) + " (" + casts.openClusters() + " active)"
				+ ", Discord " + onOff(cfg.discord.enabled) + " (" + discord.queued() + " queued)";
	}

	private static String onOff(boolean b) {
		return b ? "on" : "off";
	}

	private static DedupTracker.Settings settingsOf(GriefWatchConfig cfg) {
		return new DedupTracker.Settings(cfg.deduplication.enabled, cfg.deduplication.radius,
				cfg.deduplication.windowSeconds * 1000L, cfg.deduplication.maxGroupSeconds * 1000L);
	}

	private static AreaActivityTracker.Settings areaSettingsOf(GriefWatchConfig.AreaLog log) {
		return new AreaActivityTracker.Settings(log.enabled, log.sourceRadius, log.sourceHeight, log.joinRadius,
				log.alertThreshold, log.idleSeconds * 1000L, log.sourceMaxAgeSeconds * 1000L, log.maxAreaSeconds * 1000L);
	}

	private static void warnUnknownIds(String setting, List<String> ids, boolean blocks, List<String> warnings) {
		for (String raw : ids) {
			Identifier id = Identifier.tryParse(raw);
			boolean known = id != null && (blocks ? BuiltInRegistries.BLOCK.containsKey(id) : BuiltInRegistries.ITEM.containsKey(id));
			if (!known) {
				warnings.add(setting + ": '" + raw + "' is not a known " + (blocks ? "block" : "item") + " id.");
			}
		}
	}

	private static Path logDirectory(GriefWatchConfig cfg) {
		return FabricLoader.getInstance().getGameDir().resolve(cfg.fileLog.directory).normalize();
	}
}
