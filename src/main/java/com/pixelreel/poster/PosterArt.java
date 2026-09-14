package com.pixelreel.poster;

import com.pixelreel.ondemand.OnDemandProvider;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

/** artwork bound to a single hung poster */
public record PosterArt(OnDemandProvider provider, String itemId, String imageUrl, String title) {
	/** Marks {@link #imageUrl()} as a file inside config/pixelreel-posters instead of a remote URL. */
	public static final String LOCAL_PREFIX = "local:";
	/** Image stored in the world save after an in-game upload (png/jpg/gif). */
	public static final String UPLOAD_PREFIX = "upload:";
	/** Follows the nearest playing screen and shows that title's artwork. */
	public static final String WATCHING_PREFIX = "watching:";

	public static final int MAX_ID = 128;
	public static final int MAX_URL = 2048;
	public static final int MAX_TITLE = 256;

	public static final PosterArt EMPTY = new PosterArt(OnDemandProvider.JELLYFIN, "", "", "");
	public static final PosterArt WATCHING = new PosterArt(
		OnDemandProvider.JELLYFIN,
		"watching",
		WATCHING_PREFIX,
		"Currently Watching"
	);

	private static final String KEY_PROVIDER = "Provider";
	private static final String KEY_ITEM_ID = "ItemId";
	private static final String KEY_IMAGE_URL = "ImageUrl";
	private static final String KEY_TITLE = "Title";

	public static final StreamCodec<RegistryFriendlyByteBuf, PosterArt> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT.map(OnDemandProvider::byIndex, OnDemandProvider::ordinal),
		PosterArt::provider,
		ByteBufCodecs.stringUtf8(MAX_ID),
		PosterArt::itemId,
		ByteBufCodecs.stringUtf8(MAX_URL),
		PosterArt::imageUrl,
		ByteBufCodecs.stringUtf8(MAX_TITLE),
		PosterArt::title,
		PosterArt::new
	);

	public PosterArt {
		provider = provider == null ? OnDemandProvider.JELLYFIN : provider;
		itemId = trim(itemId, MAX_ID);
		imageUrl = trim(imageUrl, MAX_URL);
		title = trim(title, MAX_TITLE);
	}


	public boolean isEmpty() {
		return this.itemId.isEmpty() && this.imageUrl.isEmpty();
	}

	public boolean isLocal() {
		return this.imageUrl.startsWith(LOCAL_PREFIX);
	}

	public boolean isUpload() {
		return this.imageUrl.startsWith(UPLOAD_PREFIX);
	}

	public boolean isWatching() {
		return this.imageUrl.startsWith(WATCHING_PREFIX) || "watching".equals(this.itemId);
	}

	public static PosterArt watching(@Nullable BlockPos displayPos) {
		if (displayPos == null) {
			return WATCHING;
		}
		return new PosterArt(
			OnDemandProvider.JELLYFIN,
			"watching",
			WATCHING_PREFIX + displayPos.getX() + "," + displayPos.getY() + "," + displayPos.getZ(),
			"Currently Watching"
		);
	}

	public @Nullable BlockPos watchingDisplayPos() {
		if (!this.isWatching()) {
			return null;
		}
		String rest = this.imageUrl.startsWith(WATCHING_PREFIX) ? this.imageUrl.substring(WATCHING_PREFIX.length()) : "";
		if (rest.isEmpty()) {
			return null;
		}
		String[] parts = rest.split(",");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public String localFileName() {
		return this.isLocal() ? this.imageUrl.substring(LOCAL_PREFIX.length()) : "";
	}

	public String uploadId() {
		return this.isUpload() ? this.imageUrl.substring(UPLOAD_PREFIX.length()) : "";
	}

	public static PosterArt uploaded(String id, String title) {
		return new PosterArt(OnDemandProvider.JELLYFIN, id, UPLOAD_PREFIX + id, title);
	}

	public static PosterArt fromUrl(String url, String title) {
		return new PosterArt(OnDemandProvider.JELLYFIN, "", url, title);
	}

	public boolean isRemote() {
		return this.imageUrl.regionMatches(true, 0, "http://", 0, 7)
			|| this.imageUrl.regionMatches(true, 0, "https://", 0, 8);
	}

	/**
	 * Identity used for texture caching. Two hangings of the same title share one upload, and the key stays
	 * stable when a media server hands out a different image URL for the same item.
	 */
	public String cacheKey() {
		if (this.isWatching()) {
			BlockPos pos = this.watchingDisplayPos();
			return pos == null ? WATCHING_PREFIX : WATCHING_PREFIX + pos.asLong();
		}
		if (this.isUpload()) {
			return this.imageUrl;
		}
		if (this.isLocal()) {
			return this.imageUrl;
		}
		if (!this.itemId.isEmpty()) {
			return this.provider.name().toLowerCase(Locale.ROOT) + ":" + this.itemId;
		}
		return "url:" + Integer.toHexString(this.imageUrl.hashCode());
	}

	public PosterArt withImageUrl(String url) {
		return new PosterArt(this.provider, this.itemId, url, this.title);
	}

	public void save(CompoundTag tag) {
		if (this.itemId.isEmpty() && this.imageUrl.isEmpty()) {
			return;
		}
		tag.putString(KEY_PROVIDER, this.provider.name());
		tag.putString(KEY_ITEM_ID, this.itemId);
		tag.putString(KEY_IMAGE_URL, this.imageUrl);
		tag.putString(KEY_TITLE, this.title);
	}

	public static PosterArt load(CompoundTag tag) {
		if (!tag.contains(KEY_IMAGE_URL) && !tag.contains(KEY_ITEM_ID)) {
			return EMPTY;
		}
		String itemId = tag.contains(KEY_ITEM_ID) ? tag.getString(KEY_ITEM_ID) : "";
		String url = tag.contains(KEY_IMAGE_URL) ? tag.getString(KEY_IMAGE_URL) : "";
		if (url.startsWith(WATCHING_PREFIX) || url.startsWith(LOCAL_PREFIX) || url.startsWith(UPLOAD_PREFIX) || "watching".equals(itemId)) {
			return new PosterArt(
				OnDemandProvider.byName(tag.contains(KEY_PROVIDER) ? tag.getString(KEY_PROVIDER) : ""),
				itemId,
				url,
				tag.contains(KEY_TITLE) ? tag.getString(KEY_TITLE) : ""
			);
		}
		if (!itemId.isEmpty()) {
			// Catalog hangings persist the item id, not a keyed picture URL. A proxied client URL
			// (pixelreel.invalid / grant token) is kept; anything that still looks like the media
			// server is dropped so the proxy can mint a fresh grant.
			if (com.pixelreel.media.SecretUrls.hasCredential(url) || MediaHosts.isConfiguredMediaUrl(url)) {
				url = "";
			} else {
				url = com.pixelreel.media.SecretUrls.strip(url);
			}
		} else {
			url = com.pixelreel.media.SecretUrls.strip(url);
		}
		return new PosterArt(
			OnDemandProvider.byName(tag.contains(KEY_PROVIDER) ? tag.getString(KEY_PROVIDER) : ""),
			itemId,
			url,
			tag.contains(KEY_TITLE) ? tag.getString(KEY_TITLE) : ""
		);
	}

	private static String trim(String value, int max) {
		if (value == null) {
			return "";
		}
		String stripped = value.strip();
		return stripped.length() <= max ? stripped : stripped.substring(0, max);
	}
}
