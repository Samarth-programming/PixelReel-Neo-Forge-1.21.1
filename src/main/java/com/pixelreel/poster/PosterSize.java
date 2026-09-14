package com.pixelreel.poster;

/** How many blocks a hung poster occupies. */
public enum PosterSize {
	STANDARD("wall_poster", 2, 3),
	/** Largest 2:3 movie-poster hang; placement picks the biggest of these that fits the wall. */
	HUGE("huge_wall_poster", 10, 15);

	/** Widest/tallest size any poster uses, so filler-cell blockstate can cover every variant. */
	public static final int MAX_SPAN = 15;

	private final String id;
	private final int widthBlocks;
	private final int heightBlocks;

	PosterSize(String id, int widthBlocks, int heightBlocks) {
		this.id = id;
		this.widthBlocks = widthBlocks;
		this.heightBlocks = heightBlocks;
	}

	public String id() {
		return this.id;
	}

	public int widthBlocks() {
		return this.widthBlocks;
	}

	public int heightBlocks() {
		return this.heightBlocks;
	}

	public float widthPx() {
		return this.widthBlocks * 16.0F;
	}

	public float heightPx() {
		return this.heightBlocks * 16.0F;
	}

	public String tooltipKey() {
		return "item.pixelreel." + this.id + ".tooltip";
	}

	/** True when this item should pick the largest 2:3 hang that fits the wall. */
	public boolean fillsAvailableWall() {
		return this == HUGE;
	}

	/**
	 * Candidate hangs, largest first. Huge posters stay 2:3 so the whole artwork shows, and never shrink to the
	 * small 2x3 size.
	 */
	public int[][] hangSizes() {
		if (this == HUGE) {
			return new int[][]{{10, 15}, {8, 12}, {7, 10}, {6, 9}, {4, 6}};
		}
		return new int[][]{{this.widthBlocks, this.heightBlocks}};
	}
}
