package com.pixelreel.poster;

import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.registry.ModBlockEntities;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/** Finds nearby screens so a hanging can follow one of them. */
public final class WatchingDisplays {
	public static final int RANGE = 48;

	private WatchingDisplays() {
	}

	public static @Nullable DisplayBlockEntity at(Level level, @Nullable BlockPos pos) {
		if (level == null || pos == null) {
			return null;
		}
		return level.getBlockEntity(pos) instanceof DisplayBlockEntity display ? display : null;
	}

	public static @Nullable DisplayBlockEntity nearestPlaying(Level level, BlockPos from) {
		DisplayBlockEntity best = null;
		double bestDist = (double) RANGE * RANGE;
		for (DisplayBlockEntity display : nearby(level, from)) {
			if (!display.shouldPlay()) {
				continue;
			}
			double dist = display.getBlockPos().distSqr(from);
			if (dist < bestDist) {
				bestDist = dist;
				best = display;
			}
		}
		return best;
	}

	public static List<DisplayBlockEntity> nearby(Level level, BlockPos from) {
		if (level == null || from == null) {
			return List.of();
		}
		List<DisplayBlockEntity> found = new ArrayList<>();
		int minCx = (from.getX() - RANGE) >> 4;
		int maxCx = (from.getX() + RANGE) >> 4;
		int minCz = (from.getZ() - RANGE) >> 4;
		int maxCz = (from.getZ() + RANGE) >> 4;
		long rangeSqr = (long) RANGE * RANGE;
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				LevelChunk chunk = level.getChunk(cx, cz);
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (blockEntity.getType() != ModBlockEntities.DISPLAY) {
						continue;
					}
					if (!(blockEntity instanceof DisplayBlockEntity display)) {
						continue;
					}
					if (display.getBlockPos().distSqr(from) <= rangeSqr) {
						found.add(display);
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(display -> display.getBlockPos().distSqr(from)));
		return found;
	}
}
