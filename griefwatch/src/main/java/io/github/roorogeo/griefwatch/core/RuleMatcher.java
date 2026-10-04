package io.github.roorogeo.griefwatch.core;

import io.github.roorogeo.griefwatch.config.WatchRule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The watched list from the config, resolved against the game registries. Rebuilt on every
 * (re)load; used on the server thread only.
 */
public final class RuleMatcher {
	private record Compiled(String trigger, String action, String label, Identifier id,
			TagKey<Item> itemTag, TagKey<Block> blockTag, Set<String> dimensions) {

		boolean inDimension(String dimension) {
			return dimensions.isEmpty() || dimensions.contains(dimension);
		}

		boolean matches(ItemStack stack) {
			if (stack.isEmpty()) return false;
			return itemTag != null ? stack.is(itemTag) : BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
		}

		boolean matches(BlockState state) {
			return blockTag != null ? state.is(blockTag) : BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(id);
		}
	}

	private final List<Compiled> placeRules = new ArrayList<>();
	private final List<Compiled> useRules = new ArrayList<>();
	private final List<Compiled> interactRules = new ArrayList<>();

	public static RuleMatcher compile(List<WatchRule> rules, List<String> warnings) {
		RuleMatcher matcher = new RuleMatcher();
		for (WatchRule rule : rules) {
			if (!rule.enabled) continue;
			boolean isTag = rule.id.startsWith("#");
			Identifier id = Identifier.tryParse(isTag ? rule.id.substring(1) : rule.id);
			if (id == null) {
				warnings.add("Ignoring watched entry '" + rule.id + "': not a valid id.");
				continue;
			}
			boolean blockRule = WatchRule.TRIGGER_INTERACT.equals(rule.trigger);
			if (!isTag) {
				boolean known = blockRule ? BuiltInRegistries.BLOCK.containsKey(id) : BuiltInRegistries.ITEM.containsKey(id);
				if (!known) {
					warnings.add("Watched entry '" + rule.id + "' is not a known " + (blockRule ? "block" : "item")
							+ " (trigger '" + rule.trigger + "' matches " + (blockRule ? "blocks" : "items") + "); it will never match.");
				}
			}
			Set<String> dims = new HashSet<>();
			for (String d : rule.dimensions) {
				if (d == null || d.isBlank()) continue;
				d = d.trim();
				dims.add(d.contains(":") ? d : "minecraft:" + d);
			}
			Compiled compiled = new Compiled(rule.trigger, rule.effectiveAction(), rule.effectiveLabel(), id,
					isTag && !blockRule ? TagKey.create(Registries.ITEM, id) : null,
					isTag && blockRule ? TagKey.create(Registries.BLOCK, id) : null,
					Set.copyOf(dims));
			switch (rule.trigger) {
				case WatchRule.TRIGGER_USE -> matcher.useRules.add(compiled);
				case WatchRule.TRIGGER_INTERACT -> matcher.interactRules.add(compiled);
				default -> matcher.placeRules.add(compiled);
			}
		}
		return matcher;
	}

	public boolean isEmpty() {
		return placeRules.isEmpty() && useRules.isEmpty() && interactRules.isEmpty();
	}

	public int size() {
		return placeRules.size() + useRules.size() + interactRules.size();
	}

	/**
	 * A right-click on a block finished.
	 *
	 * @param before      copy of the held stack before the click (the real one may now be empty)
	 * @param stateBefore the clicked block before the click
	 * @param succeeded   whether the interaction consumed the action
	 */
	public void onUseItemOn(ServerPlayer player, Level level, ItemStack before, BlockState stateBefore,
			BlockPos hitPos, Direction face, boolean succeeded, List<GriefEvent> out) {
		String dimension = level.dimension().identifier().toString();
		BlockState stateAfter = level.getBlockState(hitPos);
		boolean blockChanged = !stateAfter.is(stateBefore.getBlock());

		// "interact": the clicked block itself was consumed (TNT primed, bed/anchor exploded).
		if (blockChanged && !interactRules.isEmpty()) {
			boolean fired = false;
			for (Compiled rule : interactRules) {
				if (rule.inDimension(dimension) && rule.matches(stateBefore)) {
					out.add(event(player, rule, BuiltInRegistries.BLOCK.getKey(stateBefore.getBlock()).toString(), dimension, hitPos));
					fired = true;
				}
			}
			if (fired) {
				return; // e.g. "ignited TNT" says more than "used flint and steel"
			}
		}
		if (!succeeded || before.isEmpty()) {
			return;
		}

		// A placed block lands in the clicked position if that was replaceable (grass, snow),
		// otherwise against the clicked face.
		BlockPos placedPos = blockChanged ? hitPos : hitPos.relative(face);
		String itemId = BuiltInRegistries.ITEM.getKey(before.getItem()).toString();
		for (Compiled rule : placeRules) {
			if (rule.inDimension(dimension) && rule.matches(before)) {
				out.add(event(player, rule, itemId, dimension, placedPos));
			}
		}
		for (Compiled rule : useRules) {
			if (rule.inDimension(dimension) && rule.matches(before)) {
				out.add(event(player, rule, itemId, dimension, placedPos));
			}
		}
	}

	/** A right-click with an item that did not target a block (buckets, fire charges thrown, ...). */
	public void onUseItem(ServerPlayer player, Level level, ItemStack before, BlockPos where, List<GriefEvent> out) {
		if (before.isEmpty() || useRules.isEmpty()) {
			return;
		}
		String dimension = level.dimension().identifier().toString();
		String itemId = BuiltInRegistries.ITEM.getKey(before.getItem()).toString();
		for (Compiled rule : useRules) {
			if (rule.inDimension(dimension) && rule.matches(before)) {
				out.add(event(player, rule, itemId, dimension, where));
			}
		}
	}

	public boolean hasUseRules() {
		return !useRules.isEmpty();
	}

	private static GriefEvent event(ServerPlayer player, Compiled rule, String targetId, String dimension, BlockPos pos) {
		return new GriefEvent(player.getName().getString(), player.getUUID(), rule.action(), rule.label(), targetId,
				dimension, pos.getX(), pos.getY(), pos.getZ(), Instant.now());
	}
}
