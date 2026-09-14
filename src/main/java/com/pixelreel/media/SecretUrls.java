package com.pixelreel.media;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Detects and strips media-server credentials that must never leave the Minecraft server. */
public final class SecretUrls {
	private static final Set<String> SECRET_PARAMS = Set.of(
		"api_key",
		"apikey",
		"api-key",
		"x-plex-token",
		"x-emby-token",
		"access_token",
		"accesstoken",
		"auth_token",
		"authtoken",
		"plex_token",
		"token"
	);

	private SecretUrls() {
	}

	public static boolean hasCredential(@Nullable String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		try {
			URI uri = URI.create(url.strip());
			if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
				return true;
			}
			return queryHasSecret(uri.getRawQuery()) || queryHasSecret(uri.getRawFragment());
		} catch (IllegalArgumentException e) {
			String lower = url.toLowerCase(Locale.ROOT);
			return lower.contains("api_key=") || lower.contains("x-plex-token=") || lower.contains("access_token=");
		}
	}

	/** Drops userinfo and known credential query parameters. Returns "" when the input is unusable. */
	public static String strip(@Nullable String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		try {
			URI uri = URI.create(url.strip());
			String query = stripQuery(uri.getRawQuery());
			return new URI(
				uri.getScheme(),
				null,
				uri.getHost(),
				uri.getPort(),
				uri.getPath(),
				query.isEmpty() ? null : query,
				uri.getFragment()
			).toString();
		} catch (Exception e) {
			return "";
		}
	}

	private static boolean queryHasSecret(@Nullable String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return false;
		}
		for (String part : rawQuery.split("&")) {
			int eq = part.indexOf('=');
			String name = eq < 0 ? part : part.substring(0, eq);
			if (SECRET_PARAMS.contains(decode(name).toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private static String stripQuery(@Nullable String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return "";
		}
		StringBuilder kept = new StringBuilder();
		for (String part : rawQuery.split("&")) {
			if (part.isEmpty()) {
				continue;
			}
			int eq = part.indexOf('=');
			String name = eq < 0 ? part : part.substring(0, eq);
			if (SECRET_PARAMS.contains(decode(name).toLowerCase(Locale.ROOT))) {
				continue;
			}
			if (!kept.isEmpty()) {
				kept.append('&');
			}
			kept.append(part);
		}
		return kept.toString();
	}

	private static String decode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return value;
		}
	}

}
