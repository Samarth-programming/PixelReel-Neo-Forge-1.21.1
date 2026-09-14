package com.pixelreel.client.playback;

import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.networking.ModNetworkPayloads;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.pixelreel.client.ClientNetworking;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side store for the addresses the server withholds from the block entity sync.
 *
 * <p>A stream URL identifies the media server and carries its API key, so it is requested per screen rather
 * than broadcast with the chunk. Everything here is keyed by channel epoch: when a screen changes what it is
 * playing the epoch moves, the cached entry stops matching, and a fresh request goes out.
 */
public final class ClientPlaybackSecrets {
	private static final long RETRY_INTERVAL_MS = 3_000L;
	private static final long DENIED_RETRY_INTERVAL_MS = 30_000L;

	private static final Map<BlockPos, Entry> entries = new ConcurrentHashMap<>();
	private static final Map<BlockPos, Long> pendingSince = new ConcurrentHashMap<>();

	private record Entry(int epoch, String streamUrl, String subtitleUrl, String imageUrl, String plexPartKey, boolean denied) {
	}

	private ClientPlaybackSecrets() {
	}

	public static void accept(ModNetworkPayloads.PlaybackSecretsData payload) {
		BlockPos pos = payload.pos().immutable();
		pendingSince.remove(pos);
		entries.put(
			pos,
			new Entry(
				payload.channelEpoch(),
				ClientMediaProxy.resolve(payload.streamUrl()),
				ClientMediaProxy.resolve(payload.subtitleUrl()),
				ClientMediaProxy.resolve(payload.imageUrl()),
				payload.plexPartKey(),
				payload.isDenied()
			)
		);
	}

	/**
	 * The stream URL for this screen, requesting it from the server when it is missing. Returns an empty
	 * string until the reply lands, so callers skip the screen this tick and pick it up on a later one. Only
	 * call this from the client tick; use {@link #knownStreamUrl} on render paths.
	 */
	public static String resolveForPlayback(DisplayBlockEntity display) {
		Entry entry = current(display);
		if (entry != null) {
			return entry.streamUrl();
		}
		request(display);
		return "";
	}

	/** Lookup with no networking, safe to call every frame. */
	public static String knownStreamUrl(DisplayBlockEntity display) {
		Entry entry = current(display);
		return entry == null ? "" : entry.streamUrl();
	}

	public static String subtitleUrl(DisplayBlockEntity display) {
		Entry entry = current(display);
		return entry == null ? "" : entry.subtitleUrl();
	}

	/** Artwork lives on the media server too, so it travels with the stream URL rather than with the block. */
	public static String imageUrl(DisplayBlockEntity display) {
		Entry entry = current(display);
		return entry == null ? "" : entry.imageUrl();
	}

	public static String plexPartKey(DisplayBlockEntity display) {
		Entry entry = current(display);
		return entry == null ? "" : entry.plexPartKey();
	}

	/** Asks for artwork without wanting to play, used by posters that mirror what a screen is showing. */
	public static void prefetch(DisplayBlockEntity display) {
		if (current(display) == null) {
			request(display);
		}
	}

	public static void clear() {
		entries.clear();
		pendingSince.clear();
	}

	public static void forget(BlockPos pos) {
		entries.remove(pos);
		pendingSince.remove(pos);
	}

	/** A refusal is cached like any other answer so a locked-down screen is not polled every tick. */
	private static @Nullable Entry current(DisplayBlockEntity display) {
		Entry entry = entries.get(display.getBlockPos());
		return entry == null || entry.epoch() != display.getChannelEpoch() ? null : entry;
	}

	private static void request(DisplayBlockEntity display) {
		if (!ClientNetworking.canSend(ModNetworkPayloads.RequestPlaybackSecrets.TYPE)) {
			return;
		}
		BlockPos pos = display.getBlockPos().immutable();
		Entry stale = entries.get(pos);
		long interval = stale != null && stale.denied() && stale.epoch() == display.getChannelEpoch()
			? DENIED_RETRY_INTERVAL_MS
			: RETRY_INTERVAL_MS;
		long now = System.currentTimeMillis();
		Long sentAt = pendingSince.get(pos);
		if (sentAt != null && now - sentAt < interval) {
			return;
		}
		pendingSince.put(pos, now);
		ClientNetworking.send(new ModNetworkPayloads.RequestPlaybackSecrets(pos, display.getChannelEpoch()));
	}
}
