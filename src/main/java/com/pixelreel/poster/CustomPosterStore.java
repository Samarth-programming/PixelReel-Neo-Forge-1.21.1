package com.pixelreel.poster;

import com.pixelreel.PixelReel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/** Custom png/jpg/gif files stored in the world save so players never have to leave the game to add posters. */
public final class CustomPosterStore {
	public static final int MAX_BYTES = 4 * 1024 * 1024;
	public static final int CHUNK_BYTES = 16 * 1024;

	private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
	private static volatile @Nullable Path directory;

	private CustomPosterStore() {
	}

	public static void attach(MinecraftServer server) {
		directory = server.getWorldPath(LevelResource.ROOT).resolve("pixelreel-posters");
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			PixelReel.LOGGER.warn("Could not create world poster folder {}: {}", directory, e.toString());
		}
		CACHE.clear();
	}

	public static void detach() {
		directory = null;
		CACHE.clear();
	}

	public static boolean isId(String id) {
		if (id == null || id.length() != 32) {
			return false;
		}
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
				return false;
			}
		}
		return true;
	}

	public static String newId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	public static String save(String suggestedName, byte[] bytes) throws IOException {
		if (directory == null) {
			throw new IOException("Poster store is not attached to a world");
		}
		if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
			throw new IOException("Poster file is empty or too large");
		}
		if (!PosterImages.looksLikeImage(bytes)) {
			throw new IOException("Not a png, jpg, or gif");
		}
		Files.createDirectories(directory);
		String id = newId();
		String extension = PosterImages.extensionOf(bytes, suggestedName);
		Path file = directory.resolve(id + extension);
		Files.write(file, bytes);
		CACHE.put(id, bytes);
		return id;
	}

	public static byte @Nullable [] load(String id) {
		if (!isId(id)) {
			return null;
		}
		byte[] cached = CACHE.get(id);
		if (cached != null) {
			return cached;
		}
		Path dir = directory;
		if (dir == null) {
			return null;
		}
		for (String extension : new String[]{".gif", ".png", ".jpg", ".jpeg"}) {
			Path file = dir.resolve(id + extension);
			if (!Files.isRegularFile(file)) {
				continue;
			}
			try {
				byte[] bytes = Files.readAllBytes(file);
				if (bytes.length > 0 && bytes.length <= MAX_BYTES) {
					CACHE.put(id, bytes);
					return bytes;
				}
			} catch (IOException e) {
				PixelReel.LOGGER.debug("Could not read uploaded poster {}: {}", file, e.toString());
			}
		}
		return null;
	}

	public static String titleOf(String fileName) {
		if (fileName == null || fileName.isBlank()) {
			return "Custom Poster";
		}
		String base = Path.of(fileName.replace('\\', '/')).getFileName().toString();
		int dot = base.lastIndexOf('.');
		if (dot > 0) {
			base = base.substring(0, dot);
		}
		base = base.replace('_', ' ').replace('-', ' ').strip();
		return base.isEmpty() ? "Custom Poster" : base;
	}

	public static boolean allowedName(String fileName) {
		if (fileName == null || fileName.isBlank() || fileName.length() > 128) {
			return false;
		}
		String lower = fileName.toLowerCase(Locale.ROOT);
		return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif");
	}
}
