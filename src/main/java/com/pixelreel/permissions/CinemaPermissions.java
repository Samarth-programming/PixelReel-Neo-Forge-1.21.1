package com.pixelreel.permissions;

import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import com.pixelreel.networking.ScreenAction;
import com.pixelreel.ondemand.OnDemandProvider;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/** simple permission gates for display actions */
public final class CinemaPermissions {
	public static final String EVERYONE = "everyone";
	public static final String OP = "op";

	private CinemaPermissions() {
	}

	public static boolean canBrowse(ServerPlayer player) {
		return allows(player, PermissionNodes.BROWSE, ConfigManager.get().permissionBrowse);
	}

	public static boolean canPlayTunarr(ServerPlayer player) {
		return allows(player, PermissionNodes.PLAY_TUNARR, ConfigManager.get().permissionPlayTunarr);
	}

	public static boolean canPlayJellyfinMovies(ServerPlayer player) {
		return allows(player, PermissionNodes.PLAY_MOVIES, ConfigManager.get().permissionPlayJellyfinMovies);
	}

	public static boolean canPlayJellyfinShows(ServerPlayer player) {
		return allows(player, PermissionNodes.PLAY_SHOWS, ConfigManager.get().permissionPlayJellyfinShows);
	}

	public static boolean canPlayOnDemandMovies(ServerPlayer player) {
		return canPlayJellyfinMovies(player);
	}

	public static boolean canPlayOnDemandShows(ServerPlayer player) {
		return canPlayJellyfinShows(player);
	}

	public static boolean canControlPlayback(ServerPlayer player) {
		return allows(player, PermissionNodes.CONTROL, ConfigManager.get().permissionControlPlayback);
	}

	/**
	 * Whether this player may be handed the raw stream URL. VLC talks to the media server directly, so
	 * granting this necessarily reveals that server's address to the player's client.
	 */
	public static boolean canWatch(ServerPlayer player) {
		return allows(player, PermissionNodes.WATCH, ConfigManager.get().permissionWatch);
	}

	/** Whether media-server hosts may appear in status output, error text and logs shown to this player. */
	public static boolean canSeeMediaHosts(ServerPlayer player) {
		return allows(player, PermissionNodes.SEE_HOSTS, ConfigManager.get().permissionSeeMediaHosts);
	}

	public static boolean canUseCustomPosterUrls(ServerPlayer player) {
		return canPlacePosters(player)
			&& allows(player, PermissionNodes.POSTER_URLS, ConfigManager.get().permissionCustomPosterUrls);
	}

	public static boolean canPause(ServerPlayer player) {
		return canControlPlayback(player);
	}

	public static boolean canSeek(ServerPlayer player) {
		return canControlPlayback(player);
	}

	public static boolean canStop(ServerPlayer player) {
		return canControlPlayback(player);
	}

	public static boolean canChangeContent(ServerPlayer player) {
		return canControlPlayback(player);
	}

	public static boolean canPlacePosters(ServerPlayer player) {
		return allows(player, PermissionNodes.POSTERS, ConfigManager.get().permissionPlacePosters);
	}

	public static boolean canConfigureTunarr(ServerPlayer player) {
		return allows(player, PermissionNodes.CONFIGURE_TUNARR, ConfigManager.get().permissionConfigureTunarr);
	}

	public static boolean canConfigureJellyfin(ServerPlayer player) {
		return allows(player, PermissionNodes.CONFIGURE_JELLYFIN, ConfigManager.get().permissionConfigureJellyfin);
	}

	public static boolean canConfigureEmby(ServerPlayer player) {
		return allows(player, PermissionNodes.CONFIGURE_EMBY, ConfigManager.get().permissionConfigureEmby);
	}

	public static boolean canConfigurePlex(ServerPlayer player) {
		return allows(player, PermissionNodes.CONFIGURE_PLEX, ConfigManager.get().permissionConfigurePlex);
	}

	public static boolean canConfigureProvider(ServerPlayer player, OnDemandProvider provider) {
		return switch (provider) {
			case JELLYFIN -> canConfigureJellyfin(player);
			case EMBY -> canConfigureEmby(player);
			case PLEX -> canConfigurePlex(player);
		};
	}

	public static boolean canRefreshLibrary(ServerPlayer player) {
		return allows(player, PermissionNodes.REFRESH_LIBRARY, ConfigManager.get().permissionRefreshLibrary);
	}

	public static boolean canConfigureAudioZone(ServerPlayer player) {
		return allows(player, PermissionNodes.AUDIO_ZONE, ConfigManager.get().permissionConfigureAudioZone);
	}

	/** Single gate for every screen action, shared by the GUI packets and the /tv command. */
	public static boolean canPerform(ServerPlayer player, ScreenAction action) {
		return switch (action) {
			case POWER_TOGGLE, POWER_ON, POWER_OFF, VOLUME_SET, CHANNEL_NEXT, CHANNEL_PREVIOUS ->
				canChangeContent(player) || canPlayTunarr(player);
			case STOP -> canStop(player);
			case RESUME, UNPAUSE, PAUSE, PAUSE_TOGGLE -> canPause(player);
			case SEEK, SEEK_FORWARD, SEEK_BACKWARD, RESTART -> canSeek(player);
			case PLAY_NEXT_NOW, CANCEL_NEXT, CYCLE_SUBTITLE, SELECT_SUBTITLE -> canChangeContent(player);
			// Duration is a measurement the server cannot make itself; ServerNetworking validates the value.
			case REPORT_DURATION -> canWatch(player);
		};
	}

	/**
	 * Resolves one permission. A permission manager such as LuckPerms wins when it has an explicit answer for
	 * {@code node}, which is what lets an admin grant playback to a rank without making anybody an operator.
	 * With no manager installed, or with the node left unset, the config rule decides.
	 */
	private static boolean allows(ServerPlayer player, String node, String rule) {
		Optional<Boolean> granted = PermissionNodes.value(player, node);
		return granted.isPresent() ? granted.get() : allows(player, rule);
	}

	/**
	 * Evaluates a config rule on its own. Accepts {@code everyone}, {@code op}, {@code none}, or a
	 * comma-separated list of player names and UUIDs, which may itself include {@code op} as one entry.
	 */
	public static boolean allows(ServerPlayer player, String rule) {
		if (rule == null || rule.isBlank()) {
			return true;
		}
		String normalized = rule.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals(EVERYONE) || normalized.equals("*") || normalized.equals("true")) {
			return true;
		}
		if (normalized.equals(OP) || normalized.equals("ops") || normalized.equals("operator")) {
			return isOperator(player);
		}
		if (normalized.equals("false") || normalized.equals("none") || normalized.equals("deny")) {
			return false;
		}
		String name = player.getGameProfile().getName().toLowerCase(Locale.ROOT);
		String uuid = player.getUUID().toString().toLowerCase(Locale.ROOT);
		for (String part : normalized.split(",")) {
			String token = part.trim();
			if (token.isEmpty()) {
				continue;
			}
			if (token.equals(OP) && isOperator(player)) {
				return true;
			}
			// Names change; accepting the UUID as well keeps a list stable across renames.
			if (token.equals(name) || token.equals(uuid)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isOperator(ServerPlayer player) {
		return player.hasPermissions(Commands.LEVEL_GAMEMASTERS);
	}

	public static PixelReelConfig.FeatureFlags featureFlags(ServerPlayer player) {
		PixelReelConfig config = ConfigManager.get();
		boolean moviesPerm = canPlayOnDemandMovies(player);
		boolean showsPerm = canPlayOnDemandShows(player);
		boolean jellyfinMovies = config.isJellyfinConfigured() && config.jellyfinMoviesEnabled && moviesPerm;
		boolean jellyfinShows = config.isJellyfinConfigured() && config.jellyfinTvShowsEnabled && showsPerm;
		boolean embyMovies = config.isEmbyConfigured() && config.embyMoviesEnabled && moviesPerm;
		boolean embyShows = config.isEmbyConfigured() && config.embyTvShowsEnabled && showsPerm;
		boolean plexMovies = config.isPlexConfigured() && config.plexMoviesEnabled && moviesPerm;
		boolean plexShows = config.isPlexConfigured() && config.plexTvShowsEnabled && showsPerm;
		return new PixelReelConfig.FeatureFlags(
			canBrowse(player),
			canPlayTunarr(player),
			jellyfinMovies || embyMovies || plexMovies,
			jellyfinShows || embyShows || plexShows,
			canControlPlayback(player),
			canConfigureTunarr(player),
			canConfigureJellyfin(player),
			canConfigureEmby(player),
			canConfigurePlex(player),
			canRefreshLibrary(player),
			config.onDemandAutoplayNextEpisode,
			jellyfinMovies,
			jellyfinShows,
			embyMovies,
			embyShows,
			plexMovies,
			plexShows
		);
	}
}
