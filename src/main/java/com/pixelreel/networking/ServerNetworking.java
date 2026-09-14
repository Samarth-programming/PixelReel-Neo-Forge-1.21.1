package com.pixelreel.networking;

import com.pixelreel.PixelReel;
import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.blockentities.WallPosterBlockEntity;
import com.pixelreel.blocks.WallPosterBlock;
import com.pixelreel.channels.Channel;
import com.pixelreel.channels.ChannelEntry;
import com.pixelreel.channels.ChannelService;
import com.pixelreel.channels.LiveStatus;
import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import com.pixelreel.emby.EmbyService;
import com.pixelreel.jellyfin.JellyfinItemKind;
import com.pixelreel.jellyfin.JellyfinItemSummary;
import com.pixelreel.jellyfin.JellyfinLibrary;
import com.pixelreel.jellyfin.JellyfinService;
import com.pixelreel.jellyfin.JellyfinStatus;
import com.pixelreel.media.MediaProxy;
import com.pixelreel.ondemand.OnDemandCatalog;
import com.pixelreel.ondemand.OnDemandProvider;
import com.pixelreel.permissions.CinemaPermissions;
import com.pixelreel.plex.PlexService;
import com.pixelreel.poster.PosterArt;
import com.pixelreel.poster.PosterImages;
import com.pixelreel.poster.WatchingDisplays;
import com.pixelreel.poster.CustomPosterStore;
import com.pixelreel.poster.CustomPosterUploads;
import com.pixelreel.server.ScreenControllerLogic;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;

public final class ServerNetworking {
	private static final double MAX_POSTER_DISTANCE_SQR =
		ScreenControllerLogic.MAX_CONTROL_DISTANCE * ScreenControllerLogic.MAX_CONTROL_DISTANCE;
	private static final long MIN_REPORTED_DURATION_MS = 5_000L;
	private static final long MAX_REPORTED_DURATION_MS = 24L * 60L * 60L * 1000L;
	/** Below this a "finished" claim is never believable, however long the client says the media is. */
	private static final long MIN_ELAPSED_BEFORE_END_MS = 30_000L;

	private ServerNetworking() {
	}

	public static void register(PayloadRegistrar registrar) {
		registrar.playToServer(
			ModNetworkPayloads.ScreenControl.TYPE,
			ModNetworkPayloads.ScreenControl.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleControl(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.ScreenTune.TYPE,
			ModNetworkPayloads.ScreenTune.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleTune(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RequestChannels.TYPE,
			ModNetworkPayloads.RequestChannels.CODEC,
			(payload, context) -> context.enqueueWork(() -> sendChannelList((ServerPlayer) context.player(), payload.forceRefresh()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RequestMediaFeatures.TYPE,
			ModNetworkPayloads.RequestMediaFeatures.CODEC,
			(payload, context) -> context.enqueueWork(() -> sendMediaFeatures((ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RequestJellyfinBrowse.TYPE,
			ModNetworkPayloads.RequestJellyfinBrowse.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleBrowse(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RequestJellyfinChildren.TYPE,
			ModNetworkPayloads.RequestJellyfinChildren.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleChildren(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.ScreenPlayJellyfin.TYPE,
			ModNetworkPayloads.ScreenPlayJellyfin.CODEC,
			(payload, context) -> context.enqueueWork(() -> handlePlayJellyfin(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.ReportMediaEnded.TYPE,
			ModNetworkPayloads.ReportMediaEnded.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleMediaEnded(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RequestJellyfinConfig.TYPE,
			ModNetworkPayloads.RequestJellyfinConfig.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleRequestJellyfinConfig((ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.UpdateJellyfinConfig.TYPE,
			ModNetworkPayloads.UpdateJellyfinConfig.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleUpdateJellyfinConfig(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RequestEmbyConfig.TYPE,
			ModNetworkPayloads.RequestEmbyConfig.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleRequestEmbyConfig((ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.UpdateEmbyConfig.TYPE,
			ModNetworkPayloads.UpdateEmbyConfig.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleUpdateEmbyConfig(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RequestPlexConfig.TYPE,
			ModNetworkPayloads.RequestPlexConfig.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleRequestPlexConfig((ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.UpdatePlexConfig.TYPE,
			ModNetworkPayloads.UpdatePlexConfig.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleUpdatePlexConfig(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RefreshJellyfinLibrary.TYPE,
			ModNetworkPayloads.RefreshJellyfinLibrary.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleRefreshLibrary((ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RequestTunarrConfig.TYPE,
			ModNetworkPayloads.RequestTunarrConfig.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleRequestTunarrConfig((ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.UpdateTunarrConfig.TYPE,
			ModNetworkPayloads.UpdateTunarrConfig.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleUpdateTunarrConfig(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.SetPosterArtwork.TYPE,
			ModNetworkPayloads.SetPosterArtwork.CODEC,
			(payload, context) -> context.enqueueWork(() -> handleSetPosterArtwork(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.UploadPosterChunk.TYPE,
			ModNetworkPayloads.UploadPosterChunk.CODEC,
			(payload, context) -> context.enqueueWork(() -> CustomPosterUploads.handleChunk(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RequestCustomPoster.TYPE,
			ModNetworkPayloads.RequestCustomPoster.CODEC,
			(payload, context) -> context.enqueueWork(() -> CustomPosterUploads.sendTo((ServerPlayer) context.player(), payload.id()))
		);
		registrar.playToServer(
			ModNetworkPayloads.RequestPlaybackSecrets.TYPE,
			ModNetworkPayloads.RequestPlaybackSecrets.CODEC,
			(payload, context) -> context.enqueueWork(() -> PlaybackSecrets.handleRequest(payload, (ServerPlayer) context.player()))
		);
		registrar.playToServer(
			ModNetworkPayloads.UnequipPixelGlasses.TYPE,
			ModNetworkPayloads.UnequipPixelGlasses.CODEC,
			(payload, context) -> context.enqueueWork(() ->
				com.pixelreel.items.PixelGlassesItem.tryUnequip((ServerPlayer) context.player())
			)
		);
	}

	private static void handleControl(ModNetworkPayloads.ScreenControl payload, ServerPlayer player) {
		DisplayBlockEntity display = ScreenControllerLogic.resolve(player, payload.pos());
		if (display == null) {
			PixelReel.LOGGER.debug("Rejected screen control from {} at {}: no reachable display", player.getName().getString(), payload.pos().toShortString());
			return;
		}
		if (!CinemaPermissions.canPerform(player, payload.action())) {
			notify(player, payload.pos(), ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		if (payload.action() == ScreenAction.REPORT_DURATION && !acceptDurationReport(display, payload.value())) {
			return;
		}
		ScreenControllerLogic.Outcome outcome = ScreenControllerLogic.apply(
			display, payload.action(), payload.value(), player.getGameProfile().getName()
		);
		notify(player, payload.pos(), outcome);
	}

	/**
	 * A runtime is a client measurement, so the first plausible report for an epoch wins and later ones are
	 * dropped. Otherwise a player could shrink the duration and then claim the film had finished.
	 */
	private static boolean acceptDurationReport(DisplayBlockEntity display, float valueMs) {
		if (display.getPlaybackDurationMs() > 0L) {
			return false;
		}
		long duration = (long) valueMs;
		return duration >= MIN_REPORTED_DURATION_MS && duration <= MAX_REPORTED_DURATION_MS;
	}

	private static void handleTune(ModNetworkPayloads.ScreenTune payload, ServerPlayer player) {
		if (!CinemaPermissions.canPlayTunarr(player)) {
			notify(player, payload.pos(), ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		DisplayBlockEntity display = ScreenControllerLogic.resolve(player, payload.pos());
		if (display == null) {
			PixelReel.LOGGER.warn("Rejected channel change from {} at {}: no reachable display", player.getGameProfile().getName(), payload.pos().toShortString());
			return;
		}
		ScreenControllerLogic.Outcome outcome = ScreenControllerLogic.tune(display, payload.channelId());
		PixelReel.LOGGER.info(
			"Channel tune from {} at {} -> {} ({})",
			player.getGameProfile().getName(),
			payload.pos().toShortString(),
			payload.channelId(),
			outcome
		);
		notify(player, payload.pos(), outcome);
	}

	private static void handleBrowse(ModNetworkPayloads.RequestJellyfinBrowse payload, ServerPlayer player) {
		if (!CinemaPermissions.canBrowse(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		OnDemandProvider provider = payload.provider();
		boolean movies = payload.kind() == ModNetworkPayloads.BrowseKind.MOVIES;
		if (movies && !CinemaPermissions.canPlayOnDemandMovies(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		if (!movies && !CinemaPermissions.canPlayOnDemandShows(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		if (!OnDemandCatalog.isConfigured(provider)) {
			PacketDistributor.sendToPlayer(
				player,
				new ModNetworkPayloads.JellyfinBrowseResult(
					provider,
					payload.kind(),
					payload.search(),
					payload.page(),
					0,
					OnDemandCatalog.lastStatus(provider),
					List.of()
				)
			);
			return;
		}
		OnDemandCatalog.refresh(provider, payload.forceRefresh()).whenComplete((status, error) -> runOnServer(player, () -> {
			OnDemandCatalog.Page page = OnDemandCatalog.page(provider, payload.kind(), payload.search(), payload.page());
			List<JellyfinItemSummary> slim = page.items().stream()
				.map(JellyfinItemSummary::forBrowsePacket)
				.map(MediaProxy::forClientItem)
				.toList();
			PacketDistributor.sendToPlayer(
				player,
				new ModNetworkPayloads.JellyfinBrowseResult(
					provider,
					payload.kind(),
					payload.search(),
					page.page(),
					page.totalCount(),
					OnDemandCatalog.lastStatus(provider),
					slim
				)
			);
		}));
	}

	private static void handleChildren(ModNetworkPayloads.RequestJellyfinChildren payload, ServerPlayer player) {
		if (!CinemaPermissions.canBrowse(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		boolean canShows = CinemaPermissions.canPlayOnDemandShows(player);
		boolean canMovies = CinemaPermissions.canPlayOnDemandMovies(player);
		// Seasons/episodes need shows; ITEM refetch is used for movie detail descriptions too.
		if (payload.kind() == ModNetworkPayloads.ChildrenKind.ITEM) {
			if (!canShows && !canMovies) {
				notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
				return;
			}
		} else if (!canShows) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		OnDemandProvider provider = payload.provider();
		if (!OnDemandCatalog.isConfigured(provider)) {
			sendChildren(player, payload, List.of());
			return;
		}
		switch (payload.kind()) {
			case SEASONS -> OnDemandCatalog.seasons(provider, payload.parentId(), payload.forceRefresh())
				.whenComplete((items, error) -> runOnServer(player, () -> sendChildren(player, payload, items)));
			case EPISODES -> OnDemandCatalog.episodes(provider, payload.parentId(), payload.forceRefresh())
				.whenComplete((items, error) -> runOnServer(player, () -> sendChildren(player, payload, items)));
			case ITEM -> OnDemandCatalog.fetchItem(provider, payload.parentId())
				.whenComplete((item, error) -> runOnServer(player, () -> {
					List<JellyfinItemSummary> items = item.isPresent() ? List.of(item.get()) : List.of();
					sendChildren(player, payload, items);
				}));
		}
	}

	private static void sendChildren(
		ServerPlayer player,
		ModNetworkPayloads.RequestJellyfinChildren payload,
		List<JellyfinItemSummary> items
	) {
		if (player.hasDisconnected()) {
			return;
		}
		PacketDistributor.sendToPlayer(
			player,
			new ModNetworkPayloads.JellyfinChildrenResult(
				payload.provider(),
				payload.kind(),
				payload.parentId(),
				OnDemandCatalog.lastStatus(payload.provider()),
				items == null ? List.of() : items.stream().map(MediaProxy::forClientItem).toList()
			)
		);
	}

	private static void handlePlayJellyfin(ModNetworkPayloads.ScreenPlayJellyfin payload, ServerPlayer player) {
		DisplayBlockEntity display = ScreenControllerLogic.resolve(player, payload.pos());
		if (display == null) {
			notify(player, payload.pos(), ScreenControllerLogic.Outcome.NO_SCREEN);
			return;
		}
		if (!CinemaPermissions.canChangeContent(player)) {
			notify(player, payload.pos(), ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		OnDemandProvider provider = payload.provider();
		if (!OnDemandCatalog.isConfigured(provider)) {
			notify(player, payload.pos(), ScreenControllerLogic.Outcome.JELLYFIN_UNAVAILABLE);
			return;
		}
		OnDemandCatalog.fetchItem(provider, payload.itemId()).whenComplete((itemOpt, error) -> runOnServer(player, () -> {
			if (itemOpt.isEmpty()) {
				notify(player, payload.pos(), ScreenControllerLogic.Outcome.ITEM_UNAVAILABLE);
				return;
			}
			JellyfinItemSummary item = itemOpt.get();
			if (item.kind() == JellyfinItemKind.MOVIE && !CinemaPermissions.canPlayOnDemandMovies(player)) {
				notify(player, payload.pos(), ScreenControllerLogic.Outcome.NO_PERMISSION);
				return;
			}
			if ((item.kind() == JellyfinItemKind.EPISODE || item.kind() == JellyfinItemKind.SERIES || item.kind() == JellyfinItemKind.SEASON)
				&& !CinemaPermissions.canPlayOnDemandShows(player)) {
				notify(player, payload.pos(), ScreenControllerLogic.Outcome.NO_PERMISSION);
				return;
			}
			if (item.kind() != JellyfinItemKind.MOVIE && item.kind() != JellyfinItemKind.EPISODE) {
				notify(player, payload.pos(), ScreenControllerLogic.Outcome.ITEM_UNAVAILABLE);
				return;
			}
			ScreenControllerLogic.playOnDemandItem(
				display, provider, item.id(), payload.startPositionMs(), player.getGameProfile().getName()
			);
		}));
	}

	private static void handleSetPosterArtwork(ModNetworkPayloads.SetPosterArtwork payload, ServerPlayer player) {
		WallPosterBlockEntity poster = resolvePoster(player, payload.pos());
		if (poster == null) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(payload.pos(), "message.pixelreel.poster.gone"));
			return;
		}
		if (!CinemaPermissions.canPlacePosters(player)) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(payload.pos(), "message.pixelreel.poster.no_permission"));
			return;
		}
		PosterArt art = payload.art();
		if (art.isEmpty()) {
			poster.setArt(PosterArt.EMPTY);
			return;
		}
		if (art.isWatching()) {
			BlockPos target = art.watchingDisplayPos();
			DisplayBlockEntity display = WatchingDisplays.at(player.level(), target);
			long rangeSqr = (long) WatchingDisplays.RANGE * WatchingDisplays.RANGE;
			if (display == null || display.getBlockPos().distSqr(poster.getBlockPos()) > rangeSqr) {
				display = WatchingDisplays.nearestPlaying(player.level(), poster.getBlockPos());
				if (display == null) {
					List<DisplayBlockEntity> nearby = WatchingDisplays.nearby(player.level(), poster.getBlockPos());
					display = nearby.isEmpty() ? null : nearby.getFirst();
				}
			}
			poster.setArt(PosterArt.watching(display == null ? null : display.getBlockPos()));
			return;
		}
		if (art.isUpload()) {
			poster.setArt(CustomPosterStore.load(art.uploadId()) != null ? PosterArt.uploaded(art.uploadId(), art.title()) : PosterArt.EMPTY);
			return;
		}
		if (art.isLocal()) {
			// Every client resolves this name inside its own poster folder, so keep it to a plain file name.
			poster.setArt(isPlainFileName(art.localFileName()) ? art : PosterArt.EMPTY);
			return;
		}
		if (art.itemId().isEmpty()) {
			acceptCustomPosterUrl(poster, player, art);
			return;
		}
		// Re-resolve the artwork URL from the catalog so the stored link is server-authoritative, keeping the
		// client-supplied one only as a fallback when the item can no longer be looked up.
		OnDemandCatalog.fetchItem(art.provider(), art.itemId()).whenComplete((item, error) -> runOnServer(player, () -> {
			if (poster.isRemoved()) {
				return;
			}
			String resolved = item == null
				? art.imageUrl()
				: item.map(JellyfinItemSummary::imageUrl).filter(url -> !url.isBlank()).orElse(art.imageUrl());
			poster.setArt(art.withImageUrl(resolved));
		}));
	}

	/**
	 * A hung URL is fetched by every client that can see the poster, so it is checked for shape, against the
	 * admin allowlist, and finally by resolving the host off-thread to make sure it is publicly routable.
	 */
	private static void acceptCustomPosterUrl(WallPosterBlockEntity poster, ServerPlayer player, PosterArt art) {
		String url = art.imageUrl();
		if (!CinemaPermissions.canUseCustomPosterUrls(player)) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(poster.getBlockPos(), "message.pixelreel.poster.no_permission"));
			return;
		}
		if (!PosterImages.isSafeHttpUrl(url)
			|| !PosterImages.isAllowedByHostPolicy(url, ConfigManager.get().posterUrlHostAllowlist)) {
			PixelReel.LOGGER.debug("Rejected poster artwork from {}: address not permitted", player.getName().getString());
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(poster.getBlockPos(), "message.pixelreel.poster.unsafe_url"));
			return;
		}
		BlockPos pos = poster.getBlockPos().immutable();
		CompletableFuture.supplyAsync(() -> PosterImages.resolvesToPublicHost(url)).whenComplete((allowed, error) -> runOnServer(player, () -> {
			if (poster.isRemoved()) {
				return;
			}
			if (allowed == null || !allowed) {
				PixelReel.LOGGER.debug("Rejected poster artwork from {}: host resolves to a private address", player.getName().getString());
				PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(pos, "message.pixelreel.poster.unsafe_url"));
				return;
			}
			poster.setArt(PosterArt.fromUrl(url, art.title()));
		}));
	}

	private static boolean isPlainFileName(String name) {
		return !name.isEmpty()
			&& name.indexOf('/') < 0
			&& name.indexOf('\\') < 0
			&& name.indexOf(':') < 0
			&& !name.contains("..");
	}

	private static @Nullable WallPosterBlockEntity resolvePoster(ServerPlayer player, BlockPos pos) {
		if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(pos)) {
			return null;
		}
		if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_POSTER_DISTANCE_SQR) {
			return null;
		}
		return WallPosterBlock.posterAt(level, pos);
	}

	private static void handleMediaEnded(ModNetworkPayloads.ReportMediaEnded payload, ServerPlayer player) {
		DisplayBlockEntity display = ScreenControllerLogic.resolve(player, payload.pos());
		if (display == null || display.getChannelEpoch() != payload.channelEpoch()) {
			return;
		}
		if (display.isPlaybackPaused() || !CinemaPermissions.canWatch(player)) {
			return;
		}
		if (!isBelievableEnd(display)) {
			PixelReel.LOGGER.debug(
				"Ignoring media-ended from {} at {} — display is at {}ms / {}ms",
				player.getGameProfile().getName(),
				payload.pos().toShortString(),
				display.currentPlaybackPositionMs(),
				display.getPlaybackDurationMs()
			);
			return;
		}
		ScreenControllerLogic.onMediaEnded(display);
	}

	/**
	 * With a known runtime the authoritative clock decides. With an unknown runtime the claim is only taken
	 * once the media has genuinely been rolling for a while, so a player who walks up cannot skip a film.
	 */
	private static boolean isBelievableEnd(DisplayBlockEntity display) {
		if (display.getPlaybackDurationMs() > 0L) {
			return display.isNearPlaybackEnd();
		}
		return display.currentPlaybackPositionMs() >= MIN_ELAPSED_BEFORE_END_MS;
	}

	private static void handleRequestJellyfinConfig(ServerPlayer player) {
		if (!CinemaPermissions.canConfigureJellyfin(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		OnDemandCatalog.discoverLibraries(OnDemandProvider.JELLYFIN)
			.whenComplete((libraries, error) -> runOnServer(player, () -> sendJellyfinConfig(player, libraries)));
	}

	private static void handleUpdateJellyfinConfig(ModNetworkPayloads.UpdateJellyfinConfig payload, ServerPlayer player) {
		if (!CinemaPermissions.canConfigureJellyfin(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		ConfigManager.update(config -> {
			config.jellyfinUrl = payload.url();
			if (payload.apiKey() != null && !payload.apiKey().isBlank()) {
				config.jellyfinApiKey = payload.apiKey();
			}
			config.jellyfinUserId = payload.userId() == null ? "" : payload.userId();
			config.jellyfinMoviesEnabled = payload.moviesEnabled();
			config.jellyfinTvShowsEnabled = payload.tvShowsEnabled();
			config.jellyfinAutoplayNextEpisode = payload.autoplayNextEpisode();
			config.onDemandAutoplayNextEpisode = payload.autoplayNextEpisode();
			config.jellyfinLibraryIds = new ArrayList<>(payload.libraryIds());
		});
		JellyfinService.INSTANCE.invalidateCache();
		JellyfinService.INSTANCE.refresh(true).whenComplete((status, error) -> runOnServer(player, () -> {
			sendMediaFeatures(player);
			OnDemandCatalog.discoverLibraries(OnDemandProvider.JELLYFIN).whenComplete((libraries, ignored) ->
				runOnServer(player, () -> sendJellyfinConfig(player, libraries))
			);
			String key = status != null && status.authenticated()
				? "message.pixelreel.jellyfin.config_saved"
				: "message.pixelreel.jellyfin.config_failed";
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(BlockPos.ZERO, key));
		}));
	}

	private static void handleRequestEmbyConfig(ServerPlayer player) {
		if (!CinemaPermissions.canConfigureEmby(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		OnDemandCatalog.discoverLibraries(OnDemandProvider.EMBY)
			.whenComplete((libraries, error) -> runOnServer(player, () -> sendEmbyConfig(player, libraries)));
	}

	private static void handleUpdateEmbyConfig(ModNetworkPayloads.UpdateEmbyConfig payload, ServerPlayer player) {
		if (!CinemaPermissions.canConfigureEmby(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		ConfigManager.update(config -> {
			config.embyUrl = payload.url();
			if (payload.apiKey() != null && !payload.apiKey().isBlank()) {
				config.embyApiKey = payload.apiKey();
			}
			config.embyUserId = payload.userId() == null ? "" : payload.userId();
			config.embyMoviesEnabled = payload.moviesEnabled();
			config.embyTvShowsEnabled = payload.tvShowsEnabled();
			config.embyLibraryIds = new ArrayList<>(payload.libraryIds());
		});
		EmbyService.INSTANCE.invalidateCache();
		EmbyService.INSTANCE.refresh(true).whenComplete((status, error) -> runOnServer(player, () -> {
			sendMediaFeatures(player);
			OnDemandCatalog.discoverLibraries(OnDemandProvider.EMBY).whenComplete((libraries, ignored) ->
				runOnServer(player, () -> sendEmbyConfig(player, libraries))
			);
			String key = status != null && status.authenticated()
				? "message.pixelreel.emby.config_saved"
				: "message.pixelreel.emby.config_failed";
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(BlockPos.ZERO, key));
		}));
	}

	private static void handleRequestPlexConfig(ServerPlayer player) {
		if (!CinemaPermissions.canConfigurePlex(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		OnDemandCatalog.discoverLibraries(OnDemandProvider.PLEX)
			.whenComplete((libraries, error) -> runOnServer(player, () -> sendPlexConfig(player, libraries)));
	}

	private static void handleUpdatePlexConfig(ModNetworkPayloads.UpdatePlexConfig payload, ServerPlayer player) {
		if (!CinemaPermissions.canConfigurePlex(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		ConfigManager.update(config -> {
			config.plexUrl = payload.url();
			if (payload.token() != null && !payload.token().isBlank()) {
				config.plexToken = payload.token();
			}
			config.plexMoviesEnabled = payload.moviesEnabled();
			config.plexTvShowsEnabled = payload.tvShowsEnabled();
			config.plexLibraryKeys = new ArrayList<>(payload.libraryKeys());
		});
		PlexService.INSTANCE.invalidateCache();
		PlexService.INSTANCE.refresh(true).whenComplete((status, error) -> runOnServer(player, () -> {
			sendMediaFeatures(player);
			OnDemandCatalog.discoverLibraries(OnDemandProvider.PLEX).whenComplete((libraries, ignored) ->
				runOnServer(player, () -> sendPlexConfig(player, libraries))
			);
			String key = status != null && status.authenticated()
				? "message.pixelreel.plex.config_saved"
				: "message.pixelreel.plex.config_failed";
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(BlockPos.ZERO, key));
		}));
	}

	private static void handleRefreshLibrary(ServerPlayer player) {
		if (!CinemaPermissions.canRefreshLibrary(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		PixelReelConfig config = ConfigManager.get();
		List<CompletableFuture<JellyfinStatus>> futures = new ArrayList<>();
		if (config.isJellyfinConfigured()) {
			futures.add(JellyfinService.INSTANCE.refresh(true));
		}
		if (config.isEmbyConfigured()) {
			futures.add(EmbyService.INSTANCE.refresh(true));
		}
		if (config.isPlexConfigured()) {
			futures.add(PlexService.INSTANCE.refresh(true));
		}
		if (futures.isEmpty()) {
			sendMediaFeatures(player);
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(BlockPos.ZERO, "message.pixelreel.ondemand.refresh_failed"));
			return;
		}
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
			.whenComplete((ignored, error) -> runOnServer(player, () -> {
				sendMediaFeatures(player);
				boolean ok = futures.stream().map(f -> {
					try {
						return f.getNow(null);
					} catch (Exception e) {
						return null;
					}
				}).anyMatch(status -> status != null && status.authenticated());
				String key = ok
					? "message.pixelreel.ondemand.refresh_done"
					: "message.pixelreel.ondemand.refresh_failed";
				PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(BlockPos.ZERO, key));
			}));
	}

	private static void sendJellyfinConfig(ServerPlayer player, List<JellyfinLibrary> libraries) {
		if (player.hasDisconnected()) {
			return;
		}
		PixelReelConfig.JellyfinPublicConfig pub = ConfigManager.get().jellyfinPublicConfig();
		PacketDistributor.sendToPlayer(
			player,
			new ModNetworkPayloads.JellyfinConfigData(
				pub.url(),
				pub.userId(),
				pub.moviesEnabled(),
				pub.tvShowsEnabled(),
				pub.autoplayNextEpisode(),
				pub.hasApiKey(),
				pub.libraryIds(),
				libraries == null ? List.of() : libraries,
				JellyfinService.INSTANCE.lastStatus()
			)
		);
	}

	private static void sendEmbyConfig(ServerPlayer player, List<JellyfinLibrary> libraries) {
		if (player.hasDisconnected()) {
			return;
		}
		PixelReelConfig.EmbyPublicConfig pub = ConfigManager.get().embyPublicConfig();
		PacketDistributor.sendToPlayer(
			player,
			new ModNetworkPayloads.EmbyConfigData(
				pub.url(),
				pub.userId(),
				pub.moviesEnabled(),
				pub.tvShowsEnabled(),
				pub.hasApiKey(),
				pub.libraryIds(),
				libraries == null ? List.of() : libraries,
				EmbyService.INSTANCE.lastStatus()
			)
		);
	}

	private static void sendPlexConfig(ServerPlayer player, List<JellyfinLibrary> libraries) {
		if (player.hasDisconnected()) {
			return;
		}
		PixelReelConfig.PlexPublicConfig pub = ConfigManager.get().plexPublicConfig();
		PacketDistributor.sendToPlayer(
			player,
			new ModNetworkPayloads.PlexConfigData(
				pub.url(),
				pub.moviesEnabled(),
				pub.tvShowsEnabled(),
				pub.hasToken(),
				pub.libraryKeys(),
				libraries == null ? List.of() : libraries,
				PlexService.INSTANCE.lastStatus()
			)
		);
	}

	private static void handleRequestTunarrConfig(ServerPlayer player) {
		if (!CinemaPermissions.canConfigureTunarr(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		sendTunarrConfig(player);
	}

	private static void handleUpdateTunarrConfig(ModNetworkPayloads.UpdateTunarrConfig payload, ServerPlayer player) {
		if (!CinemaPermissions.canConfigureTunarr(player)) {
			notify(player, BlockPos.ZERO, ScreenControllerLogic.Outcome.NO_PERMISSION);
			return;
		}
		PixelReelConfig.TunarrPublicConfig expanded = PixelReelConfig.expandTunarrUrls(payload.m3uUrl(), payload.xmltvUrl());
		if (expanded.m3uUrl().isEmpty()) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(BlockPos.ZERO, "message.pixelreel.tunarr.invalid_url"));
			sendTunarrConfig(player);
			return;
		}
		ConfigManager.update(config -> {
			config.m3uUrl = expanded.m3uUrl();
			config.xmltvUrl = expanded.xmltvUrl();
		});
		ChannelService.INSTANCE.invalidateCache();
		ChannelService.INSTANCE.channels(true).whenComplete((channels, error) -> runOnServer(player, () -> {
			sendMediaFeatures(player);
			sendTunarrConfig(player);
			sendChannelListNow(player);
			LiveStatus status = ChannelService.INSTANCE.lastStatus();
			String key = status.reachable() && status.channelCount() > 0
				? "message.pixelreel.tunarr.config_saved"
				: "message.pixelreel.tunarr.config_failed";
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(BlockPos.ZERO, key));
		}));
	}

	private static void sendTunarrConfig(ServerPlayer player) {
		if (player.hasDisconnected()) {
			return;
		}
		PixelReelConfig.TunarrPublicConfig pub = ConfigManager.get().tunarrPublicConfig();
		PacketDistributor.sendToPlayer(
			player,
			new ModNetworkPayloads.TunarrConfigData(pub.m3uUrl(), pub.xmltvUrl(), ChannelService.INSTANCE.lastStatus())
		);
	}

	public static void sendMediaFeatures(ServerPlayer player) {
		if (player.hasDisconnected()) {
			return;
		}
		PixelReelConfig.FeatureFlags flags = CinemaPermissions.featureFlags(player);
		PacketDistributor.sendToPlayer(
			player,
			ModNetworkPayloads.MediaFeatures.from(
				flags,
				JellyfinService.INSTANCE.lastStatus(),
				EmbyService.INSTANCE.lastStatus(),
				PlexService.INSTANCE.lastStatus(),
				ChannelService.INSTANCE.lastStatus()
			)
		);
	}

	public static void sendChannelList(ServerPlayer player, boolean forceRefresh) {
		ChannelService service = ChannelService.INSTANCE;
		if (!forceRefresh && service.isCacheFresh()) {
			sendChannelListNow(player);
		} else {
			service.channels(forceRefresh).whenComplete((channels, error) -> runOnServer(player, () -> sendChannelListNow(player)));
		}
	}

	public static void sendChannelListNow(ServerPlayer player) {
		if (player.hasDisconnected()) {
			return;
		}
		LiveStatus status = ChannelService.INSTANCE.lastStatus();
		List<ChannelEntry> entries = ChannelService.INSTANCE.entries();
		if (entries.size() > ModNetworkPayloads.MAX_CHANNELS) {
			entries = entries.subList(0, ModNetworkPayloads.MAX_CHANNELS);
		}
		boolean watch = CinemaPermissions.canWatch(player);
		entries = entries.stream().map(entry -> proxiedChannel(entry, watch)).toList();
		PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ChannelList(status, entries));
	}

	private static ChannelEntry proxiedChannel(ChannelEntry entry, boolean includeStream) {
		Channel channel = entry.channel();
		return new ChannelEntry(
			new Channel(
				channel.id(),
				channel.number(),
				channel.name(),
				MediaProxy.forClient(MediaProxy.Kind.IMAGE, channel.logoUrl()),
				MediaProxy.forClient(MediaProxy.Kind.IMAGE, channel.guideIconUrl()),
				includeStream ? MediaProxy.forClient(MediaProxy.Kind.STREAM, channel.streamUrl()) : ""
			),
			entry.guide()
		);
	}

	private static void runOnServer(ServerPlayer player, Runnable action) {
		MinecraftServer server = player.level().getServer();
		if (server != null) {
			server.execute(() -> {
				try {
					action.run();
				} catch (Exception e) {
					PixelReel.LOGGER.error("Failed to deliver cinema data to {}", player.getGameProfile().getName(), e);
				}
			});
		}
	}

	private static void notify(ServerPlayer player, BlockPos pos, ScreenControllerLogic.Outcome outcome) {
		String key = switch (outcome) {
			case OK -> null;
			case NO_SCREEN -> "message.pixelreel.no_screen";
			case NO_CHANNELS -> "message.pixelreel.no_channels";
			case UNKNOWN_CHANNEL -> "message.pixelreel.unknown_channel";
			case NO_PERMISSION -> "message.pixelreel.no_permission";
			case JELLYFIN_UNAVAILABLE -> "message.pixelreel.ondemand.unavailable";
			case ITEM_UNAVAILABLE -> "message.pixelreel.ondemand.item_unavailable";
			case NEXT_UNAVAILABLE -> "message.pixelreel.ondemand.next_unavailable";
			case SUBTITLE_UNAVAILABLE -> "message.pixelreel.ondemand.subtitle_unavailable";
		};
		if (key != null) {
			PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.ScreenNotice(pos, key));
		}
	}
}
