package com.pixelreel.client.playback.video;

import com.pixelreel.PixelReel;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Lifts un-tone-mapped HDR frames into a viewable range.
 *
 * <p>VLC hands us 8-bit RGB with the PQ/HLG curve still baked in, so HDR sources arrive far too dark and need an
 * exposure boost plus a filmic roll-off. SDR sources must be passed through untouched. Which one a stream is comes
 * from the library metadata; the frame heuristics here only run for live channels, where there is no metadata, and
 * they are deliberately biased towards leaving the picture alone.
 */
final class HdrToneMapper {
	private enum State {
		DETECTING,
		OFF,
		ON
	}

	private static final int SAMPLE_STRIDE = 12;
	/** Frames between samples once a decision is in force, just enough to notice a wrong guess. */
	private static final int RECHECK_FRAMES = 30;

	/** A frame this dark overall, with no real highlights, is what raw PQ looks like. */
	private static final float HDR_MEAN_MAX = 0.13F;
	private static final float HDR_P95_MAX = 0.55F;
	/** Raw PQ essentially never gets this bright, so either of these proves the stream is SDR. */
	private static final float SDR_MEAN_MIN = 0.20F;
	private static final float SDR_P95_MIN = 0.80F;
	/** Fades, letterboxing and black frames say nothing about the transfer curve. */
	private static final float BLACK_MEAN = 0.025F;

	/** Auto-detection has to see this much sustained evidence before it touches anything. */
	private static final int MIN_HDR_SAMPLES = 24;
	private static final long MIN_DETECT_MILLIS = 3_000L;

	private static final float TARGET_MEAN = 0.34F;
	private static final float MAX_EXPOSURE = 12.0F;
	private static final float MIN_EXPOSURE = 2.2F;
	private static final float DEFAULT_EXPOSURE = 5.5F;
	private static final int EXPOSURE_SAMPLES = 12;
	private static final float HABLE_WHITE = 11.2F;

	private ToneMapMode mode = ToneMapMode.AUTO;
	private State state = State.DETECTING;

	private int hdrSamples;
	private long detectStartedAt;

	private float exposureAccum;
	private int exposureSamples;
	private boolean exposureLocked;
	private float exposure = DEFAULT_EXPOSURE;

	private int framesSinceSample;
	private final byte[] lut = new byte[256];
	private float lutExposure = Float.NaN;

	void setMode(ToneMapMode mode) {
		this.mode = mode == null ? ToneMapMode.AUTO : mode;
		switch (this.mode) {
			case HDR -> {
				// Start mapping straight away; the first frames only refine how far to lift it.
				this.state = State.ON;
				this.exposure = DEFAULT_EXPOSURE;
				this.buildLut(this.exposure);
			}
			case SDR -> this.state = State.OFF;
			case AUTO -> this.state = State.DETECTING;
		}
	}

	void processInPlace(ByteBuffer rgba, int width, int height) {
		if (rgba == null || !rgba.isDirect() || width < 8 || height < 8) {
			return;
		}
		if (this.state == State.OFF) {
			return;
		}
		if (rgba.remaining() < width * height * 4) {
			return;
		}

		// Measure the frame as decoded, before any lift, so the numbers describe the source and not our own output.
		if (this.shouldSample()) {
			this.consider(sample(rgba, width, height));
		}

		if (this.state == State.ON) {
			this.applyWithExposure(rgba, width, height, this.exposure);
		}
	}

	private boolean shouldSample() {
		if (this.state == State.DETECTING || !this.exposureLocked) {
			this.framesSinceSample = 0;
			return true;
		}
		// The library already told us what this stream is, so there is nothing left to watch for.
		if (this.mode != ToneMapMode.AUTO) {
			return false;
		}
		// Keep an eye on guessed streams so a wrong call can be undone when a bright scene finally arrives.
		if (++this.framesSinceSample < RECHECK_FRAMES) {
			return false;
		}
		this.framesSinceSample = 0;
		return true;
	}

	private void consider(FrameStats stats) {
		if (stats.mean() <= BLACK_MEAN) {
			return;
		}
		boolean looksSdr = stats.mean() >= SDR_MEAN_MIN || stats.p95() >= SDR_P95_MIN;

		if (this.mode == ToneMapMode.AUTO) {
			if (looksSdr) {
				// One unmistakably bright frame is enough: no raw PQ stream produces one, and a dark SDR scene
				// that briefly fooled us gets corrected here instead of staying blown out for the whole show.
				if (this.state == State.ON) {
					PixelReel.LOGGER.debug("Disabling HDR tone mapping: stream reached SDR brightness");
				}
				this.state = State.OFF;
				return;
			}
			if (this.state == State.DETECTING) {
				this.trackDetection(stats);
			}
		}

		if (this.state == State.ON) {
			this.refineExposure(stats);
		}
	}

	/** Auto mode only latches on after the picture has stayed PQ-dark for a while, not on one dark scene. */
	private void trackDetection(FrameStats stats) {
		if (stats.mean() > HDR_MEAN_MAX || stats.p95() > HDR_P95_MAX) {
			this.hdrSamples = 0;
			this.detectStartedAt = 0L;
			return;
		}
		long now = System.currentTimeMillis();
		if (this.detectStartedAt == 0L) {
			this.detectStartedAt = now;
		}
		this.hdrSamples++;
		if (this.hdrSamples < MIN_HDR_SAMPLES || now - this.detectStartedAt < MIN_DETECT_MILLIS) {
			return;
		}
		this.state = State.ON;
		PixelReel.LOGGER.debug("Enabling HDR tone mapping: stream stayed PQ-dark for {} frames", this.hdrSamples);
	}

	/** Different HDR masters sit at different average levels, so settle the lift on the opening frames. */
	private void refineExposure(FrameStats stats) {
		if (this.exposureLocked) {
			return;
		}
		this.exposureAccum += clamp(TARGET_MEAN / Math.max(0.02F, stats.mean()), MIN_EXPOSURE, MAX_EXPOSURE);
		this.exposureSamples++;
		this.exposure = this.exposureAccum / this.exposureSamples;
		if (this.exposureSamples >= EXPOSURE_SAMPLES) {
			this.exposureLocked = true;
		}
	}

	private void applyWithExposure(ByteBuffer rgba, int width, int height, float useExposure) {
		if (Float.isNaN(this.lutExposure) || Math.abs(this.lutExposure - useExposure) > 0.01F) {
			this.buildLut(useExposure);
		}
		long base = MemoryUtil.memAddress(rgba);
		int pixels = width * height;
		for (int i = 0; i < pixels; i++) {
			long p = base + (long)i * 4L;
			int r = MemoryUtil.memGetByte(p) & 0xFF;
			int g = MemoryUtil.memGetByte(p + 1) & 0xFF;
			int b = MemoryUtil.memGetByte(p + 2) & 0xFF;
			MemoryUtil.memPutByte(p, this.lut[r]);
			MemoryUtil.memPutByte(p + 1, this.lut[g]);
			MemoryUtil.memPutByte(p + 2, this.lut[b]);
		}
	}

	private void buildLut(float useExposure) {
		float white = hable(HABLE_WHITE);
		for (int i = 0; i < 256; i++) {
			float c = i / 255.0F;
			float mapped = hable(c * useExposure) / white;
			mapped = (float)Math.pow(Math.max(0.0F, Math.min(1.0F, mapped)), 0.92);
			this.lut[i] = (byte)Math.round(mapped * 255.0F);
		}
		this.lutExposure = useExposure;
	}

	private static float hable(float x) {
		float a = 0.15F;
		float b = 0.50F;
		float c = 0.10F;
		float d = 0.20F;
		float e = 0.02F;
		float f = 0.30F;
		return ((x * (a * x + c * b) + d * e) / (x * (a * x + b) + d * f)) - e / f;
	}

	private static FrameStats sample(ByteBuffer rgba, int width, int height) {
		long base = MemoryUtil.memAddress(rgba);
		long sum = 0L;
		int count = 0;
		int[] hist = new int[256];
		for (int y = 0; y < height; y += SAMPLE_STRIDE) {
			for (int x = 0; x < width; x += SAMPLE_STRIDE) {
				long p = base + ((long)y * width + x) * 4L;
				int r = MemoryUtil.memGetByte(p) & 0xFF;
				int g = MemoryUtil.memGetByte(p + 1) & 0xFF;
				int b = MemoryUtil.memGetByte(p + 2) & 0xFF;
				int y601 = (r * 77 + g * 150 + b * 29) >> 8;
				sum += y601;
				hist[y601]++;
				count++;
			}
		}
		if (count == 0) {
			return new FrameStats(0.0F, 0.0F);
		}
		float mean = (float)sum / (count * 255.0F);
		int target = Math.max(1, (int)(count * 0.95F));
		int seen = 0;
		int p95 = 0;
		for (int i = 0; i < 256; i++) {
			seen += hist[i];
			if (seen >= target) {
				p95 = i;
				break;
			}
		}
		return new FrameStats(mean, p95 / 255.0F);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private record FrameStats(float mean, float p95) {
	}
}
