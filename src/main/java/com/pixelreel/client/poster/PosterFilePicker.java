package com.pixelreel.client.poster;

import com.pixelreel.PixelReel;
import com.pixelreel.networking.ModNetworkPayloads;
import com.pixelreel.poster.CustomPosterStore;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;
import com.pixelreel.client.ClientNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Opens a native file dialog without closing Minecraft, then uploads the image or gif to the world. */
public final class PosterFilePicker {
	private static volatile boolean open;

	private PosterFilePicker() {
	}

	public static void pickAndUpload(BlockPos posterPos, Consumer<Component> status) {
		if (open) {
			return;
		}
		open = true;
		Thread thread = new Thread(() -> {
			try {
				FileDialog dialog = new FileDialog((Frame) null, "Choose a poster image or GIF", FileDialog.LOAD);
				dialog.setFilenameFilter((dir, name) -> {
					String lower = name.toLowerCase();
					return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif");
				});
				dialog.setVisible(true);
				String directory = dialog.getDirectory();
				String file = dialog.getFile();
				dialog.dispose();
				if (directory == null || file == null) {
					return;
				}
				Path path = Path.of(directory, file);
				byte[] bytes = Files.readAllBytes(path);
				Minecraft.getInstance().execute(() -> upload(posterPos, file, bytes, status));
			} catch (Exception e) {
				PixelReel.LOGGER.warn("Could not read poster file: {}", e.toString());
				Minecraft.getInstance().execute(() -> status.accept(Component.translatable("message.pixelreel.poster.upload_invalid")));
			} finally {
				open = false;
			}
		}, "pixelreel-poster-file");
		thread.setDaemon(true);
		thread.start();
	}

	public static void upload(BlockPos posterPos, String fileName, byte[] bytes, Consumer<Component> status) {
		if (!CustomPosterStore.allowedName(fileName) || bytes.length == 0 || bytes.length > CustomPosterStore.MAX_BYTES) {
			status.accept(Component.translatable("message.pixelreel.poster.upload_too_large"));
			return;
		}
		if (!ClientNetworking.canSend(ModNetworkPayloads.UploadPosterChunk.TYPE)) {
			status.accept(Component.translatable("message.pixelreel.poster.upload_invalid"));
			return;
		}
		String uploadId = UUID.randomUUID().toString().replace("-", "");
		status.accept(Component.translatable("gui.pixelreel.poster.uploading"));
		int offset = 0;
		while (offset < bytes.length) {
			int length = Math.min(CustomPosterStore.CHUNK_BYTES, bytes.length - offset);
			byte[] chunk = Arrays.copyOfRange(bytes, offset, offset + length);
			ClientNetworking.send(new ModNetworkPayloads.UploadPosterChunk(
				posterPos,
				uploadId,
				fileName,
				bytes.length,
				offset,
				chunk
			));
			offset += length;
		}
	}
}
