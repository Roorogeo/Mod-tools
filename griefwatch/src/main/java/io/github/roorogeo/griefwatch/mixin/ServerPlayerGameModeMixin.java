package io.github.roorogeo.griefwatch.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.roorogeo.griefwatch.GriefWatch;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Every right-click a player makes on the server goes through these two methods, so wrapping
 * them sees block placement, item use on blocks (end crystals, minecarts, flint and steel) and
 * item use in the air (buckets) with the before and after state, entirely server-side.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@WrapMethod(method = "useItemOn")
	private InteractionResult griefwatch$useItemOn(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
			BlockHitResult hitResult, Operation<InteractionResult> original) {
		if (!GriefWatch.isWatching()) {
			return original.call(player, level, stack, hand, hitResult);
		}
		// Placing the last item of a stack empties it, so remember what was held.
		ItemStack before = stack.copy();
		BlockState stateBefore = level.getBlockState(hitResult.getBlockPos());
		InteractionResult result = original.call(player, level, stack, hand, hitResult);
		GriefWatch.onUseItemOn(player, level, before, stateBefore, hitResult, result);
		return result;
	}

	@WrapMethod(method = "useItem")
	private InteractionResult griefwatch$useItem(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
			Operation<InteractionResult> original) {
		if (!GriefWatch.isWatchingItemUse()) {
			return original.call(player, level, stack, hand);
		}
		ItemStack before = stack.copy();
		InteractionResult result = original.call(player, level, stack, hand);
		GriefWatch.onUseItem(player, level, before, result);
		return result;
	}
}
