package com.pixelreel.client.playback.video;

import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.jetbrains.annotations.Nullable;

/**
 * Off-heap pixel pointer for {@link NativeImage}.
 * Uses reflection so IDE launches (without Loom access-widener apply) still work;
 * tries named, intermediary, and official field names.
 */
public final class NativeImageAccess {
	private static final String[] PIXEL_FIELD_NAMES = {
		"pixels",
		"field_4988",
		"g"
	};

	private static volatile @Nullable Field pixelsField;

	private NativeImageAccess() {
	}

	public static long pointer(NativeImage image) {
		Field field = resolvePixelsField(image);
		try {
			long address = field.getLong(image);
			if (address == 0L) {
				throw new IllegalStateException("NativeImage pixel pointer is null");
			}
			return address;
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Unable to read NativeImage pixel pointer", e);
		}
	}

	private static Field resolvePixelsField(NativeImage image) {
		Field cached = pixelsField;
		if (cached != null) {
			return cached;
		}
		synchronized (NativeImageAccess.class) {
			if (pixelsField != null) {
				return pixelsField;
			}

			for (String name : PIXEL_FIELD_NAMES) {
				try {
					Field field = NativeImage.class.getDeclaredField(name);
					if (field.getType() != long.class) {
						continue;
					}
					field.setAccessible(true);
					pixelsField = field;
					return field;
				} catch (NoSuchFieldException ignored) {
				}
			}

			// Prefer the long instance field that looks like a native pointer (not size).
			Field sizeLike = null;
			Field pointerLike = null;
			long pixelBytes = (long) image.getWidth() * image.getHeight() * 4L;
			for (Field field : NativeImage.class.getDeclaredFields()) {
				int mods = field.getModifiers();
				if (Modifier.isStatic(mods) || field.getType() != long.class) {
					continue;
				}
				field.setAccessible(true);
				try {
					long value = field.getLong(image);
					if (value == pixelBytes || value == 0L) {
						if (sizeLike == null) {
							sizeLike = field;
						}
						continue;
					}
					if (value > 0x10000L) {
						pointerLike = field;
						break;
					}
					if (sizeLike == null) {
						sizeLike = field;
					}
				} catch (IllegalAccessException ignored) {
				}
			}
			Field chosen = pointerLike != null ? pointerLike : sizeLike;
			if (chosen != null) {
				pixelsField = chosen;
				return chosen;
			}
			throw new IllegalStateException(
				"Unable to locate NativeImage pixel pointer field (tried " + String.join(", ", PIXEL_FIELD_NAMES) + ")"
			);
		}
	}
}
