package com.pixelreel.poster;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Magic-byte and address checks shared by uploads, downloads, and the texture loader. */
public final class PosterImages {
	private static final Pattern LITERAL_IP = Pattern.compile("^[0-9.]+$|^[0-9a-fA-F:]*:[0-9a-fA-F:.]*$");

	private PosterImages() {
	}

	public static boolean looksLikeImage(byte[] bytes) {
		if (bytes == null || bytes.length < 12) {
			return false;
		}
		if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
			return true;
		}
		if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
			return true;
		}
		return bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
	}

	public static boolean isGif(byte[] bytes) {
		return bytes != null && bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
	}

	public static String extensionOf(byte[] bytes, @Nullable String fileName) {
		if (isGif(bytes)) {
			return ".gif";
		}
		if (bytes != null && bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50) {
			return ".png";
		}
		String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".jpeg")) {
			return ".jpeg";
		}
		return ".jpg";
	}

	/**
	 * Shape check for a player-supplied image address. Every client in range fetches whatever is hung on a
	 * poster, so this rejects anything that could aim those requests at a private network, a loopback
	 * service, or a cloud metadata endpoint. Hostnames are only resolved by {@link #resolvesToPublicHost},
	 * which blocks and therefore has to run off the render thread.
	 */
	public static boolean isSafeHttpUrl(String url) {
		URI uri = parse(url);
		if (uri == null) {
			return false;
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			return false;
		}
		// Credentials in the URL would be forwarded verbatim by every client that renders the poster.
		if (uri.getUserInfo() != null) {
			return false;
		}
		InetAddress literal = parseLiteralAddress(host);
		if (literal != null) {
			return isPublicAddress(literal);
		}
		String lower = host.toLowerCase(Locale.ROOT);
		return !lower.equals("localhost") && !lower.endsWith(".localhost") && !lower.endsWith(".internal") && !lower.endsWith(".local");
	}

	/** True when the configured allowlist is empty or the host is one of its entries (or a subdomain). */
	public static boolean isAllowedByHostPolicy(String url, List<String> allowlist) {
		if (allowlist == null || allowlist.isEmpty()) {
			return true;
		}
		URI uri = parse(url);
		String host = uri == null || uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		if (host.isEmpty()) {
			return false;
		}
		for (String allowed : allowlist) {
			String entry = allowed.trim().toLowerCase(Locale.ROOT);
			if (!entry.isEmpty() && (host.equals(entry) || host.endsWith("." + entry))) {
				return true;
			}
		}
		return false;
	}

	public static boolean resolvesToPublicHost(String url) {
		URI uri = parse(url);
		if (uri == null || uri.getHost() == null) {
			return false;
		}
		try {
			InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
			if (addresses.length == 0) {
				return false;
			}
			for (InetAddress address : addresses) {
				if (!isPublicAddress(address)) {
					return false;
				}
			}
			return true;
		} catch (UnknownHostException | SecurityException e) {
			return false;
		}
	}

	private static @Nullable URI parse(String url) {
		if (url == null) {
			return null;
		}
		String trimmed = url.strip();
		if (trimmed.length() < 12 || trimmed.length() > PosterArt.MAX_URL) {
			return null;
		}
		if (!trimmed.regionMatches(true, 0, "http://", 0, 7) && !trimmed.regionMatches(true, 0, "https://", 0, 8)) {
			return null;
		}
		try {
			URI uri = URI.create(trimmed);
			return uri.isAbsolute() ? uri : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static @Nullable InetAddress parseLiteralAddress(String host) {
		String candidate = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
		if (!LITERAL_IP.matcher(candidate).matches()) {
			return null;
		}
		try {
			return InetAddress.getByName(candidate);
		} catch (UnknownHostException e) {
			return null;
		}
	}

	private static boolean isPublicAddress(InetAddress address) {
		if (address.isAnyLocalAddress()
			|| address.isLoopbackAddress()
			|| address.isLinkLocalAddress()
			|| address.isSiteLocalAddress()
			|| address.isMulticastAddress()) {
			return false;
		}
		byte[] bytes = address.getAddress();
		if (bytes.length == 4) {
			int first = bytes[0] & 0xFF;
			int second = bytes[1] & 0xFF;
			if (first == 100 && second >= 64 && second <= 127) {
				return false;
			}
			return first != 198 || second != 18 && second != 19;
		}
		return (bytes[0] & 0xFE) != 0xFC;
	}
}
