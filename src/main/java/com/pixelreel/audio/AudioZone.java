package com.pixelreel.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Axis-aligned theater room where a display's audio is audible. */
public record AudioZone(BlockPos min, BlockPos max) {
	private static final String KEY_MIN_X = "MinX";
	private static final String KEY_MIN_Y = "MinY";
	private static final String KEY_MIN_Z = "MinZ";
	private static final String KEY_MAX_X = "MaxX";
	private static final String KEY_MAX_Y = "MaxY";
	private static final String KEY_MAX_Z = "MaxZ";

	public AudioZone {
		int minX = Math.min(min.getX(), max.getX());
		int minY = Math.min(min.getY(), max.getY());
		int minZ = Math.min(min.getZ(), max.getZ());
		int maxX = Math.max(min.getX(), max.getX());
		int maxY = Math.max(min.getY(), max.getY());
		int maxZ = Math.max(min.getZ(), max.getZ());
		min = new BlockPos(minX, minY, minZ);
		max = new BlockPos(maxX, maxY, maxZ);
	}

	public static AudioZone of(BlockPos a, BlockPos b) {
		return new AudioZone(a, b);
	}

	public static AudioZone fromCorners(List<? extends BlockPos> corners) {
		if (corners == null || corners.isEmpty()) {
			throw new IllegalArgumentException("corners required");
		}
		BlockPos first = corners.get(0);
		int minX = first.getX();
		int minY = first.getY();
		int minZ = first.getZ();
		int maxX = first.getX();
		int maxY = first.getY();
		int maxZ = first.getZ();
		for (int i = 1; i < corners.size(); i++) {
			BlockPos p = corners.get(i);
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		return of(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
	}

	/** Room box in front of the screen using configurable width/depth/height (quick guess). */
	public static AudioZone fromScreen(
		BlockPos controller,
		Direction facing,
		int controllerColumn,
		int screenWidthBlocks,
		int width,
		int depth,
		int height
	) {
		Direction left = facing.getCounterClockWise();
		int centerOffset = (screenWidthBlocks - 1) / 2 - controllerColumn;
		BlockPos screenMid = controller.relative(left, centerOffset);
		int halfW = Math.max(0, width / 2);
		int otherW = Math.max(0, width - halfW - 1);
		BlockPos cornerA = screenMid.relative(left, halfW);
		BlockPos cornerB = screenMid
			.relative(left.getOpposite(), otherW)
			.relative(facing, Math.max(0, depth - 1))
			.above(Math.max(0, height - 1));
		return of(cornerA, cornerB);
	}

	public boolean contains(Vec3 pos) {
		// Tiny epsilon so standing on the claimed floor/wall blocks still counts as inside.
		final double pad = 0.05;
		return pos.x >= this.min.getX() - pad
			&& pos.x < this.max.getX() + 1.0 + pad
			&& pos.y >= this.min.getY() - pad
			&& pos.y < this.max.getY() + 1.0 + pad
			&& pos.z >= this.min.getZ() - pad
			&& pos.z < this.max.getZ() + 1.0 + pad;
	}

	/** How far inside the box the point is (min distance to a face). 0 if outside. */
	public double depthInside(Vec3 pos) {
		if (!this.contains(pos)) {
			return 0.0;
		}
		double left = pos.x - this.min.getX();
		double right = (this.max.getX() + 1.0) - pos.x;
		double bottom = pos.y - this.min.getY();
		double top = (this.max.getY() + 1.0) - pos.y;
		double near = pos.z - this.min.getZ();
		double far = (this.max.getZ() + 1.0) - pos.z;
		return Math.min(Math.min(left, right), Math.min(Math.min(bottom, top), Math.min(near, far)));
	}

	/** Distance to the closed box surface when outside; 0 when inside. */
	public double distanceOutside(Vec3 pos) {
		if (this.contains(pos)) {
			return 0.0;
		}
		double x0 = this.min.getX();
		double y0 = this.min.getY();
		double z0 = this.min.getZ();
		double x1 = this.max.getX() + 1.0;
		double y1 = this.max.getY() + 1.0;
		double z1 = this.max.getZ() + 1.0;
		double cx = Math.clamp(pos.x, x0, x1);
		double cy = Math.clamp(pos.y, y0, y1);
		double cz = Math.clamp(pos.z, z0, z1);
		double dx = pos.x - cx;
		double dy = pos.y - cy;
		double dz = pos.z - cz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Grow/shrink the box by {@code blocks} on every side (claimed rooms get +1 so screen-row seats stay inside). */
	public AudioZone expanded(int blocks) {
		if (blocks == 0) {
			return this;
		}
		return of(
			this.min.offset(-blocks, -blocks, -blocks),
			this.max.offset(blocks, blocks, blocks)
		);
	}

	/**
	 * 1 inside the zone away from edges; fades to 0 across {@code fadeBlocks} at the boundary.
	 * Outside the zone returns 0.
	 */
	public double edgeGain(Vec3 pos, double fadeBlocks) {
		if (!this.contains(pos)) {
			return 0.0;
		}
		if (fadeBlocks <= 0.0) {
			return 1.0;
		}
		double left = pos.x - this.min.getX();
		double right = (this.max.getX() + 1.0) - pos.x;
		double bottom = pos.y - this.min.getY();
		double top = (this.max.getY() + 1.0) - pos.y;
		double near = pos.z - this.min.getZ();
		double far = (this.max.getZ() + 1.0) - pos.z;
		double dist = Math.min(Math.min(left, right), Math.min(Math.min(bottom, top), Math.min(near, far)));
		if (dist >= fadeBlocks) {
			return 1.0;
		}
		return Math.clamp(dist / fadeBlocks, 0.0, 1.0);
	}

	public void write(CompoundTag tag) {
		tag.putInt(KEY_MIN_X, this.min.getX());
		tag.putInt(KEY_MIN_Y, this.min.getY());
		tag.putInt(KEY_MIN_Z, this.min.getZ());
		tag.putInt(KEY_MAX_X, this.max.getX());
		tag.putInt(KEY_MAX_Y, this.max.getY());
		tag.putInt(KEY_MAX_Z, this.max.getZ());
	}

	public static @Nullable AudioZone read(CompoundTag tag) {
		if (!tag.contains(KEY_MIN_X) || !tag.contains(KEY_MAX_X)) {
			return null;
		}
		return of(
			new BlockPos(tag.getInt(KEY_MIN_X), tag.getInt(KEY_MIN_Y), tag.getInt(KEY_MIN_Z)),
			new BlockPos(tag.getInt(KEY_MAX_X), tag.getInt(KEY_MAX_Y), tag.getInt(KEY_MAX_Z))
		);
	}

	public String describe() {
		return this.min.getX() + "," + this.min.getY() + "," + this.min.getZ()
			+ " → "
			+ this.max.getX() + "," + this.max.getY() + "," + this.max.getZ();
	}

	public int sizeX() {
		return this.max.getX() - this.min.getX() + 1;
	}

	public int sizeY() {
		return this.max.getY() - this.min.getY() + 1;
	}

	public int sizeZ() {
		return this.max.getZ() - this.min.getZ() + 1;
	}

	public String sizeLabel() {
		return this.sizeX() + "×" + this.sizeY() + "×" + this.sizeZ();
	}
}
