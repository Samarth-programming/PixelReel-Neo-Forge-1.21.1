package com.pixelreel.client.playback;

import com.pixelreel.PixelReel;
import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.client.ClientNetworking;
import com.pixelreel.client.playback.video.ToneMapMode;
import com.pixelreel.client.playback.video.VideoTexture;
import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import com.pixelreel.items.PixelGlassesItem;
import com.pixelreel.networking.ScreenAction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** the channels are managed and played */
public final class PlaybackManager {
	public static final PlaybackManager INSTANCE = new PlaybackManager();
	private static final int RESCAN_INTERVAL_TICKS = 20;
	private static final double KEEP_ALIVE_MULTIPLIER = 1.75;

	private final Map<String, ChannelPlayer> players = new HashMap<>();
	private final Map<String, Integer> playerEpochs = new HashMap<>();
	private final Set<BlockPos> nearbyControllers = new HashSet<>();
	private final Set<String> endedReports = new HashSet<>();
	private final Set<String> durationReports = new HashSet<>();
	private int tickCounter;
	private long audioRefreshUntilMs;

	private PlaybackManager() {
	}

	public @Nullable ChannelPlayer player(String url) {
		return url == null || url.isEmpty() ? null : this.players.get(url);
	}

	public int activePlayerCount() {
		return this.players.size();
	}

	public void prewarm(List<String> urls) {
	}

	public void retry(BlockPos controllerPos) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null && minecraft.level.getBlockEntity(controllerPos) instanceof DisplayBlockEntity display) {
			ChannelPlayer player = this.players.get(ClientPlaybackSecrets.knownStreamUrl(display));
			if (player != null) {
				player.retry();
			}
		}
	}

	public void clientTick() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		Player player = minecraft.player;
		if (level == null || player == null) {
			this.releaseAll();
			return;
		}

		if (this.tickCounter++ % RESCAN_INTERVAL_TICKS == 0) {
			this.rescan(level, player);
		}

		this.driveSessions(level, player);
	}

	private void rescan(ClientLevel level, Player player) {
		this.nearbyControllers.clear();
		double keepAlive = ConfigManager.get().maximumPlaybackDistance * KEEP_ALIVE_MULTIPLIER;
		int chunkRadius = Math.max(1, SectionPos.blockToSectionCoord((int)Math.ceil(keepAlive)) + 1);
		int centreX = SectionPos.blockToSectionCoord(player.getBlockX());
		int centreZ = SectionPos.blockToSectionCoord(player.getBlockZ());
		double keepAliveSqr = keepAlive * keepAlive;

		for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(centreX + dx, centreZ + dz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
					if (entry.getValue() instanceof DisplayBlockEntity display
						&& player.distanceToSqr(display.screenCentre()) <= keepAliveSqr) {
						this.nearbyControllers.add(entry.getKey().immutable());
					}
				}
			}
		}
	}

	private void driveSessions(ClientLevel level, Player player) {
		PixelReelConfig config = ConfigManager.get();
		double startDistance = config.maximumPlaybackDistance;
		double startDistanceSqr = startDistance * startDistance;
		double keepAliveDistance = startDistance * KEEP_ALIVE_MULTIPLIER;
		double keepAliveDistanceSqr = keepAliveDistance * keepAliveDistance;

		record Binding(DisplayBlockEntity display, Vec3 centre, double distanceSqr) {
		}
		Map<String, List<Binding>> desired = new HashMap<>();

		for (BlockPos pos : this.nearbyControllers) {
			if (!(level.getBlockEntity(pos) instanceof DisplayBlockEntity display)) {
				continue;
			}
			if (!display.shouldPlay()) {
				continue;
			}
			Vec3 centre = display.screenCentre();
			double distanceSqr = player.distanceToSqr(centre);
			if (distanceSqr > keepAliveDistanceSqr) {
				continue;
			}
			// Asking costs a packet, so only do it once the screen is close enough to actually start.
			String url = ClientPlaybackSecrets.resolveForPlayback(display);
			if (url.isEmpty()) {
				continue;
			}
			boolean alreadyPlaying = this.players.containsKey(url);
			double limitSqr = alreadyPlaying ? keepAliveDistanceSqr : startDistanceSqr;
			if (distanceSqr > limitSqr) {
				continue;
			}
			desired.computeIfAbsent(url, key -> new ArrayList<>()).add(new Binding(display, centre, distanceSqr));
		}

		LinkedHashSet<String> ranked = desired.entrySet().stream()
			.sorted(Comparator.comparingDouble(entry -> entry.getValue().stream().mapToDouble(Binding::distanceSqr).min().orElse(Double.MAX_VALUE)))
			.map(Map.Entry::getKey)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

		int budget = config.maxSimultaneousChannels;
		Set<String> allowed = new HashSet<>();
		for (String url : ranked) {
			if (allowed.size() >= budget) {
				break;
			}
			allowed.add(url);
		}

		for (String url : allowed) {
			List<Binding> bindings = desired.get(url);
			Binding nearest = bindings == null || bindings.isEmpty()
				? null
				: bindings.stream().min(Comparator.comparingDouble(Binding::distanceSqr)).orElse(null);
			if (nearest == null) {
				continue;
			}
			DisplayBlockEntity display = nearest.display();
			boolean onDemand = display.isOnDemand();
			int epoch = display.getChannelEpoch();
			ChannelPlayer channelPlayer = this.players.get(url);
			Integer previousEpoch = this.playerEpochs.get(url);
			if (channelPlayer == null) {
				channelPlayer = new ChannelPlayer(
					url,
					onDemand,
					onDemand ? display.currentPlaybackPositionMs() : 0L,
					toneMapMode(display, onDemand)
				);
				this.players.put(url, channelPlayer);
				this.playerEpochs.put(url, epoch);
				this.endedReports.remove(url);
				this.durationReports.remove(url);
				PixelReel.LOGGER.info("Playing {} stream ({}) — {} active", onDemand ? "on-demand" : "channel", host(url), this.players.size());
			} else if (previousEpoch == null || previousEpoch != epoch) {
				this.playerEpochs.put(url, epoch);
				this.endedReports.remove(url);
				this.durationReports.remove(url);
				if (onDemand) {
					channelPlayer.requestSeek(display.currentPlaybackPositionMs());
				}
			}
		}

		float globalVolume = (float)config.globalTvVolume;
		boolean closedAny = false;
		Iterator<Map.Entry<String, ChannelPlayer>> closer = this.players.entrySet().iterator();
		while (closer.hasNext()) {
			Map.Entry<String, ChannelPlayer> entry = closer.next();
			List<Binding> bindings = desired.get(entry.getKey());
			boolean active = allowed.contains(entry.getKey()) && bindings != null && !bindings.isEmpty();
			if (active) {
				continue;
			}
			PixelReel.LOGGER.info("Stopping channel stream ({}) — out of range or switched away", host(entry.getKey()));
			closer.remove();
			this.playerEpochs.remove(entry.getKey());
			this.endedReports.remove(entry.getKey());
			this.durationReports.remove(entry.getKey());
			entry.getValue().close();
			closedAny = true;
		}
		if (closedAny) {
			this.audioRefreshUntilMs = System.currentTimeMillis() + 750L;
		}
		boolean refreshAudio = closedAny || System.currentTimeMillis() < this.audioRefreshUntilMs;
		Vec3 listener = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
		double edgeFade = config.audioZoneEdgeFadeBlocks;
		boolean glasses = PixelGlassesItem.isWearing(player);

		record Audible(String url, ChannelPlayer channelPlayer, DisplayBlockEntity display, double distanceSqr, float gain, boolean zoned, double zoneDepth, double zoneOutside) {
		}
		List<Audible> audibles = new ArrayList<>();

		for (Map.Entry<String, ChannelPlayer> entry : this.players.entrySet()) {
			ChannelPlayer channelPlayer = entry.getValue();
			List<Binding> bindings = desired.get(entry.getKey());
			if (bindings == null || bindings.isEmpty()) {
				continue;
			}

			Binding nearest = bindings.stream().min(Comparator.comparingDouble(Binding::distanceSqr)).orElseThrow();
			DisplayBlockEntity display = nearest.display();
			if (display.isOnDemand()) {
				channelPlayer.setSubtitleUrl(ClientPlaybackSecrets.subtitleUrl(display));
				boolean paused = display.isPlaybackPaused() || display.isSuspended();
				channelPlayer.setDesiredPaused(paused);
				long target = display.currentPlaybackPositionMs();
				long actual = channelPlayer.mediaTimeMs();
				if (!paused && Math.abs(actual - target) > 2500L) {
					channelPlayer.requestSeek(target);
				}
				long length = channelPlayer.mediaLengthMs();
				if (length > 0L && display.getPlaybackDurationMs() <= 0L && this.durationReports.add(entry.getKey())) {
					ClientNetworking.sendControl(display.getBlockPos(), ScreenAction.REPORT_DURATION, (float)length);
				}
			} else {
				channelPlayer.setDesiredPaused(false);
			}

			channelPlayer.tick();
			if (display.isOnDemand()
				&& !display.isPlaybackPaused()
				&& display.isNearPlaybackEnd()
				&& channelPlayer.looksGenuinelyEnded()
				&& this.endedReports.add(entry.getKey())) {
				ClientNetworking.reportMediaEnded(display.getBlockPos(), display.getChannelEpoch());
			}
			float volume = 0.0F;
			float audioRange = 8.0F;
			for (Binding binding : bindings) {
				volume = Math.max(volume, binding.display().getVolume());
				audioRange = Math.max(audioRange, binding.display().type().audioRange());
			}
			boolean zoned = display.hasAudioZone();
			double zoneGain = display.audioZoneGain(listener, edgeFade);
			double normalized;
			if (zoned) {
				// Glasses only boost inside the room; outside stays silent.
				normalized = zoneGain;
			} else {
				double distance = Math.sqrt(nearest.distanceSqr());
				normalized = glasses ? 1.0 : softDistanceGain(distance, audioRange);
			}
			float gain = (float)(volume * globalVolume * normalized);
			if (display.isOnDemand() && display.isPlaybackPaused()) {
				gain = 0.0F;
			}
			audibles.add(new Audible(
				entry.getKey(),
				channelPlayer,
				display,
				nearest.distanceSqr(),
				gain,
				zoned,
				zoned ? display.audioZoneDepth(listener) : 0.0,
				zoned ? display.audioZoneOutsideDistance(listener) : Double.POSITIVE_INFINITY
			));
		}

		// Inside claimed theaters: pick the room you're deepest into (not merely nearest screen).
		String preferZonedUrl = null;
		double preferDepth = -1.0;
		double preferDistance = Double.MAX_VALUE;
		for (Audible audible : audibles) {
			if (!audible.zoned() || audible.gain() <= 0.0F) {
				continue;
			}
			if (audible.zoneDepth() > preferDepth
				|| (audible.zoneDepth() == preferDepth && audible.distanceSqr() < preferDistance)) {
				preferDepth = audible.zoneDepth();
				preferDistance = audible.distanceSqr();
				preferZonedUrl = audible.url();
			}
		}
		final String exclusiveUrl = preferZonedUrl;
		boolean insideClaimedTheater = exclusiveUrl != null;

		// In a hallway: pre-warm the nearest zone's audio track at volume 0 so entry unmutes instantly.
		final double warmOutsideBlocks = 3.0;
		String warmUrl = null;
		double warmOutside = Double.MAX_VALUE;
		if (!insideClaimedTheater) {
			for (Audible audible : audibles) {
				if (!audible.zoned()) {
					continue;
				}
				double outside = audible.zoneOutside();
				if (outside > 0.0 && outside <= warmOutsideBlocks && outside < warmOutside) {
					warmOutside = outside;
					warmUrl = audible.url();
				}
			}
		}
		final String warmTrackUrl = warmUrl;

		record GainPlan(Audible audible, float gain, boolean warm) {
		}
		List<GainPlan> plans = new ArrayList<>(audibles.size());
		for (Audible audible : audibles) {
			float gain = audible.gain();
			if (insideClaimedTheater && !exclusiveUrl.equals(audible.url())) {
				gain = 0.0F;
			}
			boolean warm = gain <= 0.0F && warmTrackUrl != null && warmTrackUrl.equals(audible.url());
			plans.add(new GainPlan(audible, gain, warm));
		}
		// Enable / raise volume first, then mute others — avoids a silent gap while VLC reselects a track.
		plans.sort(Comparator.comparingDouble((GainPlan p) -> p.gain() > 0.0F ? 0.0 : (p.warm() ? 1.0 : 2.0)));
		for (GainPlan plan : plans) {
			if (refreshAudio) {
				plan.audible().channelPlayer().markVolumeDirty();
			}
			plan.audible().channelPlayer().tickAudio(plan.gain(), plan.warm());
		}
	}

	private static double softDistanceGain(double distance, double audioRange) {
		if (distance <= 0.0 || audioRange <= 0.0) {
			return 1.0;
		}
		if (distance >= audioRange) {
			return 0.0;
		}
		double half = audioRange * 0.5;
		if (distance <= half) {
			return 1.0;
		}
		return Math.clamp(1.0 - (distance - half) / (audioRange - half), 0.0, 1.0);
	}

	private static String host(String url) {
		return com.pixelreel.channels.ChannelService.hostOnly(url);
	}

	/**
	 * On-demand items carry a colour range from the library, so trust it either way and never tone map SDR.
	 * Live channels arrive without any metadata, which is the only case left for guessing from the frames.
	 */
	private static ToneMapMode toneMapMode(DisplayBlockEntity display, boolean onDemand) {
		if (!onDemand) {
			return ToneMapMode.AUTO;
		}
		return display.isHdrContent() ? ToneMapMode.HDR : ToneMapMode.SDR;
	}

	public @Nullable PictureHandle pictureFor(DisplayBlockEntity display) {
		ChannelPlayer current = this.players.get(ClientPlaybackSecrets.knownStreamUrl(display));
		if (current != null && current.hasPicture() && current.uploadFrame()) {
			VideoTexture texture = current.videoTexture();
			return new PictureHandle(
				texture.textureId(),
				texture.displayAspect(),
				texture.contentU0(),
				texture.contentV0(),
				texture.contentU1(),
				texture.contentV1()
			);
		}
		return null;
	}

	public @Nullable PlaybackStatus statusFor(DisplayBlockEntity display) {
		ChannelPlayer player = this.players.get(ClientPlaybackSecrets.knownStreamUrl(display));
		return player == null ? null : player.status();
	}

	public @Nullable DisplayBlockEntity nearestPlayingDisplay(Player player, ClientLevel level) {
		DisplayBlockEntity best = null;
		double bestDistanceSqr = Double.MAX_VALUE;
		for (BlockPos pos : this.nearbyControllers) {
			if (!(level.getBlockEntity(pos) instanceof DisplayBlockEntity display) || !display.shouldPlay()) {
				continue;
			}
			double distanceSqr = player.distanceToSqr(display.screenCentre());
			if (distanceSqr < bestDistanceSqr) {
				bestDistanceSqr = distanceSqr;
				best = display;
			}
		}
		return best;
	}

	public void release(BlockPos controllerPos) {
		this.nearbyControllers.remove(controllerPos);
	}

	public void releaseAll() {
		if (!this.players.isEmpty()) {
			PixelReel.LOGGER.debug("Releasing {} channel player(s)", this.players.size());
			this.players.values().forEach(ChannelPlayer::close);
			this.players.clear();
		}
		this.playerEpochs.clear();
		this.endedReports.clear();
		this.durationReports.clear();
		this.nearbyControllers.clear();
	}

	public record PictureHandle(
		net.minecraft.resources.ResourceLocation textureId,
		float aspect,
		float u0,
		float v0,
		float u1,
		float v1
	) {
	}
}
