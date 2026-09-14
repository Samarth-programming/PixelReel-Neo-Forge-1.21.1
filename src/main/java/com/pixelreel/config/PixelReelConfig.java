package com.pixelreel.config;

import com.pixelreel.ondemand.EmbyStyleConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** config/pixelreel.json */
public final class PixelReelConfig {
	public String m3uUrl = "";
	public String xmltvUrl = "";

	public String jellyfinUrl = "";
	public String jellyfinApiKey = "";
	public String jellyfinUserId = "";
	public boolean jellyfinMoviesEnabled = true;
	public boolean jellyfinTvShowsEnabled = true;
	public List<String> jellyfinLibraryIds = new ArrayList<>();
	public boolean jellyfinAutoplayNextEpisode = true;
	public int jellyfinLibraryCacheSeconds = 600;
	public int jellyfinProgressReportSeconds = 30;
	public int jellyfinNextEpisodeCountdownSeconds = 10;

	public String embyUrl = "";
	public String embyApiKey = "";
	public String embyUserId = "";
	public boolean embyMoviesEnabled = true;
	public boolean embyTvShowsEnabled = true;
	public List<String> embyLibraryIds = new ArrayList<>();
	public int embyLibraryCacheSeconds = 600;

	public String plexUrl = "";
	public String plexToken = "";
	public boolean plexMoviesEnabled = true;
	public boolean plexTvShowsEnabled = true;
	public List<String> plexLibraryKeys = new ArrayList<>();
	public int plexLibraryCacheSeconds = 600;

	public boolean onDemandAutoplayNextEpisode = true;

	public String permissionBrowse = "everyone";
	public String permissionPlayTunarr = "everyone";
	public String permissionPlayJellyfinMovies = "everyone";
	public String permissionPlayJellyfinShows = "everyone";
	public String permissionControlPlayback = "everyone";
	public String permissionPlacePosters = "everyone";
	public String permissionConfigureTunarr = "op";
	public String permissionConfigureJellyfin = "op";
	public String permissionConfigureEmby = "op";
	public String permissionConfigurePlex = "op";
	public String permissionRefreshLibrary = "op";
	/**
	 * Who receives the raw stream URL. A client cannot decode video without connecting straight to the
	 * media server, so anyone in this list learns its address. Narrow it on public servers.
	 */
	public String permissionWatch = "everyone";
	/** Who may hang a poster pointing at an arbitrary web address. */
	public String permissionCustomPosterUrls = "everyone";
	/** Who may see media-server hosts in status output and error text. */
	public String permissionSeeMediaHosts = "op";

	/**
	 * Hosts custom poster URLs may point at. Empty means any public host. Entries match the host itself
	 * plus its subdomains, e.g. "image.tmdb.org" or "imgur.com".
	 */
	public List<String> posterUrlHostAllowlist = new ArrayList<>();

	public double globalTvVolume = 1.5;

	public int maxSimultaneousChannels = 2;

	public String artworkCacheLocation = "pixelreel-cache";
	public int artworkCacheHours = 24;

	public boolean channelPrewarmingEnabled = true;
	public int prewarmLimit = 1;
	public int warmPlayerTimeoutSeconds = 20;

	public double defaultDisplayVolume = 1.0;

	/** Default room zone for `/tv zone here` (blocks). Tunable in config/pixelreel.json. */
	public int audioZoneDefaultWidth = 30;
	public int audioZoneDefaultDepth = 35;
	public int audioZoneDefaultHeight = 12;
	/** Soft fade width at zone edges (blocks). 0 = full volume anywhere inside (recommended for theaters). */
	public double audioZoneEdgeFadeBlocks = 0.0;
	public String permissionConfigureAudioZone = "op";

	public int networkTimeoutSeconds = 20;
	/**
	 * HTTP port clients use to fetch video and artwork without ever seeing an API key. 0 picks an
	 * ephemeral port. Dedicated servers must open this port next to 25565.
	 */
	public int mediaProxyPort = 25567;
	/**
	 * Hostname clients should use for the proxy. Empty means each client uses the same host it used
	 * to reach Minecraft (rewriting {@code pixelreel.invalid}). Set this when that host is not reachable
	 * on the proxy port (NAT, reverse proxy, etc.).
	 */
	public String mediaProxyPublicHost = "";
	public int streamCachingMillis = 3000;
	public int streamReconnectAttempts = 5;
	public int channelCacheSeconds = 300;
	public int maximumVideoResolution = 1280;
	public int maximumPlaybackDistance = 96;

	public Map<String, String> posterOverrides = new HashMap<>();

	public PixelReelConfig validated() {
		PixelReelConfig copy = new PixelReelConfig();
		copy.m3uUrl = normalizeUrl(this.m3uUrl);
		copy.xmltvUrl = normalizeUrl(this.xmltvUrl);
		copy.jellyfinUrl = normalizeUrl(this.jellyfinUrl);
		copy.jellyfinApiKey = this.jellyfinApiKey == null ? "" : this.jellyfinApiKey.trim();
		copy.jellyfinUserId = this.jellyfinUserId == null ? "" : this.jellyfinUserId.trim();
		copy.jellyfinMoviesEnabled = this.jellyfinMoviesEnabled;
		copy.jellyfinTvShowsEnabled = this.jellyfinTvShowsEnabled;
		copy.jellyfinLibraryIds = this.jellyfinLibraryIds == null
			? new ArrayList<>()
			: new ArrayList<>(this.jellyfinLibraryIds.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).toList());
		copy.jellyfinAutoplayNextEpisode = this.jellyfinAutoplayNextEpisode;
		copy.jellyfinLibraryCacheSeconds = clamp(this.jellyfinLibraryCacheSeconds, 30, 86400, 600);
		copy.jellyfinProgressReportSeconds = clamp(this.jellyfinProgressReportSeconds, 5, 300, 30);
		copy.jellyfinNextEpisodeCountdownSeconds = clamp(this.jellyfinNextEpisodeCountdownSeconds, 3, 60, 10);
		copy.embyUrl = normalizeUrl(this.embyUrl);
		copy.embyApiKey = this.embyApiKey == null ? "" : this.embyApiKey.trim();
		copy.embyUserId = this.embyUserId == null ? "" : this.embyUserId.trim();
		copy.embyMoviesEnabled = this.embyMoviesEnabled;
		copy.embyTvShowsEnabled = this.embyTvShowsEnabled;
		copy.embyLibraryIds = this.embyLibraryIds == null
			? new ArrayList<>()
			: new ArrayList<>(this.embyLibraryIds.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).toList());
		copy.embyLibraryCacheSeconds = clamp(this.embyLibraryCacheSeconds, 30, 86400, 600);
		copy.plexUrl = normalizeUrl(this.plexUrl);
		copy.plexToken = this.plexToken == null ? "" : this.plexToken.trim();
		copy.plexMoviesEnabled = this.plexMoviesEnabled;
		copy.plexTvShowsEnabled = this.plexTvShowsEnabled;
		copy.plexLibraryKeys = this.plexLibraryKeys == null
			? new ArrayList<>()
			: new ArrayList<>(this.plexLibraryKeys.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).toList());
		copy.plexLibraryCacheSeconds = clamp(this.plexLibraryCacheSeconds, 30, 86400, 600);
		copy.onDemandAutoplayNextEpisode = this.onDemandAutoplayNextEpisode || this.jellyfinAutoplayNextEpisode;
		copy.permissionBrowse = normalizePermission(this.permissionBrowse, "everyone");
		copy.permissionPlayTunarr = normalizePermission(this.permissionPlayTunarr, "everyone");
		copy.permissionPlayJellyfinMovies = normalizePermission(this.permissionPlayJellyfinMovies, "everyone");
		copy.permissionPlayJellyfinShows = normalizePermission(this.permissionPlayJellyfinShows, "everyone");
		copy.permissionControlPlayback = normalizePermission(this.permissionControlPlayback, "everyone");
		copy.permissionPlacePosters = normalizePermission(this.permissionPlacePosters, "everyone");
		copy.permissionConfigureTunarr = normalizePermission(this.permissionConfigureTunarr, "op");
		copy.permissionConfigureJellyfin = normalizePermission(this.permissionConfigureJellyfin, "op");
		copy.permissionConfigureEmby = normalizePermission(this.permissionConfigureEmby, "op");
		copy.permissionConfigurePlex = normalizePermission(this.permissionConfigurePlex, "op");
		copy.permissionRefreshLibrary = normalizePermission(this.permissionRefreshLibrary, "op");
		copy.permissionWatch = normalizePermission(this.permissionWatch, "everyone");
		copy.permissionCustomPosterUrls = normalizePermission(this.permissionCustomPosterUrls, "everyone");
		copy.permissionSeeMediaHosts = normalizePermission(this.permissionSeeMediaHosts, "op");
		copy.posterUrlHostAllowlist = this.posterUrlHostAllowlist == null
			? new ArrayList<>()
			: new ArrayList<>(this.posterUrlHostAllowlist.stream()
				.filter(host -> host != null && !host.isBlank())
				.map(host -> host.trim().toLowerCase(java.util.Locale.ROOT))
				.toList());
		copy.globalTvVolume = clamp(this.globalTvVolume, 0.0, 2.0, 1.5);
		copy.maxSimultaneousChannels = clamp(this.maxSimultaneousChannels, 1, 4, 2);
		copy.artworkCacheLocation = this.artworkCacheLocation == null || this.artworkCacheLocation.isBlank()
			? "pixelreel-cache"
			: this.artworkCacheLocation.trim();
		copy.artworkCacheHours = clamp(this.artworkCacheHours, 1, 24 * 30, 24);
		copy.channelPrewarmingEnabled = this.channelPrewarmingEnabled;
		copy.prewarmLimit = clamp(this.prewarmLimit, 0, 4, 1);
		copy.warmPlayerTimeoutSeconds = clamp(this.warmPlayerTimeoutSeconds, 5, 600, 20);
		copy.defaultDisplayVolume = clamp(this.defaultDisplayVolume, 0.0, 1.0, 1.0);
		copy.audioZoneDefaultWidth = clamp(this.audioZoneDefaultWidth, 1, 256, 30);
		copy.audioZoneDefaultDepth = clamp(this.audioZoneDefaultDepth, 1, 256, 35);
		copy.audioZoneDefaultHeight = clamp(this.audioZoneDefaultHeight, 1, 64, 12);
		copy.audioZoneEdgeFadeBlocks = clamp(this.audioZoneEdgeFadeBlocks, 0.0, 8.0, 0.0);
		copy.permissionConfigureAudioZone = normalizePermission(this.permissionConfigureAudioZone, "op");
		copy.networkTimeoutSeconds = clamp(this.networkTimeoutSeconds, 1, 120, 20);
		copy.mediaProxyPort = this.mediaProxyPort <= 0 ? 0 : clamp(this.mediaProxyPort, 1, 65535, 25567);
		copy.mediaProxyPublicHost = this.mediaProxyPublicHost == null ? "" : this.mediaProxyPublicHost.trim();
		copy.streamCachingMillis = clamp(this.streamCachingMillis, 0, 10000, 3000);
		copy.streamReconnectAttempts = clamp(this.streamReconnectAttempts, 0, 20, 5);
		copy.channelCacheSeconds = clamp(this.channelCacheSeconds, 5, 86400, 300);
		copy.maximumVideoResolution = clamp(this.maximumVideoResolution, 128, 1280, 1280);
		copy.maximumPlaybackDistance = clamp(this.maximumPlaybackDistance, 8, 256, 96);
		copy.posterOverrides = this.posterOverrides == null ? new HashMap<>() : new HashMap<>(this.posterOverrides);
		return copy;
	}

	public boolean isConfigured() {
		return !this.m3uUrl.isEmpty();
	}

	public boolean isJellyfinConfigured() {
		return !this.jellyfinUrl.isEmpty() && !this.jellyfinApiKey.isEmpty();
	}

	public boolean isEmbyConfigured() {
		return !this.embyUrl.isEmpty() && !this.embyApiKey.isEmpty();
	}

	public boolean isPlexConfigured() {
		return !this.plexUrl.isEmpty() && !this.plexToken.isEmpty();
	}

	public boolean isAnyOnDemandConfigured() {
		return this.isJellyfinConfigured() || this.isEmbyConfigured() || this.isPlexConfigured();
	}

	public EmbyStyleConnection jellyfinConnection() {
		return new EmbyStyleConnection("Jellyfin", this.jellyfinUrl, this.jellyfinApiKey, this.jellyfinUserId);
	}

	public EmbyStyleConnection embyConnection() {
		return new EmbyStyleConnection("Emby", this.embyUrl, this.embyApiKey, this.embyUserId);
	}

	public record FeatureFlags(
		boolean canBrowse,
		boolean canPlayTunarr,
		boolean canPlayMovies,
		boolean canPlayShows,
		boolean canControlPlayback,
		boolean canConfigureTunarr,
		boolean canConfigureJellyfin,
		boolean canConfigureEmby,
		boolean canConfigurePlex,
		boolean canRefreshLibrary,
		boolean autoplayNextEpisode,
		boolean jellyfinMovies,
		boolean jellyfinShows,
		boolean embyMovies,
		boolean embyShows,
		boolean plexMovies,
		boolean plexShows
	) {
	}

	public record TunarrPublicConfig(String m3uUrl, String xmltvUrl) {
	}

	public record JellyfinPublicConfig(
		String url,
		String userId,
		boolean moviesEnabled,
		boolean tvShowsEnabled,
		boolean autoplayNextEpisode,
		List<String> libraryIds,
		boolean hasApiKey
	) {
	}

	public record EmbyPublicConfig(
		String url,
		String userId,
		boolean moviesEnabled,
		boolean tvShowsEnabled,
		List<String> libraryIds,
		boolean hasApiKey
	) {
	}

	public record PlexPublicConfig(
		String url,
		boolean moviesEnabled,
		boolean tvShowsEnabled,
		List<String> libraryKeys,
		boolean hasToken
	) {
	}

	public TunarrPublicConfig tunarrPublicConfig() {
		return new TunarrPublicConfig(this.m3uUrl, this.xmltvUrl);
	}

	public JellyfinPublicConfig jellyfinPublicConfig() {
		return new JellyfinPublicConfig(
			this.jellyfinUrl,
			this.jellyfinUserId,
			this.jellyfinMoviesEnabled,
			this.jellyfinTvShowsEnabled,
			this.onDemandAutoplayNextEpisode,
			List.copyOf(this.jellyfinLibraryIds),
			!this.jellyfinApiKey.isEmpty()
		);
	}

	public EmbyPublicConfig embyPublicConfig() {
		return new EmbyPublicConfig(
			this.embyUrl,
			this.embyUserId,
			this.embyMoviesEnabled,
			this.embyTvShowsEnabled,
			List.copyOf(this.embyLibraryIds),
			!this.embyApiKey.isEmpty()
		);
	}

	public PlexPublicConfig plexPublicConfig() {
		return new PlexPublicConfig(
			this.plexUrl,
			this.plexMoviesEnabled,
			this.plexTvShowsEnabled,
			List.copyOf(this.plexLibraryKeys),
			!this.plexToken.isEmpty()
		);
	}

	public static TunarrPublicConfig expandTunarrUrls(String m3uOrBase, String xmltvOrBlank) {
		String m3u = normalizeUrl(m3uOrBase);
		String xmltv = normalizeUrl(xmltvOrBlank);
		if (m3u.isEmpty()) {
			return new TunarrPublicConfig("", xmltv);
		}
		String lower = m3u.toLowerCase();
		boolean looksLikePlaylist = lower.contains(".m3u") || lower.contains("/api/channels");
		if (!looksLikePlaylist) {
			String base = stripTrailingSlash(m3u);
			m3u = base + "/api/channels.m3u";
			if (xmltv.isEmpty()) {
				xmltv = base + "/api/xmltv.xml";
			}
		} else if (xmltv.isEmpty()) {
			int marker = lower.indexOf("/api/channels");
			if (marker >= 0) {
				xmltv = m3u.substring(0, marker) + "/api/xmltv.xml";
			}
		}
		return new TunarrPublicConfig(m3u, xmltv);
	}

	private static String stripTrailingSlash(String url) {
		String value = url;
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	public String mediaServerHost() {
		try {
			String host = URI.create(this.m3uUrl).getHost();
			return host == null ? "" : host;
		} catch (IllegalArgumentException e) {
			return "";
		}
	}

	public static String normalizeUrl(String raw) {
		if (raw == null) {
			return "";
		}
		String url = raw.trim();
		if (url.isEmpty()) {
			return "";
		}
		if (!url.regionMatches(true, 0, "http://", 0, 7) && !url.regionMatches(true, 0, "https://", 0, 8)) {
			url = "http://" + url;
		}
		try {
			URI uri = URI.create(url);
			String scheme = uri.getScheme();
			if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
				return "";
			}
			return uri.getHost() != null && !uri.getHost().isBlank() ? url : "";
		} catch (IllegalArgumentException e) {
			return "";
		}
	}

	private static String normalizePermission(String raw, String fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		return raw.trim();
	}

	private static double clamp(double value, double min, double max, double fallback) {
		return Double.isNaN(value) || Double.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value));
	}

	private static int clamp(int value, int min, int max, int fallback) {
		return value == 0 && min > 0 ? fallback : Math.max(min, Math.min(max, value));
	}
}
