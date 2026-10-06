package io.github.roorogeo.griefwatch.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.roorogeo.griefwatch.GriefWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * {@code checkBurnOut} is where fire destroys a flammable neighbour (removing it or turning it
 * into fire). Comparing the block before and after tells us whether it burned.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
	@WrapMethod(method = "checkBurnOut")
	private void griefwatch$checkBurnOut(Level level, BlockPos pos, int chance, RandomSource random, int age,
			Operation<Void> original) {
		if (!GriefWatch.isWatchingFire()) {
			original.call(level, pos, chance, random, age);
			return;
		}
		BlockState before = level.getBlockState(pos);
		original.call(level, pos, chance, random, age);
		if (!before.isAir() && level.getBlockState(pos) != before) {
			GriefWatch.onBlockBurned(level, pos, before);
		}
	}
}
