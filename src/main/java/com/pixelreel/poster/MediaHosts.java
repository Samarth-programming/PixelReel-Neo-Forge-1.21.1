package com.pixelreel.poster;

import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Hides media-server hosts/IPs from players who are not operators or media admins. */
public final class MediaHosts {
	private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
	private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s]+");

	private MediaHosts() {
	}

	public static boolean isConfiguredMediaUrl(String url) {
		String host = hostOf(url);
		if (host.isEmpty()) {
			return false;
		}
		for (String configured : configuredHosts()) {
			if (host.equalsIgnoreCase(configured)) {
				return true;
			}
		}
		return false;
	}

	public static String redact(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String redacted = URL.matcher(text).replaceAll("[media server]");
		redacted = IPV4.matcher(redacted).replaceAll("[host]");
		for (String host : configuredHosts()) {
			if (host.length() < 3) {
				continue;
			}
			redacted = redacted.replaceAll("(?i)" + Pattern.quote(host), "[host]");
		}
		return redacted;
	}

	public static String visibleHost(String url, boolean allowed) {
		if (!allowed) {
			return "hidden";
		}
		try {
			URI uri = URI.create(url == null ? "" : url);
			String host = uri.getHost();
			if (host == null || host.isBlank()) {
				return "hidden";
			}
			return host + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
		} catch (IllegalArgumentException e) {
			return "hidden";
		}
	}

	private static List<String> configuredHosts() {
		PixelReelConfig config = ConfigManager.get();
		List<String> hosts = new ArrayList<>();
		addHost(hosts, config.jellyfinUrl);
		addHost(hosts, config.embyUrl);
		addHost(hosts, config.plexUrl);
		addHost(hosts, config.m3uUrl);
		addHost(hosts, config.xmltvUrl);
		return hosts;
	}

	private static void addHost(List<String> hosts, String url) {
		String host = hostOf(url);
		if (!host.isEmpty()) {
			hosts.add(host);
		}
	}

	private static String hostOf(String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		try {
			String value = url.strip();
			if (!value.regionMatches(true, 0, "http://", 0, 7) && !value.regionMatches(true, 0, "https://", 0, 8)) {
				return "";
			}
			URI uri = URI.create(value);
			String host = uri.getHost();
			return host == null ? "" : host.toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException e) {
			return "";
		}
	}
}
