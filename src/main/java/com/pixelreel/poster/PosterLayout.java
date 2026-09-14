package com.pixelreel.poster;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Grid and geometry of a hung poster. The anchor block is the bottom-left corner as seen by somebody standing in
 * front of the poster, columns run toward that viewer's right and rows run upwards.
 */
public final class PosterLayout {
	/** Distance from the wall, measured in the north-facing frame where the wall sits at z = 16. */
	public static final float THICKNESS_PX = 1.5F;
	public static final float ART_DEPTH_PX = 16.0F - THICKNESS_PX;
	public static final float FRAME_DEPTH_PX = ART_DEPTH_PX + 0.5F;
	public static final float FRAME_BORDER_PX = 1.0F;

	private static final VoxelShape SHAPE_NORTH = Block.box(0.0, 0.0, 16.0 - THICKNESS_PX, 16.0, 16.0, 16.0);
	private static final VoxelShape SHAPE_SOUTH = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, THICKNESS_PX);
	private static final VoxelShape SHAPE_WEST = Block.box(16.0 - THICKNESS_PX, 0.0, 0.0, 16.0, 16.0, 16.0);
	private static final VoxelShape SHAPE_EAST = Block.box(0.0, 0.0, 0.0, THICKNESS_PX, 16.0, 16.0);

	private PosterLayout() {
	}

	/** Bottom-left origin plus how many blocks the hanging actually occupies. */
	public record Placement(BlockPos origin, int width, int height) {
		public float widthPx() {
			return this.width * 16.0F;
		}

		public float heightPx() {
			return this.height * 16.0F;
		}
	}

	/** Direction the columns run in: the right hand of somebody looking at the poster. */
	public static Direction columnAxis(Direction facing) {
		return facing.getCounterClockWise();
	}

	public static BlockPos cellPos(BlockPos anchor, Direction facing, int column, int row) {
		return anchor.relative(columnAxis(facing), column).above(row);
	}

	public static BlockPos anchorPos(BlockPos cell, Direction facing, int column, int row) {
		return cell.relative(columnAxis(facing).getOpposite(), column).below(row);
	}

	public static VoxelShape shape(Direction facing) {
		return switch (facing) {
			case SOUTH -> SHAPE_SOUTH;
			case WEST -> SHAPE_WEST;
			case EAST -> SHAPE_EAST;
			default -> SHAPE_NORTH;
		};
	}

	/** True when the block behind this cell can carry wall art. */
	public static boolean hasBacking(BlockGetter level, BlockPos cell, Direction facing) {
		BlockPos behind = cell.relative(facing.getOpposite());
		BlockState wall = level.getBlockState(behind);
		return wall.isFaceSturdy(level, behind, facing);
	}

	private static boolean cellFree(LevelReader level, BlockPos cell) {
		if (level.isOutsideBuildHeight(cell)) {
			return false;
		}
		return level.getBlockState(cell).canBeReplaced();
	}

	private static boolean cellOk(LevelReader level, BlockPos cell, Direction facing) {
		return cellFree(level, cell) && hasBacking(level, cell, facing);
	}

	public static boolean canHang(
		LevelReader level,
		BlockPos anchor,
		Direction facing,
		PosterSize size,
		@Nullable BlockPlaceContext context
	) {
		return canHang(level, anchor, facing, size.widthBlocks(), size.heightBlocks());
	}

	/** True when every cell is free and backed by a wall, so the poster hangs flush and never floats. */
	public static boolean canHang(LevelReader level, BlockPos anchor, Direction facing, int width, int height) {
		if (!facing.getAxis().isHorizontal() || width < 1 || height < 1) {
			return false;
		}
		for (int column = 0; column < width; column++) {
			for (int row = 0; row < height; row++) {
				if (!cellOk(level, cellPos(anchor, facing, column, row), facing)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Where and how large this poster should hang. The click can be any cell; huge posters pick the largest
	 * 2:3 size that still fits the wall.
	 */
	public static @Nullable Placement findPlacement(
		LevelReader level,
		BlockPos seed,
		Direction facing,
		PosterSize size,
		@Nullable BlockPlaceContext context
	) {
		BlockPos hangSeed = resolveSeed(level, seed, facing);
		if (hangSeed == null) {
			return null;
		}
		for (int[] dim : size.hangSizes()) {
			BlockPos origin = findFixedAnchor(level, hangSeed, facing, dim[0], dim[1]);
			if (origin != null) {
				return new Placement(origin, dim[0], dim[1]);
			}
		}
		return null;
	}

	/**
	 * Reconstruct the hanging once the item has already chosen the bottom-left cell. Tries the same sizes
	 * largest-first so a huge poster cannot collapse to 2x3.
	 */
	public static @Nullable Placement fitFromOrigin(
		LevelReader level,
		BlockPos origin,
		Direction facing,
		PosterSize size
	) {
		if (!facing.getAxis().isHorizontal() || !cellOk(level, origin, facing)) {
			return null;
		}
		for (int[] dim : size.hangSizes()) {
			if (canHang(level, origin, facing, dim[0], dim[1])) {
				return new Placement(origin, dim[0], dim[1]);
			}
		}
		return null;
	}

	/** Bottom-left origin for a fixed-size poster. */
	public static @Nullable BlockPos findAnchor(
		LevelReader level,
		BlockPos seed,
		Direction facing,
		PosterSize size,
		@Nullable BlockPlaceContext context
	) {
		Placement placement = findPlacement(level, seed, facing, size, context);
		return placement == null ? null : placement.origin();
	}

	private static @Nullable BlockPos resolveSeed(LevelReader level, BlockPos seed, Direction facing) {
		if (!facing.getAxis().isHorizontal()) {
			return null;
		}
		if (cellOk(level, seed, facing)) {
			return seed;
		}
		BlockPos inFront = seed.relative(facing);
		if (cellOk(level, inFront, facing)) {
			return inFront;
		}
		return null;
	}

	private static @Nullable BlockPos findFixedAnchor(
		LevelReader level,
		BlockPos hangSeed,
		Direction facing,
		int width,
		int height
	) {
		int preferColumn = width / 2;
		int preferRow = height / 2;
		BlockPos best = null;
		int bestScore = Integer.MAX_VALUE;
		for (int column = 0; column < width; column++) {
			for (int row = 0; row < height; row++) {
				BlockPos origin = anchorPos(hangSeed, facing, column, row);
				if (!canHang(level, origin, facing, width, height)) {
					continue;
				}
				int score = Math.abs(column - preferColumn) + Math.abs(row - preferRow);
				if (score < bestScore) {
					bestScore = score;
					best = origin;
				}
			}
		}
		return best;
	}
}
