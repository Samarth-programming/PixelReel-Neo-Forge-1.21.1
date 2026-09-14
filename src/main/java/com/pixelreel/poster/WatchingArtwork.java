package com.pixelreel.poster;

import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.jellyfin.JellyfinItemKind;

/** Artwork a Currently Watching hanging should show for a screen. */
public final class WatchingArtwork {
	private WatchingArtwork() {
	}

	public static String imageUrl(DisplayBlockEntity display) {
		String url = display.getMediaImageUrl();
		return seriesPosterUrl(url, display.getJellyfinItemId(), display.getJellyfinSeriesId(), display.getJellyfinKind());
	}

	public static String seriesPosterUrl(String url, String itemId, String seriesId, JellyfinItemKind kind) {
		if (url == null || url.isEmpty()) {
			return "";
		}
		if (kind != JellyfinItemKind.EPISODE && kind != JellyfinItemKind.SEASON) {
			return url;
		}
		if (itemId == null || itemId.isEmpty() || seriesId == null || seriesId.isEmpty() || itemId.equals(seriesId)) {
			return url;
		}
		return url
			.replace("/Items/" + itemId + "/", "/Items/" + seriesId + "/")
			.replace("/library/metadata/" + itemId + "/", "/library/metadata/" + seriesId + "/");
	}

	public static String cacheId(DisplayBlockEntity display) {
		JellyfinItemKind kind = display.getJellyfinKind();
		String seriesId = display.getJellyfinSeriesId();
		if ((kind == JellyfinItemKind.EPISODE || kind == JellyfinItemKind.SEASON) && !seriesId.isEmpty()) {
			return "series:" + seriesId;
		}
		String itemId = display.getJellyfinItemId();
		if (!itemId.isEmpty()) {
			return "item:" + itemId;
		}
		String channelId = display.getChannelId();
		if (!channelId.isEmpty()) {
			return "ch:" + channelId;
		}
		return "url:" + Integer.toHexString(imageUrl(display).hashCode());
	}

	public static String title(DisplayBlockEntity display) {
		JellyfinItemKind kind = display.getJellyfinKind();
		if (kind == JellyfinItemKind.EPISODE || kind == JellyfinItemKind.SEASON) {
			String channelName = display.getChannelName();
			int split = channelName.indexOf(" - ");
			if (split > 0) {
				return channelName.substring(0, split);
			}
		}
		return display.getMediaTitle();
	}
}
