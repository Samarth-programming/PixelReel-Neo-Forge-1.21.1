package com.pixelreel.networking;

import com.pixelreel.PixelReel;
import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.blocks.DisplayBlock;
import com.pixelreel.config.ConfigManager;
import com.pixelreel.permissions.CinemaPermissions;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Hands out the addresses a client needs in order to play a screen.
 *
 * <p>Those addresses are short-lived proxy URLs on this Minecraft process. The real Jellyfin/Emby/Plex
 * URL (and its API key) never leaves the server.
 */
public final class PlaybackSecrets {
	/** Slightly beyond the client's keep-alive radius so walking around a large room does not drop the grant. */
	private static final double RANGE_SLACK = 1.6;
	private static final long MIN_REQUEST_INTERVAL_MS = 500L;

	private static final Map<UUID, Long> lastRequestMillis = new ConcurrentHashMap<>();

	private PlaybackSecrets() {
	}

	public static void handleRequest(ModNetworkPayloads.RequestPlaybackSecrets payload, ServerPlayer player) {
		if (isFlooding(player)) {
			return;
		}
		BlockPos pos = payload.pos();
		DisplayBlockEntity display = resolveInPlaybackRange(player, pos);
		if (display == null) {
			deny(player, pos, payload.channelEpoch());
			return;
		}
		if (!CinemaPermissions.canWatch(player)) {
			PixelReel.LOGGER.debug("Refused stream URL to {}: no watch permission", player.getGameProfile().getName());
			deny(player, pos, display.getChannelEpoch());
			return;
		}
		if (!display.shouldPlay() || display.getStreamUrl().isEmpty()) {
			deny(player, pos, display.getChannelEpoch());
			return;
		}
		PacketDistributor.sendToPlayer(player, secretsPayload(display));
	}

	/** Pushes the new addresses to everyone already watching, so a channel change does not stall for a round trip. */
	public static void broadcastUpdate(DisplayBlockEntity display) {
		if (display.getLevel() == null || display.getLevel().isClientSide()) {
			return;
		}
		var server = display.getLevel().getServer();
		if (server == null) {
			return;
		}
		BlockPos pos = display.getBlockPos();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.level() != display.getLevel() || !isWithinPlaybackRange(player, display)) {
				continue;
			}
			if (!CinemaPermissions.canWatch(player)) {
				deny(player, pos, display.getChannelEpoch());
				continue;
			}
			PacketDistributor.sendToPlayer(player, secretsPayload(display));
		}
	}

	private static ModNetworkPayloads.PlaybackSecretsData secretsPayload(DisplayBlockEntity display) {
		BlockPos pos = display.getBlockPos();
		int epoch = display.getChannelEpoch();
		if (!display.shouldPlay()) {
			return new ModNetworkPayloads.PlaybackSecretsData(pos, epoch, "", "", "", "");
		}
		return new ModNetworkPayloads.PlaybackSecretsData(
			pos,
			epoch,
			com.pixelreel.media.MediaProxy.streamForDisplay(pos, epoch, display.getStreamUrl()),
			com.pixelreel.media.MediaProxy.forClient(com.pixelreel.media.MediaProxy.Kind.SUBTITLE, display.getSubtitleFetchUrl()),
			com.pixelreel.media.MediaProxy.forClient(com.pixelreel.media.MediaProxy.Kind.IMAGE, display.getMediaImageUrl()),
			""
		);
	}

	public static void forget(ServerPlayer player) {
		lastRequestMillis.remove(player.getUUID());
	}

	private static void deny(ServerPlayer player, BlockPos pos, int epoch) {
		PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.PlaybackSecretsData(pos, epoch, "", "", "", ""));
	}

	/**
	 * Deliberately uses the playback radius rather than the shorter control radius: a big cinema screen is
	 * watched from further away than it can be operated from.
	 */
	private static @Nullable DisplayBlockEntity resolveInPlaybackRange(ServerPlayer player, BlockPos pos) {
		if (!(player.level() instanceof ServerLevel level) || !level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
			return null;
		}
		DisplayBlockEntity display = DisplayBlock.displayAt(level, pos);
		return display != null && isWithinPlaybackRange(player, display) ? display : null;
	}

	private static boolean isWithinPlaybackRange(ServerPlayer player, DisplayBlockEntity display) {
		double range = ConfigManager.get().maximumPlaybackDistance * RANGE_SLACK;
		return player.distanceToSqr(display.screenCentre()) <= range * range;
	}

	private static boolean isFlooding(ServerPlayer player) {
		long now = System.currentTimeMillis();
		Long previous = lastRequestMillis.put(player.getUUID(), now);
		return previous != null && now - previous < MIN_REQUEST_INTERVAL_MS;
	}
}
