package com.pixelreel.client.gui.poster;

import com.pixelreel.PixelReel;
import com.pixelreel.config.ConfigManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Images the player dropped into config/pixelreel-posters, offered as poster artwork. */
public final class LocalPosterLibrary {
	private static final int MAX_FILES = 256;
	private static final long RESCAN_MILLIS = 5_000L;

	private static List<String> cached = List.of();
	private static long scannedAt;

	private LocalPosterLibrary() {
	}

	public static List<String> fileNames(boolean forceRescan) {
		long now = System.currentTimeMillis();
		if (!forceRescan && scannedAt != 0L && now - scannedAt < RESCAN_MILLIS) {
			return cached;
		}
		scannedAt = now;
		Path directory = ConfigManager.posterOverrideDir();
		try (Stream<Path> files = Files.list(directory)) {
			cached = files.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(LocalPosterLibrary::isImage)
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.limit(MAX_FILES)
				.toList();
		} catch (IOException | RuntimeException e) {
			PixelReel.LOGGER.debug("Could not list local posters in {}: {}", directory, e.toString());
			cached = List.of();
		}
		return cached;
	}

	public static String titleOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		String base = dot > 0 ? fileName.substring(0, dot) : fileName;
		return base.replace('_', ' ').replace('-', ' ').strip();
	}

	private static boolean isImage(String fileName) {
		String lower = fileName.toLowerCase(Locale.ROOT);
		return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
	}
}
