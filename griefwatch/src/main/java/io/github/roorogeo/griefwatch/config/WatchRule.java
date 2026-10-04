package io.github.roorogeo.griefwatch.config;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry of the "watched" list in the config.
 *
 * <ul>
 *   <li>{@code id}: an item/block id ({@code minecraft:tnt}) or a tag ({@code #minecraft:beds}).</li>
 *   <li>{@code trigger}:
 *     <ul>
 *       <li>{@code place} - the player successfully used this item on a block (placing a block,
 *           an end crystal, a TNT minecart, ...).</li>
 *       <li>{@code use} - the player successfully used this item, on a block or in the air
 *           (flint and steel, lava bucket, fire charge, ...).</li>
 *       <li>{@code interact} - the player right-clicked a block with this id and the block was
 *           removed or replaced as a result (igniting TNT, detonating a bed in the Nether or a
 *           respawn anchor in the Overworld).</li>
 *     </ul>
 *   </li>
 *   <li>{@code action}: verb used in log lines; defaults to placed / used / activated.</li>
 *   <li>{@code label}: display name; defaults to a name derived from the id.</li>
 *   <li>{@code dimensions}: only log in these dimensions (empty = everywhere).</li>
 * </ul>
 */
public final class WatchRule {
	public static final String TRIGGER_PLACE = "place";
	public static final String TRIGGER_USE = "use";
	public static final String TRIGGER_INTERACT = "interact";

	public boolean enabled = true;
	public String id = "";
	public String trigger = TRIGGER_PLACE;
	public String action;
	public String label;
	public List<String> dimensions = new ArrayList<>();

	public WatchRule() {
	}

	public static WatchRule of(boolean enabled, String id, String trigger, String action, String label, String... dimensions) {
		WatchRule rule = new WatchRule();
		rule.enabled = enabled;
		rule.id = id;
		rule.trigger = trigger;
		rule.action = action;
		rule.label = label;
		rule.dimensions = new ArrayList<>(List.of(dimensions));
		return rule;
	}

	public String effectiveAction() {
		if (action != null && !action.isBlank()) {
			return action.trim();
		}
		return switch (trigger == null ? "" : trigger) {
			case TRIGGER_USE -> "used";
			case TRIGGER_INTERACT -> "activated";
			default -> "placed";
		};
	}

	public String effectiveLabel() {
		if (label != null && !label.isBlank()) {
			return label.trim();
		}
		String path = id == null ? "" : id.trim();
		if (path.startsWith("#")) {
			path = path.substring(1);
		}
		int colon = path.indexOf(':');
		if (colon >= 0) {
			path = path.substring(colon + 1);
		}
		StringBuilder out = new StringBuilder();
		for (String word : path.split("[_/]")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.isEmpty() ? String.valueOf(id) : out.toString();
	}

	public static List<WatchRule> defaults() {
		List<WatchRule> list = new ArrayList<>();
		list.add(of(true, "minecraft:tnt", TRIGGER_PLACE, "placed", "TNT"));
		list.add(of(true, "minecraft:tnt", TRIGGER_INTERACT, "ignited", "TNT"));
		list.add(of(true, "minecraft:end_crystal", TRIGGER_PLACE, "placed", "End Crystal"));
		list.add(of(true, "minecraft:tnt_minecart", TRIGGER_PLACE, "placed", "TNT Minecart"));
		// Shipped disabled: flip "enabled" to true in config/griefwatch.json to watch these.
		list.add(of(false, "minecraft:respawn_anchor", TRIGGER_PLACE, "placed", "Respawn Anchor",
				"minecraft:overworld", "minecraft:the_end"));
		list.add(of(false, "minecraft:respawn_anchor", TRIGGER_INTERACT, "detonated", "Respawn Anchor"));
		list.add(of(false, "#minecraft:beds", TRIGGER_PLACE, "placed", "Bed",
				"minecraft:the_nether", "minecraft:the_end"));
		list.add(of(false, "#minecraft:beds", TRIGGER_INTERACT, "detonated", "Bed",
				"minecraft:the_nether", "minecraft:the_end"));
		list.add(of(false, "minecraft:flint_and_steel", TRIGGER_USE, "used", "Flint and Steel"));
		list.add(of(false, "minecraft:fire_charge", TRIGGER_USE, "used", "Fire Charge"));
		list.add(of(false, "minecraft:lava_bucket", TRIGGER_USE, "emptied", "Lava Bucket"));
		list.add(of(false, "minecraft:wither_skeleton_skull", TRIGGER_PLACE, "placed", "Wither Skeleton Skull"));
		return list;
	}
}
