package io.github.roorogeo.griefwatch.mixin;

import io.github.roorogeo.griefwatch.GriefWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.LevelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lava meeting water (LiquidBlock / LavaFluid) places cobblestone, stone, obsidian or basalt and
 * then fires {@link LevelEvent#LAVA_FIZZ} at that position, so this one hook sees every block a
 * lava or water cast creates. World generation uses a different level class and is not seen.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Inject(method = "levelEvent", at = @At("HEAD"))
	private void griefwatch$levelEvent(Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
		if (type == LevelEvent.LAVA_FIZZ && GriefWatch.isWatchingCasts()) {
			GriefWatch.onLavaFizz((ServerLevel) (Object) this, pos);
		}
	}
}
