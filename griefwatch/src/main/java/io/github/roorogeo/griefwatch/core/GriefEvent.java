package io.github.roorogeo.griefwatch.core;

import java.time.Instant;
import java.util.UUID;

/**
 * A single watched action, captured on the server thread as plain values so it can be
 * handed to the logging threads without touching game objects.
 *
 * @param action    verb, e.g. "placed"
 * @param target    display name, e.g. "TNT"
 * @param targetId  registry id of the item/block involved, e.g. "minecraft:tnt"
 * @param dimension dimension id, e.g. "minecraft:overworld"
 */
public record GriefEvent(String playerName, UUID playerId, String action, String target, String targetId,
		String dimension, int x, int y, int z, Instant timestamp) {

	/** Events with the same group key may be merged by the deduplicator. */
	public String groupKey() {
		return playerId + "|" + action + "|" + target + "|" + dimension;
	}

	/** "overworld" for "minecraft:overworld", full id for modded dimensions. */
	public static String shortDimension(String dimension) {
		return dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
	}
}
