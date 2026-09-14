package com.pixelreel.networking;

import com.pixelreel.PixelReel;
import com.pixelreel.channels.ChannelEntry;
import com.pixelreel.channels.LiveStatus;
import com.pixelreel.config.PixelReelConfig;
import com.pixelreel.jellyfin.JellyfinItemSummary;
import com.pixelreel.jellyfin.JellyfinLibrary;
import com.pixelreel.jellyfin.JellyfinService;
import com.pixelreel.jellyfin.JellyfinStatus;
import com.pixelreel.ondemand.OnDemandProvider;
import com.pixelreel.poster.PosterArt;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class ModNetworkPayloads {
	public static final int MAX_CHANNELS = 2048;
	public static final int MAX_JF_PAGE = JellyfinService.PAGE_SIZE;
	public static final int MAX_JF_CHILDREN = 256;
	public static final int MAX_SECRET_URL = 2048;

	private ModNetworkPayloads() {
	}

	public static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("1");
		ServerNetworking.register(registrar);
		registerClientbound(registrar);
	}

	private static void registerClientbound(PayloadRegistrar registrar) {
		registrar.playToClient(ChannelList.TYPE, ChannelList.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(ScreenNotice.TYPE, ScreenNotice.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(OpenMenu.TYPE, OpenMenu.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(RetryDisplay.TYPE, RetryDisplay.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(ShowClientStatus.TYPE, ShowClientStatus.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(MediaFeatures.TYPE, MediaFeatures.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(JellyfinBrowseResult.TYPE, JellyfinBrowseResult.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(JellyfinChildrenResult.TYPE, JellyfinChildrenResult.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(JellyfinConfigData.TYPE, JellyfinConfigData.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(EmbyConfigData.TYPE, EmbyConfigData.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(PlexConfigData.TYPE, PlexConfigData.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(TunarrConfigData.TYPE, TunarrConfigData.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(OpenPosterPicker.TYPE, OpenPosterPicker.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(CustomPosterChunk.TYPE, CustomPosterChunk.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
		registrar.playToClient(PlaybackSecretsData.TYPE, PlaybackSecretsData.CODEC, (payload, context) ->
			context.enqueueWork(() -> ClientPacketDispatch.handle(payload)));
	}

	/**
	 * Asks the server for the addresses needed to actually play a screen. Sent only by a client that is about
	 * to start a session, so the URLs never ride along with the block entity sync.
	 */
	public record RequestPlaybackSecrets(BlockPos pos, int channelEpoch) implements CustomPacketPayload {
		public static final Type<RequestPlaybackSecrets> TYPE = new Type<>(PixelReel.id("request_playback_secrets"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestPlaybackSecrets> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC,
			RequestPlaybackSecrets::pos,
			ByteBufCodecs.VAR_INT,
			RequestPlaybackSecrets::channelEpoch,
			RequestPlaybackSecrets::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Blank URLs mean the request was refused; the client should not retry until the epoch changes. */
	public record PlaybackSecretsData(
		BlockPos pos,
		int channelEpoch,
		String streamUrl,
		String subtitleUrl,
		String imageUrl,
		String plexPartKey
	) implements CustomPacketPayload {
		public static final Type<PlaybackSecretsData> TYPE = new Type<>(PixelReel.id("playback_secrets"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PlaybackSecretsData> CODEC = StreamCodec.of(
			(buf, value) -> {
				BlockPos.STREAM_CODEC.encode(buf, value.pos);
				buf.writeVarInt(value.channelEpoch);
				buf.writeUtf(value.streamUrl, MAX_SECRET_URL);
				buf.writeUtf(value.subtitleUrl, MAX_SECRET_URL);
				buf.writeUtf(value.imageUrl, MAX_SECRET_URL);
				buf.writeUtf(value.plexPartKey, MAX_SECRET_URL);
			},
			buf -> new PlaybackSecretsData(
				BlockPos.STREAM_CODEC.decode(buf),
				buf.readVarInt(),
				buf.readUtf(MAX_SECRET_URL),
				buf.readUtf(MAX_SECRET_URL),
				buf.readUtf(MAX_SECRET_URL),
				buf.readUtf(MAX_SECRET_URL)
			)
		);

		public boolean isDenied() {
			return this.streamUrl.isEmpty();
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record OpenPosterPicker(BlockPos pos) implements CustomPacketPayload {
		public static final Type<OpenPosterPicker> TYPE = new Type<>(PixelReel.id("open_poster_picker"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenPosterPicker> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, OpenPosterPicker::pos, OpenPosterPicker::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record SetPosterArtwork(BlockPos pos, PosterArt art) implements CustomPacketPayload {
		public static final Type<SetPosterArtwork> TYPE = new Type<>(PixelReel.id("set_poster_artwork"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetPosterArtwork> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC,
			SetPosterArtwork::pos,
			PosterArt.STREAM_CODEC,
			SetPosterArtwork::art,
			SetPosterArtwork::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UploadPosterChunk(
		BlockPos pos,
		String uploadId,
		String fileName,
		int totalBytes,
		int offset,
		byte[] data
	) implements CustomPacketPayload {
		public static final Type<UploadPosterChunk> TYPE = new Type<>(PixelReel.id("upload_poster_chunk"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UploadPosterChunk> CODEC = StreamCodec.of(
			(buf, value) -> {
				BlockPos.STREAM_CODEC.encode(buf, value.pos);
				buf.writeUtf(value.uploadId, 64);
				buf.writeUtf(value.fileName, 128);
				buf.writeVarInt(value.totalBytes);
				buf.writeVarInt(value.offset);
				buf.writeVarInt(value.data.length);
				buf.writeBytes(value.data);
			},
			buf -> {
				BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
				String uploadId = buf.readUtf(64);
				String fileName = buf.readUtf(128);
				int totalBytes = buf.readVarInt();
				int offset = buf.readVarInt();
				int length = buf.readVarInt();
				byte[] data = new byte[Math.clamp(length, 0, com.pixelreel.poster.CustomPosterStore.CHUNK_BYTES)];
				buf.readBytes(data);
				return new UploadPosterChunk(pos, uploadId, fileName, totalBytes, offset, data);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RequestCustomPoster(String id) implements CustomPacketPayload {
		public static final Type<RequestCustomPoster> TYPE = new Type<>(PixelReel.id("request_custom_poster"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestCustomPoster> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(64),
			RequestCustomPoster::id,
			RequestCustomPoster::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record CustomPosterChunk(
		String id,
		int totalBytes,
		int offset,
		byte[] data
	) implements CustomPacketPayload {
		public static final Type<CustomPosterChunk> TYPE = new Type<>(PixelReel.id("custom_poster_chunk"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CustomPosterChunk> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeUtf(value.id, 64);
				buf.writeVarInt(value.totalBytes);
				buf.writeVarInt(value.offset);
				buf.writeVarInt(value.data.length);
				buf.writeBytes(value.data);
			},
			buf -> {
				String id = buf.readUtf(64);
				int totalBytes = buf.readVarInt();
				int offset = buf.readVarInt();
				int length = buf.readVarInt();
				byte[] data = new byte[Math.clamp(length, 0, com.pixelreel.poster.CustomPosterStore.CHUNK_BYTES)];
				buf.readBytes(data);
				return new CustomPosterChunk(id, totalBytes, offset, data);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ChannelList(LiveStatus status, List<ChannelEntry> entries) implements CustomPacketPayload {
		public static final Type<ChannelList> TYPE = new Type<>(PixelReel.id("channel_list"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ChannelList> CODEC = StreamCodec.composite(
			LiveStatus.STREAM_CODEC,
			ChannelList::status,
			ChannelEntry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CHANNELS)),
			ChannelList::entries,
			ChannelList::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RequestChannels(boolean forceRefresh) implements CustomPacketPayload {
		public static final Type<RequestChannels> TYPE = new Type<>(PixelReel.id("request_channels"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestChannels> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, RequestChannels::forceRefresh, RequestChannels::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ScreenControl(BlockPos pos, ScreenAction action, float value) implements CustomPacketPayload {
		public static final Type<ScreenControl> TYPE = new Type<>(PixelReel.id("screen_control"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScreenControl> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC,
			ScreenControl::pos,
			ByteBufCodecs.VAR_INT.map(ScreenAction::byIndex, Enum::ordinal),
			ScreenControl::action,
			ByteBufCodecs.FLOAT,
			ScreenControl::value,
			ScreenControl::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ScreenTune(BlockPos pos, String channelId) implements CustomPacketPayload {
		public static final Type<ScreenTune> TYPE = new Type<>(PixelReel.id("screen_tune"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScreenTune> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC,
			ScreenTune::pos,
			ByteBufCodecs.stringUtf8(128),
			ScreenTune::channelId,
			ScreenTune::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ScreenNotice(BlockPos pos, String translationKey) implements CustomPacketPayload {
		public static final Type<ScreenNotice> TYPE = new Type<>(PixelReel.id("screen_notice"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScreenNotice> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC,
			ScreenNotice::pos,
			ByteBufCodecs.stringUtf8(128),
			ScreenNotice::translationKey,
			ScreenNotice::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record OpenMenu(BlockPos pos) implements CustomPacketPayload {
		public static final Type<OpenMenu> TYPE = new Type<>(PixelReel.id("open_menu"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenMenu> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, OpenMenu::pos, OpenMenu::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RetryDisplay(BlockPos pos) implements CustomPacketPayload {
		public static final Type<RetryDisplay> TYPE = new Type<>(PixelReel.id("retry_display"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RetryDisplay> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, RetryDisplay::pos, RetryDisplay::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ShowClientStatus(BlockPos pos) implements CustomPacketPayload {
		public static final Type<ShowClientStatus> TYPE = new Type<>(PixelReel.id("show_client_status"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ShowClientStatus> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ShowClientStatus::pos, ShowClientStatus::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RequestMediaFeatures() implements CustomPacketPayload {
		public static final Type<RequestMediaFeatures> TYPE = new Type<>(PixelReel.id("request_media_features"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestMediaFeatures> CODEC = StreamCodec.unit(new RequestMediaFeatures());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record MediaFeatures(
		boolean canBrowse,
		boolean canPlayTunarr,
		boolean canPlayMovies,
		boolean canPlayShows,
		boolean canControlPlayback,
		boolean canConfigureTunarr,
		boolean canConfigureJellyfin,
		boolean canConfigureEmby,
		boolean canConfigurePlex,
		boolean canRefreshLibrary,
		boolean autoplayNextEpisode,
		boolean jellyfinMovies,
		boolean jellyfinShows,
		boolean embyMovies,
		boolean embyShows,
		boolean plexMovies,
		boolean plexShows,
		JellyfinStatus jellyfinStatus,
		JellyfinStatus embyStatus,
		JellyfinStatus plexStatus,
		LiveStatus tunarrStatus
	) implements CustomPacketPayload {
		public static final Type<MediaFeatures> TYPE = new Type<>(PixelReel.id("media_features"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MediaFeatures> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeBoolean(value.canBrowse);
				buf.writeBoolean(value.canPlayTunarr);
				buf.writeBoolean(value.canPlayMovies);
				buf.writeBoolean(value.canPlayShows);
				buf.writeBoolean(value.canControlPlayback);
				buf.writeBoolean(value.canConfigureTunarr);
				buf.writeBoolean(value.canConfigureJellyfin);
				buf.writeBoolean(value.canConfigureEmby);
				buf.writeBoolean(value.canConfigurePlex);
				buf.writeBoolean(value.canRefreshLibrary);
				buf.writeBoolean(value.autoplayNextEpisode);
				buf.writeBoolean(value.jellyfinMovies);
				buf.writeBoolean(value.jellyfinShows);
				buf.writeBoolean(value.embyMovies);
				buf.writeBoolean(value.embyShows);
				buf.writeBoolean(value.plexMovies);
				buf.writeBoolean(value.plexShows);
				JellyfinStatus.STREAM_CODEC.encode(buf, value.jellyfinStatus);
				JellyfinStatus.STREAM_CODEC.encode(buf, value.embyStatus);
				JellyfinStatus.STREAM_CODEC.encode(buf, value.plexStatus);
				LiveStatus.STREAM_CODEC.encode(buf, value.tunarrStatus);
			},
			buf -> new MediaFeatures(
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readBoolean(),
				JellyfinStatus.STREAM_CODEC.decode(buf),
				JellyfinStatus.STREAM_CODEC.decode(buf),
				JellyfinStatus.STREAM_CODEC.decode(buf),
				LiveStatus.STREAM_CODEC.decode(buf)
			)
		);

		public static MediaFeatures from(
			PixelReelConfig.FeatureFlags flags,
			JellyfinStatus jellyfinStatus,
			JellyfinStatus embyStatus,
			JellyfinStatus plexStatus,
			LiveStatus tunarrStatus
		) {
			return new MediaFeatures(
				flags.canBrowse(),
				flags.canPlayTunarr(),
				flags.canPlayMovies(),
				flags.canPlayShows(),
				flags.canControlPlayback(),
				flags.canConfigureTunarr(),
				flags.canConfigureJellyfin(),
				flags.canConfigureEmby(),
				flags.canConfigurePlex(),
				flags.canRefreshLibrary(),
				flags.autoplayNextEpisode(),
				flags.jellyfinMovies(),
				flags.jellyfinShows(),
				flags.embyMovies(),
				flags.embyShows(),
				flags.plexMovies(),
				flags.plexShows(),
				jellyfinStatus,
				embyStatus,
				plexStatus,
				tunarrStatus
			);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RequestTunarrConfig() implements CustomPacketPayload {
		public static final Type<RequestTunarrConfig> TYPE = new Type<>(PixelReel.id("request_tunarr_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestTunarrConfig> CODEC = StreamCodec.unit(new RequestTunarrConfig());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UpdateTunarrConfig(String m3uUrl, String xmltvUrl) implements CustomPacketPayload {
		public static final Type<UpdateTunarrConfig> TYPE = new Type<>(PixelReel.id("update_tunarr_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UpdateTunarrConfig> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(512),
			UpdateTunarrConfig::m3uUrl,
			ByteBufCodecs.stringUtf8(512),
			UpdateTunarrConfig::xmltvUrl,
			UpdateTunarrConfig::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TunarrConfigData(String m3uUrl, String xmltvUrl, LiveStatus status) implements CustomPacketPayload {
		public static final Type<TunarrConfigData> TYPE = new Type<>(PixelReel.id("tunarr_config_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TunarrConfigData> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(512),
			TunarrConfigData::m3uUrl,
			ByteBufCodecs.stringUtf8(512),
			TunarrConfigData::xmltvUrl,
			LiveStatus.STREAM_CODEC,
			TunarrConfigData::status,
			TunarrConfigData::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public enum BrowseKind {
		MOVIES,
		SERIES;

		private static final BrowseKind[] VALUES = values();

		public static BrowseKind byIndex(int index) {
			return index >= 0 && index < VALUES.length ? VALUES[index] : MOVIES;
		}
	}

	public record RequestJellyfinBrowse(
		OnDemandProvider provider,
		BrowseKind kind,
		String search,
		int page,
		boolean forceRefresh
	) implements CustomPacketPayload {
		public static final Type<RequestJellyfinBrowse> TYPE = new Type<>(PixelReel.id("request_jf_browse"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestJellyfinBrowse> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.map(OnDemandProvider::byIndex, OnDemandProvider::ordinal),
			RequestJellyfinBrowse::provider,
			ByteBufCodecs.VAR_INT.map(BrowseKind::byIndex, Enum::ordinal),
			RequestJellyfinBrowse::kind,
			ByteBufCodecs.stringUtf8(128),
			RequestJellyfinBrowse::search,
			ByteBufCodecs.VAR_INT,
			RequestJellyfinBrowse::page,
			ByteBufCodecs.BOOL,
			RequestJellyfinBrowse::forceRefresh,
			RequestJellyfinBrowse::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record JellyfinBrowseResult(
		OnDemandProvider provider,
		BrowseKind kind,
		String search,
		int page,
		int totalCount,
		JellyfinStatus status,
		List<JellyfinItemSummary> items
	) implements CustomPacketPayload {
		public static final Type<JellyfinBrowseResult> TYPE = new Type<>(PixelReel.id("jf_browse_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, JellyfinBrowseResult> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeVarInt(value.provider.ordinal());
				buf.writeVarInt(value.kind.ordinal());
				buf.writeUtf(value.search, 128);
				buf.writeVarInt(value.page);
				buf.writeVarInt(value.totalCount);
				JellyfinStatus.STREAM_CODEC.encode(buf, value.status);
				JellyfinItemSummary.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_JF_PAGE)).encode(buf, value.items);
			},
			buf -> new JellyfinBrowseResult(
				OnDemandProvider.byIndex(buf.readVarInt()),
				BrowseKind.byIndex(buf.readVarInt()),
				buf.readUtf(128),
				buf.readVarInt(),
				buf.readVarInt(),
				JellyfinStatus.STREAM_CODEC.decode(buf),
				JellyfinItemSummary.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_JF_PAGE)).decode(buf)
			)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public enum ChildrenKind {
		SEASONS,
		EPISODES,
		ITEM;

		private static final ChildrenKind[] VALUES = values();

		public static ChildrenKind byIndex(int index) {
			return index >= 0 && index < VALUES.length ? VALUES[index] : ITEM;
		}
	}

	public record RequestJellyfinChildren(
		OnDemandProvider provider,
		ChildrenKind kind,
		String parentId,
		boolean forceRefresh
	) implements CustomPacketPayload {
		public static final Type<RequestJellyfinChildren> TYPE = new Type<>(PixelReel.id("request_jf_children"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestJellyfinChildren> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.map(OnDemandProvider::byIndex, OnDemandProvider::ordinal),
			RequestJellyfinChildren::provider,
			ByteBufCodecs.VAR_INT.map(ChildrenKind::byIndex, Enum::ordinal),
			RequestJellyfinChildren::kind,
			ByteBufCodecs.stringUtf8(128),
			RequestJellyfinChildren::parentId,
			ByteBufCodecs.BOOL,
			RequestJellyfinChildren::forceRefresh,
			RequestJellyfinChildren::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record JellyfinChildrenResult(
		OnDemandProvider provider,
		ChildrenKind kind,
		String parentId,
		JellyfinStatus status,
		List<JellyfinItemSummary> items
	) implements CustomPacketPayload {
		public static final Type<JellyfinChildrenResult> TYPE = new Type<>(PixelReel.id("jf_children_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, JellyfinChildrenResult> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.map(OnDemandProvider::byIndex, OnDemandProvider::ordinal),
			JellyfinChildrenResult::provider,
			ByteBufCodecs.VAR_INT.map(ChildrenKind::byIndex, Enum::ordinal),
			JellyfinChildrenResult::kind,
			ByteBufCodecs.stringUtf8(128),
			JellyfinChildrenResult::parentId,
			JellyfinStatus.STREAM_CODEC,
			JellyfinChildrenResult::status,
			JellyfinItemSummary.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_JF_CHILDREN)),
			JellyfinChildrenResult::items,
			JellyfinChildrenResult::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ScreenPlayJellyfin(
		OnDemandProvider provider,
		BlockPos pos,
		String itemId,
		long startPositionMs
	) implements CustomPacketPayload {
		public static final Type<ScreenPlayJellyfin> TYPE = new Type<>(PixelReel.id("screen_play_jf"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScreenPlayJellyfin> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.map(OnDemandProvider::byIndex, OnDemandProvider::ordinal),
			ScreenPlayJellyfin::provider,
			BlockPos.STREAM_CODEC,
			ScreenPlayJellyfin::pos,
			ByteBufCodecs.stringUtf8(128),
			ScreenPlayJellyfin::itemId,
			ByteBufCodecs.VAR_LONG,
			ScreenPlayJellyfin::startPositionMs,
			ScreenPlayJellyfin::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ReportMediaEnded(BlockPos pos, int channelEpoch) implements CustomPacketPayload {
		public static final Type<ReportMediaEnded> TYPE = new Type<>(PixelReel.id("report_media_ended"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ReportMediaEnded> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC,
			ReportMediaEnded::pos,
			ByteBufCodecs.VAR_INT,
			ReportMediaEnded::channelEpoch,
			ReportMediaEnded::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RequestJellyfinConfig() implements CustomPacketPayload {
		public static final Type<RequestJellyfinConfig> TYPE = new Type<>(PixelReel.id("request_jf_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestJellyfinConfig> CODEC = StreamCodec.unit(new RequestJellyfinConfig());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UpdateJellyfinConfig(
		String url,
		String apiKey,
		String userId,
		boolean moviesEnabled,
		boolean tvShowsEnabled,
		boolean autoplayNextEpisode,
		List<String> libraryIds
	) implements CustomPacketPayload {
		public static final Type<UpdateJellyfinConfig> TYPE = new Type<>(PixelReel.id("update_jf_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UpdateJellyfinConfig> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeUtf(value.url, 256);
				buf.writeUtf(value.apiKey, 512);
				buf.writeUtf(value.userId, 128);
				buf.writeBoolean(value.moviesEnabled);
				buf.writeBoolean(value.tvShowsEnabled);
				buf.writeBoolean(value.autoplayNextEpisode);
				buf.writeVarInt(value.libraryIds.size());
				for (String id : value.libraryIds) {
					buf.writeUtf(id, 128);
				}
			},
			buf -> {
				String url = buf.readUtf(256);
				String apiKey = buf.readUtf(512);
				String userId = buf.readUtf(128);
				boolean movies = buf.readBoolean();
				boolean shows = buf.readBoolean();
				boolean autoplay = buf.readBoolean();
				int count = buf.readVarInt();
				List<String> ids = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					ids.add(buf.readUtf(128));
				}
				return new UpdateJellyfinConfig(url, apiKey, userId, movies, shows, autoplay, ids);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RefreshJellyfinLibrary() implements CustomPacketPayload {
		public static final Type<RefreshJellyfinLibrary> TYPE = new Type<>(PixelReel.id("refresh_jf_library"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RefreshJellyfinLibrary> CODEC = StreamCodec.unit(new RefreshJellyfinLibrary());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record JellyfinConfigData(
		String url,
		String userId,
		boolean moviesEnabled,
		boolean tvShowsEnabled,
		boolean autoplayNextEpisode,
		boolean hasApiKey,
		List<String> selectedLibraryIds,
		List<JellyfinLibrary> availableLibraries,
		JellyfinStatus status
	) implements CustomPacketPayload {
		public static final Type<JellyfinConfigData> TYPE = new Type<>(PixelReel.id("jf_config_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, JellyfinConfigData> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeUtf(value.url, 256);
				buf.writeUtf(value.userId, 128);
				buf.writeBoolean(value.moviesEnabled);
				buf.writeBoolean(value.tvShowsEnabled);
				buf.writeBoolean(value.autoplayNextEpisode);
				buf.writeBoolean(value.hasApiKey);
				buf.writeVarInt(value.selectedLibraryIds.size());
				for (String id : value.selectedLibraryIds) {
					buf.writeUtf(id, 128);
				}
				JellyfinLibrary.STREAM_CODEC.apply(ByteBufCodecs.list(128)).encode(buf, value.availableLibraries);
				JellyfinStatus.STREAM_CODEC.encode(buf, value.status);
			},
			buf -> {
				String url = buf.readUtf(256);
				String userId = buf.readUtf(128);
				boolean movies = buf.readBoolean();
				boolean shows = buf.readBoolean();
				boolean autoplay = buf.readBoolean();
				boolean hasKey = buf.readBoolean();
				int count = buf.readVarInt();
				List<String> selected = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					selected.add(buf.readUtf(128));
				}
				List<JellyfinLibrary> libraries = JellyfinLibrary.STREAM_CODEC.apply(ByteBufCodecs.list(128)).decode(buf);
				JellyfinStatus status = JellyfinStatus.STREAM_CODEC.decode(buf);
				return new JellyfinConfigData(url, userId, movies, shows, autoplay, hasKey, selected, libraries, status);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RequestEmbyConfig() implements CustomPacketPayload {
		public static final Type<RequestEmbyConfig> TYPE = new Type<>(PixelReel.id("request_emby_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestEmbyConfig> CODEC = StreamCodec.unit(new RequestEmbyConfig());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UpdateEmbyConfig(
		String url,
		String apiKey,
		String userId,
		boolean moviesEnabled,
		boolean tvShowsEnabled,
		List<String> libraryIds
	) implements CustomPacketPayload {
		public static final Type<UpdateEmbyConfig> TYPE = new Type<>(PixelReel.id("update_emby_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UpdateEmbyConfig> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeUtf(value.url, 256);
				buf.writeUtf(value.apiKey, 512);
				buf.writeUtf(value.userId, 128);
				buf.writeBoolean(value.moviesEnabled);
				buf.writeBoolean(value.tvShowsEnabled);
				buf.writeVarInt(value.libraryIds.size());
				for (String id : value.libraryIds) {
					buf.writeUtf(id, 128);
				}
			},
			buf -> {
				String url = buf.readUtf(256);
				String apiKey = buf.readUtf(512);
				String userId = buf.readUtf(128);
				boolean movies = buf.readBoolean();
				boolean shows = buf.readBoolean();
				int count = buf.readVarInt();
				List<String> ids = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					ids.add(buf.readUtf(128));
				}
				return new UpdateEmbyConfig(url, apiKey, userId, movies, shows, ids);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record EmbyConfigData(
		String url,
		String userId,
		boolean moviesEnabled,
		boolean tvShowsEnabled,
		boolean hasApiKey,
		List<String> selectedLibraryIds,
		List<JellyfinLibrary> availableLibraries,
		JellyfinStatus status
	) implements CustomPacketPayload {
		public static final Type<EmbyConfigData> TYPE = new Type<>(PixelReel.id("emby_config_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, EmbyConfigData> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeUtf(value.url, 256);
				buf.writeUtf(value.userId, 128);
				buf.writeBoolean(value.moviesEnabled);
				buf.writeBoolean(value.tvShowsEnabled);
				buf.writeBoolean(value.hasApiKey);
				buf.writeVarInt(value.selectedLibraryIds.size());
				for (String id : value.selectedLibraryIds) {
					buf.writeUtf(id, 128);
				}
				JellyfinLibrary.STREAM_CODEC.apply(ByteBufCodecs.list(128)).encode(buf, value.availableLibraries);
				JellyfinStatus.STREAM_CODEC.encode(buf, value.status);
			},
			buf -> {
				String url = buf.readUtf(256);
				String userId = buf.readUtf(128);
				boolean movies = buf.readBoolean();
				boolean shows = buf.readBoolean();
				boolean hasKey = buf.readBoolean();
				int count = buf.readVarInt();
				List<String> selected = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					selected.add(buf.readUtf(128));
				}
				List<JellyfinLibrary> libraries = JellyfinLibrary.STREAM_CODEC.apply(ByteBufCodecs.list(128)).decode(buf);
				JellyfinStatus status = JellyfinStatus.STREAM_CODEC.decode(buf);
				return new EmbyConfigData(url, userId, movies, shows, hasKey, selected, libraries, status);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record RequestPlexConfig() implements CustomPacketPayload {
		public static final Type<RequestPlexConfig> TYPE = new Type<>(PixelReel.id("request_plex_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestPlexConfig> CODEC = StreamCodec.unit(new RequestPlexConfig());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UpdatePlexConfig(
		String url,
		String token,
		boolean moviesEnabled,
		boolean tvShowsEnabled,
		List<String> libraryKeys
	) implements CustomPacketPayload {
		public static final Type<UpdatePlexConfig> TYPE = new Type<>(PixelReel.id("update_plex_config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UpdatePlexConfig> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeUtf(value.url, 256);
				buf.writeUtf(value.token, 512);
				buf.writeBoolean(value.moviesEnabled);
				buf.writeBoolean(value.tvShowsEnabled);
				buf.writeVarInt(value.libraryKeys.size());
				for (String key : value.libraryKeys) {
					buf.writeUtf(key, 128);
				}
			},
			buf -> {
				String url = buf.readUtf(256);
				String token = buf.readUtf(512);
				boolean movies = buf.readBoolean();
				boolean shows = buf.readBoolean();
				int count = buf.readVarInt();
				List<String> keys = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					keys.add(buf.readUtf(128));
				}
				return new UpdatePlexConfig(url, token, movies, shows, keys);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UnequipPixelGlasses() implements CustomPacketPayload {
		public static final Type<UnequipPixelGlasses> TYPE = new Type<>(PixelReel.id("unequip_pixel_glasses"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UnequipPixelGlasses> CODEC =
			StreamCodec.unit(new UnequipPixelGlasses());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record PlexConfigData(
		String url,
		boolean moviesEnabled,
		boolean tvShowsEnabled,
		boolean hasToken,
		List<String> selectedLibraryKeys,
		List<JellyfinLibrary> availableLibraries,
		JellyfinStatus status
	) implements CustomPacketPayload {
		public static final Type<PlexConfigData> TYPE = new Type<>(PixelReel.id("plex_config_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PlexConfigData> CODEC = StreamCodec.of(
			(buf, value) -> {
				buf.writeUtf(value.url, 256);
				buf.writeBoolean(value.moviesEnabled);
				buf.writeBoolean(value.tvShowsEnabled);
				buf.writeBoolean(value.hasToken);
				buf.writeVarInt(value.selectedLibraryKeys.size());
				for (String key : value.selectedLibraryKeys) {
					buf.writeUtf(key, 128);
				}
				JellyfinLibrary.STREAM_CODEC.apply(ByteBufCodecs.list(128)).encode(buf, value.availableLibraries);
				JellyfinStatus.STREAM_CODEC.encode(buf, value.status);
			},
			buf -> {
				String url = buf.readUtf(256);
				boolean movies = buf.readBoolean();
				boolean shows = buf.readBoolean();
				boolean hasToken = buf.readBoolean();
				int count = buf.readVarInt();
				List<String> selected = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					selected.add(buf.readUtf(128));
				}
				List<JellyfinLibrary> libraries = JellyfinLibrary.STREAM_CODEC.apply(ByteBufCodecs.list(128)).decode(buf);
				JellyfinStatus status = JellyfinStatus.STREAM_CODEC.decode(buf);
				return new PlexConfigData(url, movies, shows, hasToken, selected, libraries, status);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
