package com.pixelreel.media;

import com.pixelreel.PixelReel;
import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import com.pixelreel.jellyfin.JellyfinClient;
import com.pixelreel.jellyfin.JellyfinItemSummary;
import com.pixelreel.ondemand.OnDemandCatalog;
import com.pixelreel.poster.MediaHosts;
import com.pixelreel.poster.PosterArt;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Short-lived HTTP front for media and artwork.
 *
 * <p>VLC and the poster loader have to fetch bytes over HTTP, but the upstream URLs embed a Jellyfin/Emby
 * API key or a Plex token. Clients receive only {@code http://pixelreel.invalid:&lt;port&gt;/m/&lt;token&gt;};
 * this process holds the real URL and injects it on the way out.
 */
public final class MediaProxy {
	public static final String PLACEHOLDER_HOST = "pixelreel.invalid";
	private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
	private static final int MAX_GRANTS = 4096;
	private static final long STREAM_TTL_MS = 3L * 60L * 60L * 1000L;
	private static final long IMAGE_TTL_MS = 2L * 60L * 60L * 1000L;
	private static final SecureRandom RANDOM = new SecureRandom();

	public enum Kind {
		STREAM,
		IMAGE,
		SUBTITLE
	}

	private static final Map<String, Grant> grants = new ConcurrentHashMap<>();
	private static final Map<String, String> tokensByKey = new ConcurrentHashMap<>();
	private static final Object LOCK = new Object();

	private static volatile @Nullable HttpServer server;
	private static volatile @Nullable ExecutorService executor;
	private static volatile @Nullable HttpClient http;
	private static volatile int boundPort = -1;

	private record Grant(String token, Kind kind, String upstream, long expiresAtMillis) {
		boolean expired(long now) {
			return now >= this.expiresAtMillis;
		}
	}

	private MediaProxy() {
	}

	public static void start() {
		stop();
		PixelReelConfig config = ConfigManager.get();
		int requested = config.mediaProxyPort;
		ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "pixelreel-media-proxy");
			thread.setDaemon(true);
			return thread;
		});
		HttpServer created = null;
		int port = -1;
		int first = requested <= 0 ? 0 : requested;
		int last = requested <= 0 ? 0 : requested + 10;
		for (int candidate = first; candidate <= last; candidate++) {
			try {
				created = HttpServer.create(new InetSocketAddress("0.0.0.0", candidate), 64);
				port = created.getAddress().getPort();
				break;
			} catch (IOException e) {
				if (candidate == last) {
					PixelReel.LOGGER.error(
						"Could not bind the media proxy on port {} (tried through {}). Playback will not start until this port is free.",
						requested,
						last
					);
					pool.shutdownNow();
					return;
				}
			}
		}
		if (created == null) {
			pool.shutdownNow();
			return;
		}
		created.createContext("/m/", MediaProxy::handle);
		created.setExecutor(pool);
		created.start();
		server = created;
		executor = pool;
		boundPort = port;
		http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(Math.max(3, config.networkTimeoutSeconds)))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.executor(pool)
			.build();
		PixelReel.LOGGER.info(
			"Media proxy listening on port {} — clients never receive Jellyfin/Emby/Plex keys. Open this port next to 25565.",
			port
		);
	}

	public static void stop() {
		HttpServer current = server;
		server = null;
		boundPort = -1;
		if (current != null) {
			current.stop(0);
		}
		ExecutorService pool = executor;
		executor = null;
		if (pool != null) {
			pool.shutdownNow();
		}
		http = null;
		grants.clear();
		tokensByKey.clear();
	}

	public static boolean isRunning() {
		return server != null && boundPort > 0;
	}

	public static int port() {
		return boundPort;
	}

	/** Client-safe URL, or empty when the proxy is down rather than falling back to a keyed upstream. */
	public static String forClient(Kind kind, String upstream) {
		if (upstream == null || upstream.isBlank()) {
			return "";
		}
		if (!needsProxy(upstream)) {
			return upstream;
		}
		if (!isRunning()) {
			PixelReel.LOGGER.debug("Dropped a keyed media URL because the proxy is not listening");
			return "";
		}
		String token = issue(kind, upstream);
		return token.isEmpty() ? "" : publicUrl(token);
	}

	public static JellyfinItemSummary forClientItem(JellyfinItemSummary item) {
		if (item == null) {
			return null;
		}
		return item.withImageUrl(forClient(Kind.IMAGE, item.imageUrl()));
	}

	public static PosterArt forClientArtwork(PosterArt art) {
		if (art == null) {
			return PosterArt.EMPTY;
		}
		if (art.isWatching() || art.isLocal() || art.isUpload()) {
			return art;
		}
		String real = art.imageUrl();
		if (!art.itemId().isEmpty() && (real.isBlank() || SecretUrls.hasCredential(real) || MediaHosts.isConfiguredMediaUrl(real))) {
			String fromCatalog = OnDemandCatalog.artworkUrl(art.provider(), art.itemId());
			if (!fromCatalog.isBlank()) {
				real = fromCatalog;
			}
		}
		if (real.isBlank()) {
			return art.withImageUrl("");
		}
		return art.withImageUrl(forClient(Kind.IMAGE, real));
	}

	public static PosterArt forWorldSave(PosterArt art) {
		if (art == null) {
			return PosterArt.EMPTY;
		}
		if (art.isWatching() || art.isLocal() || art.isUpload()) {
			return art;
		}
		if (!art.itemId().isEmpty()) {
			return new PosterArt(art.provider(), art.itemId(), "", art.title());
		}
		if (art.imageUrl().isEmpty()) {
			return PosterArt.EMPTY;
		}
		return art.withImageUrl(SecretUrls.strip(art.imageUrl()));
	}

	public static String streamForDisplay(BlockPos pos, int epoch, String upstream) {
		if (upstream == null || upstream.isBlank()) {
			return "";
		}
		if (!needsProxy(upstream)) {
			return upstream;
		}
		if (!isRunning()) {
			return "";
		}
		String key = "s:" + pos.asLong() + ":" + epoch;
		String token = issueKeyed(key, Kind.STREAM, upstream, STREAM_TTL_MS);
		return token.isEmpty() ? "" : publicUrl(token);
	}

	public static boolean needsProxy(String url) {
		return SecretUrls.hasCredential(url) || MediaHosts.isConfiguredMediaUrl(url);
	}

	private static String issue(Kind kind, String upstream) {
		long ttl = kind == Kind.IMAGE ? IMAGE_TTL_MS : STREAM_TTL_MS;
		return issueKeyed(kind.name().toLowerCase(Locale.ROOT) + ":" + upstream, kind, upstream, ttl);
	}

	private static String issueKeyed(String key, Kind kind, String upstream, long ttlMs) {
		long now = System.currentTimeMillis();
		String existing = tokensByKey.get(key);
		if (existing != null) {
			Grant grant = grants.get(existing);
			if (grant != null && !grant.expired(now) && grant.kind() == kind && grant.upstream().equals(upstream)) {
				return existing;
			}
		}
		evictIfNeeded(now);
		String token = newToken();
		grants.put(token, new Grant(token, kind, upstream, now + ttlMs));
		tokensByKey.put(key, token);
		return token;
	}

	private static String publicUrl(String token) {
		PixelReelConfig config = ConfigManager.get();
		String host = config.mediaProxyPublicHost == null ? "" : config.mediaProxyPublicHost.trim();
		if (host.isEmpty()) {
			host = PLACEHOLDER_HOST;
		} else if (host.contains("://")) {
			try {
				URI uri = URI.create(host);
				if (uri.getHost() != null) {
					int port = uri.getPort() > 0 ? uri.getPort() : boundPort;
					return uri.getScheme() + "://" + uri.getHost() + ":" + port + "/m/" + token;
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		int colon = host.indexOf(':');
		if (colon > 0 && host.indexOf(']') < 0) {
			return "http://" + host + "/m/" + token;
		}
		return "http://" + host + ":" + boundPort + "/m/" + token;
	}

	private static void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				return;
			}
			String path = exchange.getRequestURI().getPath();
			String token = path.startsWith("/m/") ? path.substring(3) : "";
			int slash = token.indexOf('/');
			if (slash >= 0) {
				token = token.substring(0, slash);
			}
			Grant grant = grants.get(token);
			if (grant == null || grant.expired(System.currentTimeMillis())) {
				exchange.sendResponseHeaders(404, -1);
				return;
			}
			proxy(exchange, grant);
		} catch (Exception e) {
			PixelReel.LOGGER.debug("Media proxy request failed: {}", JellyfinClient.sanitizeDetail(e.getMessage()));
			try {
				exchange.sendResponseHeaders(502, -1);
			} catch (IOException ignored) {
			}
		} finally {
			exchange.close();
		}
	}

	private static void proxy(HttpExchange exchange, Grant grant) throws Exception {
		HttpClient client = http;
		if (client == null) {
			exchange.sendResponseHeaders(503, -1);
			return;
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(grant.upstream()))
			.timeout(Duration.ofSeconds(Math.max(10, ConfigManager.get().networkTimeoutSeconds)))
			.header("User-Agent", "pixelReel-proxy/1.0")
			.header("Accept", "*/*");
		String range = exchange.getRequestHeaders().getFirst("Range");
		if (range != null && !range.isBlank() && grant.kind() == Kind.STREAM) {
			builder.header("Range", range);
		}
		boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
		builder.method(head ? "HEAD" : "GET", HttpRequest.BodyPublishers.noBody());
		HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		int code = response.statusCode();
		if (code < 200 || code >= 400) {
			response.body().close();
			exchange.sendResponseHeaders(code >= 400 ? code : 502, -1);
			return;
		}
		Headers out = exchange.getResponseHeaders();
		copyHeader(response, out, "Content-Type");
		copyHeader(response, out, "Content-Range");
		copyHeader(response, out, "Accept-Ranges");
		if (head) {
			copyHeader(response, out, "Content-Length");
			response.body().close();
			exchange.sendResponseHeaders(code, -1);
			return;
		}
		if (grant.kind() != Kind.STREAM) {
			byte[] body = response.body().readNBytes(MAX_IMAGE_BYTES + 1);
			response.body().close();
			if (body.length > MAX_IMAGE_BYTES) {
				exchange.sendResponseHeaders(502, -1);
				return;
			}
			out.set("Content-Length", Integer.toString(body.length));
			exchange.sendResponseHeaders(code, body.length);
			exchange.getResponseBody().write(body);
			return;
		}
		exchange.sendResponseHeaders(code, 0);
		try (InputStream in = response.body(); OutputStream dest = exchange.getResponseBody()) {
			in.transferTo(dest);
		} catch (IOException ignored) {
			// Viewer walked away or VLC dropped the socket.
		}
	}

	private static void copyHeader(HttpResponse<?> response, Headers out, String name) {
		response.headers().firstValue(name).ifPresent(value -> out.set(name, value));
	}

	private static String newToken() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static void evictIfNeeded(long now) {
		if (grants.size() < MAX_GRANTS) {
			Iterator<Map.Entry<String, Grant>> iterator = grants.entrySet().iterator();
			while (iterator.hasNext()) {
				Grant grant = iterator.next().getValue();
				if (grant.expired(now)) {
					iterator.remove();
				}
			}
			return;
		}
		synchronized (LOCK) {
			long oldest = Long.MAX_VALUE;
			String oldestToken = null;
			for (Grant grant : grants.values()) {
				if (grant.expiresAtMillis() < oldest) {
					oldest = grant.expiresAtMillis();
					oldestToken = grant.token();
				}
			}
			if (oldestToken != null) {
				grants.remove(oldestToken);
			}
		}
	}
}
