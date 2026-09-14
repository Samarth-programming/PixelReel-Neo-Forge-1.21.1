package com.pixelreel.client.texture;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.NodeList;

/** Turns a GIF into composited ARGB frames with delays, so hung posters can animate. */
final class GifDecoder {
	static final int MAX_FRAMES = 48;
	static final int MAX_EDGE = 512;

	record Animation(int width, int height, List<int[]> frames, int[] delaysMs) {
	}

	private GifDecoder() {
	}

	static boolean isGif(byte[] bytes) {
		return bytes != null
			&& bytes.length >= 6
			&& bytes[0] == 'G'
			&& bytes[1] == 'I'
			&& bytes[2] == 'F';
	}

	static @Nullable Animation decode(byte[] bytes) throws IOException {
		if (!isGif(bytes)) {
			return null;
		}
		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
		if (!readers.hasNext()) {
			return null;
		}
		ImageReader reader = readers.next();
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (input == null) {
				return null;
			}
			reader.setInput(input, false, false);
			int count = Math.min(reader.getNumImages(true), MAX_FRAMES);
			if (count <= 0) {
				return null;
			}
			int canvasWidth = Math.max(1, reader.getWidth(0));
			int canvasHeight = Math.max(1, reader.getHeight(0));
			IIOMetadata stream = reader.getStreamMetadata();
			if (stream != null) {
				int[] screen = logicalScreen(stream);
				if (screen != null) {
					canvasWidth = Math.max(canvasWidth, screen[0]);
					canvasHeight = Math.max(canvasHeight, screen[1]);
				}
			}

			BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
			Graphics2D matte = canvas.createGraphics();
			try {
				matte.setComposite(AlphaComposite.Src);
				matte.setColor(Color.BLACK);
				matte.fillRect(0, 0, canvasWidth, canvasHeight);
			} finally {
				matte.dispose();
			}
			BufferedImage backup = null;
			List<int[]> frames = new ArrayList<>(count);
			int[] delays = new int[count];
			for (int index = 0; index < count; index++) {
				BufferedImage frame = reader.read(index);
				GifFrameInfo info = frameInfo(reader.getImageMetadata(index));
				if ("restoreToPrevious".equals(info.disposal) && backup == null) {
					backup = copy(canvas);
				}
				Graphics2D graphics = canvas.createGraphics();
				try {
					graphics.drawImage(frame, info.left, info.top, null);
				} finally {
					graphics.dispose();
				}
				frames.add(snapshot(canvas));
				delays[index] = info.delayMs;

				if ("restoreToBackgroundColor".equals(info.disposal)) {
					clear(canvas, info.left, info.top, frame.getWidth(), frame.getHeight());
				} else if ("restoreToPrevious".equals(info.disposal) && backup != null) {
					Graphics2D restore = canvas.createGraphics();
					try {
						restore.drawImage(backup, 0, 0, null);
					} finally {
						restore.dispose();
					}
				} else {
					backup = null;
				}
			}
			return scale(new Animation(canvasWidth, canvasHeight, frames, delays));
		} finally {
			reader.dispose();
		}
	}

	private static Animation scale(Animation source) {
		int width = source.width();
		int height = source.height();
		if (width <= MAX_EDGE && height <= MAX_EDGE) {
			return source;
		}
		float scale = (float) MAX_EDGE / Math.max(width, height);
		int targetWidth = Math.max(1, Math.round(width * scale));
		int targetHeight = Math.max(1, Math.round(height * scale));
		List<int[]> scaled = new ArrayList<>(source.frames().size());
		for (int[] frame : source.frames()) {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, width, height, frame, 0, width);
			BufferedImage out = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = out.createGraphics();
			try {
				graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
			} finally {
				graphics.dispose();
			}
			int[] argb = new int[targetWidth * targetHeight];
			out.getRGB(0, 0, targetWidth, targetHeight, argb, 0, targetWidth);
			scaled.add(argb);
		}
		return new Animation(targetWidth, targetHeight, scaled, source.delaysMs());
	}

	private static int[] snapshot(BufferedImage canvas) {
		int[] argb = new int[canvas.getWidth() * canvas.getHeight()];
		canvas.getRGB(0, 0, canvas.getWidth(), canvas.getHeight(), argb, 0, canvas.getWidth());
		return argb;
	}

	private static BufferedImage copy(BufferedImage source) {
		BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = copy.createGraphics();
		try {
			graphics.drawImage(source, 0, 0, null);
		} finally {
			graphics.dispose();
		}
		return copy;
	}

	private static void clear(BufferedImage canvas, int x, int y, int width, int height) {
		int maxX = Math.min(canvas.getWidth(), x + width);
		int maxY = Math.min(canvas.getHeight(), y + height);
		for (int py = Math.max(0, y); py < maxY; py++) {
			for (int px = Math.max(0, x); px < maxX; px++) {
				canvas.setRGB(px, py, 0xFF000000);
			}
		}
	}

	private static int @Nullable [] logicalScreen(IIOMetadata stream) {
		try {
			IIOMetadataNode root = (IIOMetadataNode) stream.getAsTree("javax_imageio_gif_stream_1.0");
			NodeList screens = root.getElementsByTagName("LogicalScreenDescriptor");
			if (screens.getLength() == 0) {
				return null;
			}
			IIOMetadataNode screen = (IIOMetadataNode) screens.item(0);
			return new int[]{
				Math.max(1, Integer.parseInt(screen.getAttribute("logicalScreenWidth"))),
				Math.max(1, Integer.parseInt(screen.getAttribute("logicalScreenHeight")))
			};
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static GifFrameInfo frameInfo(IIOMetadata metadata) {
		int delayMs = 100;
		int left = 0;
		int top = 0;
		String disposal = "none";
		try {
			IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
			NodeList controls = root.getElementsByTagName("GraphicControlExtension");
			if (controls.getLength() > 0) {
				IIOMetadataNode control = (IIOMetadataNode) controls.item(0);
				int hundredths = parseInt(control.getAttribute("delayTime"), 10);
				delayMs = hundredths <= 1 ? 100 : hundredths * 10;
				String method = control.getAttribute("disposalMethod");
				if (method != null && !method.isBlank()) {
					disposal = method;
				}
			}
			NodeList descriptors = root.getElementsByTagName("ImageDescriptor");
			if (descriptors.getLength() > 0) {
				IIOMetadataNode descriptor = (IIOMetadataNode) descriptors.item(0);
				left = parseInt(descriptor.getAttribute("imageLeftPosition"), 0);
				top = parseInt(descriptor.getAttribute("imageTopPosition"), 0);
			}
		} catch (RuntimeException ignored) {
		}
		return new GifFrameInfo(left, top, delayMs, disposal);
	}

	private static int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private record GifFrameInfo(int left, int top, int delayMs, String disposal) {
	}
}
