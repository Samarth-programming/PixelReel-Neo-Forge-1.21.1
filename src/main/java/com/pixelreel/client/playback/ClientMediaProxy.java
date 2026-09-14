package com.pixelreel.client.playback;

import com.pixelreel.media.MediaProxy;
import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/** Rewrites the placeholder proxy host to the Minecraft server the client actually joined. */
public final class ClientMediaProxy {
	private ClientMediaProxy() {
	}

	public static String resolve(String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		if (!isPlaceholder(url)) {
			return url;
		}
		try {
			URI uri = URI.create(url);
			String host = connectionHost();
			int port = uri.getPort();
			return new URI("http", null, host, port, uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
		} catch (Exception e) {
			return url;
		}
	}

	public static boolean isPlaceholder(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		try {
			String host = URI.create(url).getHost();
			return host != null && MediaProxy.PLACEHOLDER_HOST.equalsIgnoreCase(host);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String connectionHost() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.hasSingleplayerServer()) {
			return "127.0.0.1";
		}
		ServerData data = minecraft.getCurrentServer();
		if (data == null || data.ip == null || data.ip.isBlank()) {
			return "127.0.0.1";
		}
		return hostOf(data.ip.trim());
	}

	private static String hostOf(String address) {
		String value = address;
		int scheme = value.indexOf("://");
		if (scheme >= 0) {
			value = value.substring(scheme + 3);
		}
		if (value.startsWith("[")) {
			int end = value.indexOf(']');
			return end > 1 ? value.substring(1, end) : "127.0.0.1";
		}
		int slash = value.indexOf('/');
		if (slash >= 0) {
			value = value.substring(0, slash);
		}
		int colon = value.indexOf(':');
		if (colon > 0 && value.indexOf(':', colon + 1) < 0) {
			return value.substring(0, colon);
		}
		return value;
	}
}
