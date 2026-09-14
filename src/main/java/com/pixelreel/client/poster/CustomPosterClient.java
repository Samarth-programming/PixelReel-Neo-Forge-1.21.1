package com.pixelreel.client.poster;

import com.pixelreel.client.texture.PosterCache;
import com.pixelreel.networking.ModNetworkPayloads;
import com.pixelreel.poster.CustomPosterStore;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.pixelreel.client.ClientNetworking;
import org.jetbrains.annotations.Nullable;

/** Downloads world-saved custom posters (including gifs) so every player sees the same hanging. */
public final class CustomPosterClient {
	private static final Map<String, byte[]> COMPLETE = new ConcurrentHashMap<>();
	private static final Map<String, Partial> PARTIAL = new ConcurrentHashMap<>();
	private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

	private CustomPosterClient() {
	}

	public static byte @Nullable [] bytes(String id) {
		if (!CustomPosterStore.isId(id)) {
			return null;
		}
		byte[] complete = COMPLETE.get(id);
		if (complete != null) {
			return complete;
		}
		if (REQUESTED.add(id) && ClientNetworking.canSend(ModNetworkPayloads.RequestCustomPoster.TYPE)) {
			ClientNetworking.send(new ModNetworkPayloads.RequestCustomPoster(id));
		}
		return null;
	}

	public static void accept(ModNetworkPayloads.CustomPosterChunk chunk) {
		if (!CustomPosterStore.isId(chunk.id()) || chunk.totalBytes() <= 0 || chunk.totalBytes() > CustomPosterStore.MAX_BYTES) {
			return;
		}
		if (chunk.offset() < 0 || chunk.offset() + chunk.data().length > chunk.totalBytes()) {
			return;
		}
		Partial partial = PARTIAL.computeIfAbsent(chunk.id(), ignored -> new Partial(chunk.totalBytes()));
		if (partial.buffer.length != chunk.totalBytes()) {
			return;
		}
		System.arraycopy(chunk.data(), 0, partial.buffer, chunk.offset(), chunk.data().length);
		partial.received += chunk.data().length;
		if (partial.received < partial.buffer.length) {
			return;
		}
		PARTIAL.remove(chunk.id());
		COMPLETE.put(chunk.id(), partial.buffer);
		PosterCache.INSTANCE.invalidatePlaced("upload:" + chunk.id());
	}

	public static void clear() {
		COMPLETE.clear();
		PARTIAL.clear();
		REQUESTED.clear();
	}

	private static final class Partial {
		final byte[] buffer;
		int received;

		Partial(int totalBytes) {
			this.buffer = new byte[totalBytes];
		}
	}
}
