package com.pixelreel.client.playback;

import com.pixelreel.PixelReel;
import com.pixelreel.channels.ChannelService;
import com.pixelreel.client.playback.subtitle.SubtitleOverlay;
import com.pixelreel.client.playback.video.ToneMapMode;
import com.pixelreel.client.playback.video.VideoTexture;
import com.pixelreel.client.playback.video.VlcRuntime;
import com.pixelreel.config.ConfigManager;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;

/** this is how media gets handled using VLC becuase vlc is the goat */
public final class ChannelPlayer implements AutoCloseable {
	private static final long RECONNECT_DELAY_MILLIS = 2000L;
	private static final long VOLUME_UPDATE_INTERVAL_MS = 250L;
	private static final int VOLUME_CHANGE_THRESHOLD = 3;
	private static final long SEEK_TOLERANCE_MS = 1500L;
	private static final long END_IGNORE_AFTER_SEEK_MS = 2_500L;
	private static final long END_NEAR_MS = 8_000L;

	private final String url;
	private final boolean onDemand;
	private final long initialStartMs;
	private final VideoTexture videoTexture = new VideoTexture();
	private final SubtitleOverlay subtitleOverlay = new SubtitleOverlay();
	private @Nullable EmbeddedMediaPlayer player;
	private volatile PlaybackStatus status = PlaybackStatus.IDLE;
	private volatile String errorDetail = "";
	private volatile float bufferingProgress;
	private final AtomicBoolean streamFailed = new AtomicBoolean();
	private final AtomicBoolean streamEnded = new AtomicBoolean();
	private final AtomicBoolean firstFrameSeen = new AtomicBoolean();
	private final AtomicBoolean buffering = new AtomicBoolean();
	private int reconnectAttempts;
	private long reconnectAt;
	private boolean closed;
	private long lastVolumeUpdateAt;
	private int lastAppliedVolume = Integer.MIN_VALUE;
	private volatile float desiredGain = 1.0F;
	private final AtomicBoolean volumeDirty = new AtomicBoolean(true);
	private volatile boolean desiredPaused;
	private volatile long desiredSeekMs = -1L;
	private volatile long reportedLengthMs;
	private boolean startSeekApplied;
	private volatile long lastSeekAt;
	private volatile long suppressPlayUntil;
	/** libVLC volume/mute is process-global; per-stream silence uses track disable instead. */
	private int savedAudioTrack = -1;
	private boolean audioOutputDisabled;

	public ChannelPlayer(String url, boolean onDemand, long startPositionMs, ToneMapMode toneMapMode) {
		this.url = ClientMediaProxy.resolve(url);
		this.onDemand = onDemand;
		this.initialStartMs = Math.max(0L, startPositionMs);
		this.videoTexture.setToneMapMode(toneMapMode);
		if (!VlcRuntime.isAvailable()) {
			this.status = PlaybackStatus.UNAVAILABLE;
			this.errorDetail = VlcRuntime.unavailableReason();
		} else {
			this.openStream();
		}
	}

	public String url() {
		return this.url;
	}

	public boolean isOnDemand() {
		return this.onDemand;
	}

	public PlaybackStatus status() {
		return this.status;
	}

	public String errorDetail() {
		return this.errorDetail;
	}

	public float bufferingProgress() {
		return this.bufferingProgress;
	}

	public VideoTexture videoTexture() {
		return this.videoTexture;
	}

	public boolean hasPicture() {
		return this.firstFrameSeen.get() && this.videoTexture.hasFrame();
	}

	public float videoAspectRatio() {
		return this.videoTexture.displayAspect();
	}

	public long mediaTimeMs() {
		EmbeddedMediaPlayer current = this.player;
		if (current == null) {
			return 0L;
		}
		try {
			return Math.max(0L, current.status().time());
		} catch (Throwable t) {
			return 0L;
		}
	}

	public long mediaLengthMs() {
		if (this.reportedLengthMs > 0L) {
			return this.reportedLengthMs;
		}
		EmbeddedMediaPlayer current = this.player;
		if (current == null) {
			return 0L;
		}
		try {
			long length = current.status().length();
			if (length > 0L) {
				this.reportedLengthMs = length;
			}
			return Math.max(0L, length);
		} catch (Throwable t) {
			return 0L;
		}
	}

	public void setDesiredPaused(boolean paused) {
		this.desiredPaused = paused;
	}

	public void requestSeek(long positionMs) {
		this.desiredSeekMs = Math.max(0L, positionMs);
		this.lastSeekAt = System.currentTimeMillis();
	}

	public void setSubtitleUrl(@Nullable String url) {
		this.subtitleOverlay.setSourceUrl(url == null || url.isBlank() ? "" : ClientMediaProxy.resolve(url));
	}

	public boolean consumeEnded() {
		return this.streamEnded.compareAndSet(true, false);
	}

	public void tick() {
		if (this.closed || !VlcRuntime.isAvailable()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (this.streamEnded.get()) {
			if (this.onDemand) {
				this.status = PlaybackStatus.ENDED;
			} else if (this.streamEnded.compareAndSet(true, false)) {
				this.streamFailed.set(true);
			}
		}
		if (!this.onDemand && this.streamFailed.compareAndSet(true, false)) {
			int maxAttempts = ConfigManager.get().streamReconnectAttempts;
			if (this.reconnectAttempts >= maxAttempts) {
				this.status = PlaybackStatus.ERROR;
				if (this.errorDetail.isEmpty()) {
					this.errorDetail = "stream failed";
				}
				PixelReel.LOGGER.warn("Giving up on channel stream ({}) after {} attempt(s)", ChannelService.hostOnly(this.url), maxAttempts);
			} else {
				this.reconnectAttempts++;
				this.reconnectAt = now + RECONNECT_DELAY_MILLIS;
				this.status = PlaybackStatus.CONNECTING;
				PixelReel.LOGGER.info(
					"Channel stream ({}) dropped; reconnect attempt {}/{}", ChannelService.hostOnly(this.url), this.reconnectAttempts, maxAttempts
				);
			}
		} else if (this.reconnectAt != 0L && now >= this.reconnectAt) {
			this.reconnectAt = 0L;
			this.openStream();
		}

		this.applyTransport();
		this.subtitleOverlay.tick(this.mediaTimeMs());

		if (this.firstFrameSeen.get() && (this.status == PlaybackStatus.CONNECTING || this.status == PlaybackStatus.BUFFERING)) {
			if (!this.buffering.get()) {
				this.status = this.desiredPaused ? PlaybackStatus.PLAYING : PlaybackStatus.PLAYING;
				this.reconnectAttempts = 0;
			} else {
				this.status = PlaybackStatus.BUFFERING;
			}
		}
	}

	private void applyTransport() {
		EmbeddedMediaPlayer current = this.player;
		if (current == null) {
			return;
		}
		if (this.status == PlaybackStatus.ENDED) {
			this.stopPlayback(current);
			return;
		}
		try {
			if (this.onDemand && !this.startSeekApplied && this.firstFrameSeen.get() && this.initialStartMs > 0L) {
				current.controls().setTime(this.initialStartMs);
				this.startSeekApplied = true;
				this.desiredSeekMs = -1L;
				this.lastSeekAt = System.currentTimeMillis();
			}
			long seek = this.desiredSeekMs;
			if (seek >= 0L) {
				long actual = current.status().time();
				if (Math.abs(actual - seek) > SEEK_TOLERANCE_MS) {
					current.controls().setTime(seek);
					this.lastSeekAt = System.currentTimeMillis();
				}
				this.desiredSeekMs = -1L;
			}
			boolean playing = current.status().isPlaying();
			if (this.desiredPaused && playing) {
				current.controls().pause();
			} else if (!this.desiredPaused && !playing && this.status != PlaybackStatus.ERROR) {
				if (System.currentTimeMillis() < this.suppressPlayUntil) {
					return;
				}
				current.controls().play();
			}
		} catch (Throwable t) {
			PixelReel.LOGGER.debug("Could not apply transport for {}: {}", ChannelService.hostOnly(this.url), t.toString());
		}
	}

	private void stopPlayback(@Nullable EmbeddedMediaPlayer current) {
		if (current == null) {
			return;
		}
		try {
			if (current.status().isPlaying()) {
				current.controls().stop();
			}
		} catch (Throwable t) {
			PixelReel.LOGGER.debug("Could not stop ended stream {}: {}", ChannelService.hostOnly(this.url), t.toString());
		}
	}

	/**
	 * True when VLC really reached the end of a title. Opening, seeking, and dead transcode URLs all fire
	 * `finished` too, and those must not restart the stream or tell the server the movie is over.
	 */
	boolean looksGenuinelyEnded() {
		if (!this.onDemand || this.status != PlaybackStatus.ENDED) {
			return false;
		}
		if (!this.firstFrameSeen.get()) {
			return false;
		}
		long length = this.mediaLengthMs();
		long time = this.mediaTimeMs();
		if (length > END_NEAR_MS) {
			return time + END_NEAR_MS >= length;
		}
		return time >= 5_000L;
	}

	private void onNativeFinished() {
		this.errorDetail = "stream ended";
		if (!this.onDemand) {
			this.streamEnded.set(true);
			return;
		}
		if (!this.firstFrameSeen.get() || System.currentTimeMillis() - this.lastSeekAt < END_IGNORE_AFTER_SEEK_MS) {
			this.suppressPlayUntil = System.currentTimeMillis() + END_IGNORE_AFTER_SEEK_MS;
			PixelReel.LOGGER.debug("Ignoring premature VLC finished for {}", ChannelService.hostOnly(this.url));
			return;
		}
		long length = this.mediaLengthMs();
		long time = this.mediaTimeMs();
		if (length > END_NEAR_MS && time + END_NEAR_MS < length) {
			this.suppressPlayUntil = System.currentTimeMillis() + END_IGNORE_AFTER_SEEK_MS;
			PixelReel.LOGGER.debug(
				"Ignoring VLC finished for {} at {}ms / {}ms",
				ChannelService.hostOnly(this.url),
				time,
				length
			);
			return;
		}
		if (length <= 0L && time < 5_000L) {
			this.suppressPlayUntil = System.currentTimeMillis() + END_IGNORE_AFTER_SEEK_MS;
			PixelReel.LOGGER.debug("Ignoring VLC finished for {} with no duration", ChannelService.hostOnly(this.url));
			return;
		}
		this.streamEnded.set(true);
		this.status = PlaybackStatus.ENDED;
	}

	public void retry() {
		this.reconnectAttempts = 0;
		this.reconnectAt = 0L;
		this.streamEnded.set(false);
		this.openStream();
	}

	public void tickAudio(float linearGain) {
		this.tickAudio(linearGain, false);
	}

	/**
	 * @param keepTrackWarm when muted, leave the audio track selected and hold volume at 0 so
	 *                      entering a zone can unmute instantly (libVLC track re-select is slow).
	 */
	public void tickAudio(float linearGain, boolean keepTrackWarm) {
		this.desiredGain = Math.clamp(linearGain, 0.0F, 1.0F);
		EmbeddedMediaPlayer current = this.player;
		if (current == null || this.closed) {
			return;
		}

		long now = System.currentTimeMillis();
		boolean dirty = this.volumeDirty.getAndSet(false);
		boolean wantAudio = this.desiredGain > 0.001F;
		if (!wantAudio) {
			if (keepTrackWarm) {
				if (!dirty && !this.audioOutputDisabled && this.lastAppliedVolume == 0
					&& now - this.lastVolumeUpdateAt < VOLUME_UPDATE_INTERVAL_MS) {
					return;
				}
				this.lastVolumeUpdateAt = now;
				this.lastAppliedVolume = 0;
				try {
					this.enableAudioOutput(current);
					current.audio().setVolume(0);
				} catch (Throwable t) {
					PixelReel.LOGGER.debug("Could not warm-mute VLC for {}: {}", ChannelService.hostOnly(this.url), t.toString());
				}
				return;
			}
			if (!dirty && this.audioOutputDisabled && now - this.lastVolumeUpdateAt < VOLUME_UPDATE_INTERVAL_MS) {
				return;
			}
			this.lastVolumeUpdateAt = now;
			this.lastAppliedVolume = 0;
			this.disableAudioOutput(current);
			return;
		}

		float master = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER);
		float records = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.RECORDS);
		int volume = (int)Math.round(Math.clamp(this.desiredGain * master * records, 0.0F, 2.0F) * 100.0);
		boolean wasDisabled = this.audioOutputDisabled;
		if (!dirty
			&& !wasDisabled
			&& this.lastAppliedVolume != Integer.MIN_VALUE
			&& Math.abs(volume - this.lastAppliedVolume) < VOLUME_CHANGE_THRESHOLD
			&& now - this.lastVolumeUpdateAt < VOLUME_UPDATE_INTERVAL_MS) {
			return;
		}

		this.lastVolumeUpdateAt = now;
		this.lastAppliedVolume = volume;
		try {
			this.enableAudioOutput(current);
			// Never setMute — mute is process-global in libVLC and would silence every stream.
			current.audio().setVolume(volume);
			if (wasDisabled) {
				// Track just came back; push volume once more next tick in case VLC ignored the first set.
				this.volumeDirty.set(true);
			}
		} catch (Throwable t) {
			PixelReel.LOGGER.debug("Could not set VLC volume for {}: {}", ChannelService.hostOnly(this.url), t.toString());
		}
	}

	private void disableAudioOutput(EmbeddedMediaPlayer current) {
		if (this.audioOutputDisabled) {
			return;
		}
		try {
			int track = current.audio().track();
			if (track >= 0) {
				this.savedAudioTrack = track;
			}
			current.audio().setTrack(-1);
			this.audioOutputDisabled = true;
		} catch (Throwable t) {
			PixelReel.LOGGER.debug("Could not disable audio track for {}: {}", ChannelService.hostOnly(this.url), t.toString());
		}
	}

	private void enableAudioOutput(EmbeddedMediaPlayer current) {
		if (!this.audioOutputDisabled) {
			return;
		}
		try {
			int restore = this.savedAudioTrack >= 0 ? this.savedAudioTrack : 0;
			current.audio().setTrack(restore);
			this.audioOutputDisabled = false;
		} catch (Throwable t) {
			PixelReel.LOGGER.debug("Could not enable audio track for {}: {}", ChannelService.hostOnly(this.url), t.toString());
		}
	}

	public boolean uploadFrame() {
		return !this.closed && this.videoTexture.uploadIfDirty();
	}

	private void openStream() {
		EmbeddedMediaPlayer current = this.ensurePlayer();
		if (current == null) {
			return;
		}
		this.firstFrameSeen.set(false);
		this.streamFailed.set(false);
		this.streamEnded.set(false);
		this.errorDetail = "";
		this.status = PlaybackStatus.CONNECTING;
		this.lastAppliedVolume = Integer.MIN_VALUE;
		this.volumeDirty.set(true);
		this.audioOutputDisabled = false;
		this.savedAudioTrack = -1;
		this.startSeekApplied = this.initialStartMs <= 0L;
		this.lastSeekAt = System.currentTimeMillis();
		try {
			int caching = Math.clamp(ConfigManager.get().streamCachingMillis, 0, 10000);
			boolean started = current.media().play(this.url, this.mediaOptions(caching));
			if (!started) {
				this.status = PlaybackStatus.ERROR;
				this.errorDetail = "could not open stream";
				PixelReel.LOGGER.warn("libVLC refused the channel stream ({})", ChannelService.hostOnly(this.url));
			}
		} catch (Exception e) {
			this.status = PlaybackStatus.ERROR;
			this.errorDetail = String.valueOf(e.getMessage());
			PixelReel.LOGGER.error("Failed to start channel playback ({})", ChannelService.hostOnly(this.url), e);
		}
	}

	private String[] mediaOptions(int caching) {
		if (this.onDemand) {
			// Do not http-reconnect VOD: when a title ends, VLC would reopen the same URL from the start
			// and loop it for that one client. Live channels still reconnect after a drop.
			return new String[]{
				":network-caching=" + caching,
				":file-caching=" + caching,
				":no-video-title-show",
				":input-repeat=0"
			};
		}
		return new String[]{
			":network-caching=" + caching,
			":live-caching=" + caching,
			":file-caching=" + caching,
			":http-reconnect"
		};
	}

	private @Nullable EmbeddedMediaPlayer ensurePlayer() {
		if (this.player != null) {
			return this.player;
		}
		MediaPlayerFactory factory = VlcRuntime.factory();
		if (factory == null) {
			return null;
		}
		try {
			EmbeddedMediaPlayer created = factory.mediaPlayers().newEmbeddedMediaPlayer();
			created.videoSurface().set(factory.videoSurfaces().newVideoSurface(new FrameFormatCallback(), new FrameRenderCallback(), true));
			created.events().addMediaPlayerEventListener(new StreamEventListener());
			this.player = created;
			return created;
		} catch (Exception e) {
			this.status = PlaybackStatus.ERROR;
			this.errorDetail = "could not create media player";
			PixelReel.LOGGER.error("Failed to create a media player for {}", ChannelService.hostOnly(this.url), e);
			return null;
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.status = PlaybackStatus.IDLE;
		EmbeddedMediaPlayer current = this.player;
		this.player = null;
		if (current != null) {
			// Do not mute/setVolume(0) here — libVLC audio output is often process-global, so
			// muting one player can silence every other active stream from the same factory.
			try {
				current.controls().stop();
			} catch (Throwable ignored) {
			}
			Thread.ofVirtual().name("pixelreel-release").start(() -> {
				try {
					current.release();
				} catch (Throwable t) {
					PixelReel.LOGGER.warn("Error releasing the media player for {}", ChannelService.hostOnly(this.url), t);
				}
			});
		}
		this.subtitleOverlay.clear();
		this.videoTexture.close();
	}

	/** Force the next tickAudio call to push volume to libVLC again. */
	public void markVolumeDirty() {
		this.volumeDirty.set(true);
		this.lastAppliedVolume = Integer.MIN_VALUE;
	}

	private void onVideoFrame(ByteBuffer buffer, int width, int height, float displayAspect) {
		this.videoTexture.submitFrame(buffer, width, height, displayAspect, this.subtitleOverlay.activeText());
		this.firstFrameSeen.set(true);
	}

	private final class FrameFormatCallback implements BufferFormatCallback {
		@Override
		public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
			int cap = Math.min(1280, ConfigManager.get().maximumVideoResolution);
			int width = sourceWidth;
			int height = sourceHeight;
			if (width <= 0 || height <= 0) {
				width = 640;
				height = 360;
			} else {
				int longest = Math.max(width, height);
				if (longest > cap) {
					float scale = (float)cap / longest;
					width = Math.max(2, Math.round(width * scale));
					height = Math.max(2, Math.round(height * scale));
				}
			}
			width &= ~1;
			height &= ~1;
			return new BufferFormat(VideoTexture.CHROMA, width, height, new int[]{width * 4}, new int[]{height});
		}

		@Override
		public void newFormatSize(int bufferWidth, int bufferHeight, int displayWidth, int displayHeight) {
		}

		@Override
		public void allocatedBuffers(ByteBuffer[] buffers) {
		}
	}

	private final class FrameRenderCallback implements RenderCallback {
		@Override
		public void lock(MediaPlayer mediaPlayer) {
		}

		@Override
		public void display(MediaPlayer mediaPlayer, ByteBuffer[] buffers, BufferFormat format, int displayWidth, int displayHeight) {
			if (buffers.length == 0) {
				return;
			}
			int bufferWidth = format.getWidth();
			int bufferHeight = format.getHeight();
			float aspect;
			if (displayWidth > 0 && displayHeight > 0) {
				aspect = (float)displayWidth / displayHeight;
			} else if (bufferHeight > 0) {
				aspect = (float)bufferWidth / bufferHeight;
			} else {
				aspect = 0.0F;
			}
			ChannelPlayer.this.onVideoFrame(buffers[0], bufferWidth, bufferHeight, aspect);
		}

		@Override
		public void unlock(MediaPlayer mediaPlayer) {
		}
	}

	private final class StreamEventListener extends MediaPlayerEventAdapter {
		@Override
		public void buffering(MediaPlayer mediaPlayer, float newCache) {
			ChannelPlayer.this.bufferingProgress = newCache;
			ChannelPlayer.this.buffering.set(newCache < 100.0F);
			ChannelPlayer.this.volumeDirty.set(true);
		}

	@Override
		public void playing(MediaPlayer mediaPlayer) {
			ChannelPlayer.this.volumeDirty.set(true);
			// New media may re-select an audio track; re-apply silence if this room is muted.
			if (ChannelPlayer.this.desiredGain <= 0.001F && ChannelPlayer.this.player != null) {
				ChannelPlayer.this.audioOutputDisabled = false;
				ChannelPlayer.this.disableAudioOutput(ChannelPlayer.this.player);
			}
		}

		@Override
		public void error(MediaPlayer mediaPlayer) {
			ChannelPlayer.this.errorDetail = ChannelPlayer.this.onDemand ? "stream failed to start" : "stream error";
			if (ChannelPlayer.this.onDemand) {
				ChannelPlayer.this.status = PlaybackStatus.ERROR;
			} else {
				ChannelPlayer.this.streamFailed.set(true);
			}
		}

		@Override
		public void finished(MediaPlayer mediaPlayer) {
			ChannelPlayer.this.onNativeFinished();
		}

		@Override
		public void lengthChanged(MediaPlayer mediaPlayer, long newLength) {
			if (newLength > 0L) {
				ChannelPlayer.this.reportedLengthMs = newLength;
			}
		}
	}
}
