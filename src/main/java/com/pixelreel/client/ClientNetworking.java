package com.pixelreel.client;

import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.blocks.WallPosterBlock;
import com.pixelreel.channels.ChannelService;
import com.pixelreel.client.gui.MediaSourceScreen;
import com.pixelreel.client.gui.emby.EmbyConfigScreen;
import com.pixelreel.client.gui.jellyfin.JellyfinConfigScreen;
import com.pixelreel.client.gui.ondemand.BrowseResultListener;
import com.pixelreel.client.gui.ondemand.OnDemandDetailScreen;
import com.pixelreel.client.gui.ondemand.OnDemandEpisodeScreen;
import com.pixelreel.client.gui.ondemand.OnDemandSeriesScreen;
import com.pixelreel.client.gui.plex.PlexConfigScreen;
import com.pixelreel.client.gui.poster.PosterPickerScreen;
import com.pixelreel.client.gui.tunarr.ChannelMenuScreen;
import com.pixelreel.client.gui.tunarr.TunarrConfigScreen;
import com.pixelreel.client.playback.ChannelPlayer;
import com.pixelreel.client.playback.ClientPlaybackSecrets;
import com.pixelreel.client.playback.PlaybackManager;
import com.pixelreel.networking.ClientPacketDispatch;
import com.pixelreel.networking.ModNetworkPayloads;
import com.pixelreel.networking.ScreenAction;
import com.pixelreel.ondemand.OnDemandProvider;
import com.pixelreel.poster.PosterArt;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ClientNetworking {
	private static OnDemandProvider pendingBrowseProvider = OnDemandProvider.JELLYFIN;
	private static ModNetworkPayloads.BrowseKind pendingBrowseKind = ModNetworkPayloads.BrowseKind.MOVIES;
	private static String pendingBrowseSearch = "";
	private static int pendingBrowsePage;

	private ClientNetworking() {
	}

	public static void register() {
		ClientPacketDispatch.setHandler(ClientNetworking::dispatch);
	}

	public static boolean canSend(CustomPacketPayload.Type<?> type) {
		var connection = Minecraft.getInstance().getConnection();
		return connection != null && connection.hasChannel(type);
	}

	public static void send(CustomPacketPayload payload) {
		PacketDistributor.sendToServer(payload);
	}

	private static void dispatch(CustomPacketPayload payload) {
		switch (payload) {
			case ModNetworkPayloads.ChannelList list -> {
				ClientChannelCache.INSTANCE.accept(list.entries(), list.status());
				if (Minecraft.getInstance().screen instanceof ChannelMenuScreen menu) {
					menu.onChannelsUpdated();
				}
			}
			case ModNetworkPayloads.OpenMenu openMenu -> openMenu(openMenu.pos());
			case ModNetworkPayloads.ScreenNotice notice -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft.player != null) {
					minecraft.player.displayClientMessage(Component.translatable(notice.translationKey()), true);
				}
			}
			case ModNetworkPayloads.RetryDisplay retry -> PlaybackManager.INSTANCE.retry(retry.pos());
			case ModNetworkPayloads.ShowClientStatus status -> printClientStatus(status.pos());
			case ModNetworkPayloads.MediaFeatures features -> {
				ClientMediaCache.INSTANCE.acceptFeatures(features);
				var screen = Minecraft.getInstance().screen;
				if (screen instanceof MediaSourceScreen sourceScreen) {
					sourceScreen.onFeaturesUpdated();
				} else if (screen instanceof PosterPickerScreen pickerScreen) {
					pickerScreen.onFeaturesUpdated();
				}
			}
			case ModNetworkPayloads.OpenPosterPicker picker -> openPosterPicker(picker.pos());
			case ModNetworkPayloads.CustomPosterChunk chunk ->
				com.pixelreel.client.poster.CustomPosterClient.accept(chunk);
			case ModNetworkPayloads.PlaybackSecretsData secrets -> ClientPlaybackSecrets.accept(secrets);
			case ModNetworkPayloads.JellyfinBrowseResult browse -> {
				if (browse.provider() != pendingBrowseProvider
					|| browse.kind() != pendingBrowseKind
					|| browse.page() != pendingBrowsePage
					|| !browse.search().equals(pendingBrowseSearch)) {
					return;
				}
				ClientMediaCache.INSTANCE.acceptBrowse(browse);
				if (Minecraft.getInstance().screen instanceof BrowseResultListener screen) {
					screen.onBrowseUpdated();
				}
			}
			case ModNetworkPayloads.JellyfinChildrenResult children -> {
				ClientMediaCache.INSTANCE.acceptChildren(children);
				var screen = Minecraft.getInstance().screen;
				if (screen instanceof OnDemandSeriesScreen seriesScreen) {
					seriesScreen.onChildrenUpdated();
				} else if (screen instanceof OnDemandEpisodeScreen episodeScreen) {
					episodeScreen.onChildrenUpdated();
				} else if (screen instanceof OnDemandDetailScreen detailScreen) {
					detailScreen.onItemUpdated();
				}
			}
			case ModNetworkPayloads.JellyfinConfigData jellyfinConfig -> {
				ClientMediaCache.INSTANCE.acceptConfig(jellyfinConfig);
				if (Minecraft.getInstance().screen instanceof JellyfinConfigScreen screen) {
					screen.onConfigUpdated();
				}
			}
			case ModNetworkPayloads.EmbyConfigData embyConfig -> {
				ClientMediaCache.INSTANCE.acceptEmbyConfig(embyConfig);
				if (Minecraft.getInstance().screen instanceof EmbyConfigScreen screen) {
					screen.onConfigUpdated();
				}
			}
			case ModNetworkPayloads.PlexConfigData plexConfig -> {
				ClientMediaCache.INSTANCE.acceptPlexConfig(plexConfig);
				if (Minecraft.getInstance().screen instanceof PlexConfigScreen screen) {
					screen.onConfigUpdated();
				}
			}
			case ModNetworkPayloads.TunarrConfigData tunarrConfig -> {
				ClientMediaCache.INSTANCE.acceptTunarrConfig(tunarrConfig);
				if (Minecraft.getInstance().screen instanceof TunarrConfigScreen screen) {
					screen.onConfigUpdated();
				}
			}
			default -> {
			}
		}
	}

	public static void requestChannels(boolean forceRefresh) {
		if (canSend(ModNetworkPayloads.RequestChannels.TYPE)) {
			send(new ModNetworkPayloads.RequestChannels(forceRefresh));
		}
	}

	public static void requestMediaFeatures() {
		if (canSend(ModNetworkPayloads.RequestMediaFeatures.TYPE)) {
			send(new ModNetworkPayloads.RequestMediaFeatures());
		}
	}

	public static void requestJellyfinBrowse(
		OnDemandProvider provider,
		ModNetworkPayloads.BrowseKind kind,
		String search,
		int page,
		boolean force
	) {
		String normalized = search == null ? "" : search;
		pendingBrowseProvider = provider;
		pendingBrowseKind = kind;
		pendingBrowseSearch = normalized;
		pendingBrowsePage = page;
		if (canSend(ModNetworkPayloads.RequestJellyfinBrowse.TYPE)) {
			send(new ModNetworkPayloads.RequestJellyfinBrowse(provider, kind, normalized, page, force));
		}
	}

	public static void requestJellyfinChildren(
		OnDemandProvider provider,
		ModNetworkPayloads.ChildrenKind kind,
		String parentId,
		boolean force
	) {
		if (canSend(ModNetworkPayloads.RequestJellyfinChildren.TYPE)) {
			send(new ModNetworkPayloads.RequestJellyfinChildren(provider, kind, parentId, force));
		}
	}

	public static void playJellyfin(OnDemandProvider provider, BlockPos pos, String itemId, long startPositionMs) {
		if (canSend(ModNetworkPayloads.ScreenPlayJellyfin.TYPE)) {
			send(new ModNetworkPayloads.ScreenPlayJellyfin(provider, pos, itemId, startPositionMs));
		}
	}

	public static void reportMediaEnded(BlockPos pos, int channelEpoch) {
		if (canSend(ModNetworkPayloads.ReportMediaEnded.TYPE)) {
			send(new ModNetworkPayloads.ReportMediaEnded(pos, channelEpoch));
		}
	}

	public static void requestJellyfinConfig() {
		if (canSend(ModNetworkPayloads.RequestJellyfinConfig.TYPE)) {
			send(new ModNetworkPayloads.RequestJellyfinConfig());
		}
	}

	public static void requestEmbyConfig() {
		if (canSend(ModNetworkPayloads.RequestEmbyConfig.TYPE)) {
			send(new ModNetworkPayloads.RequestEmbyConfig());
		}
	}

	public static void requestPlexConfig() {
		if (canSend(ModNetworkPayloads.RequestPlexConfig.TYPE)) {
			send(new ModNetworkPayloads.RequestPlexConfig());
		}
	}

	public static void requestTunarrConfig() {
		if (canSend(ModNetworkPayloads.RequestTunarrConfig.TYPE)) {
			send(new ModNetworkPayloads.RequestTunarrConfig());
		}
	}

	public static void updateTunarrConfig(String m3uUrl, String xmltvUrl) {
		if (canSend(ModNetworkPayloads.UpdateTunarrConfig.TYPE)) {
			send(new ModNetworkPayloads.UpdateTunarrConfig(m3uUrl, xmltvUrl));
		}
	}

	public static void updateJellyfinConfig(
		String url,
		String apiKey,
		String userId,
		boolean movies,
		boolean shows,
		boolean autoplay,
		List<String> libraryIds
	) {
		if (canSend(ModNetworkPayloads.UpdateJellyfinConfig.TYPE)) {
			send(new ModNetworkPayloads.UpdateJellyfinConfig(url, apiKey, userId, movies, shows, autoplay, libraryIds));
		}
	}

	public static void updateEmbyConfig(
		String url,
		String apiKey,
		String userId,
		boolean movies,
		boolean shows,
		List<String> libraryIds
	) {
		if (canSend(ModNetworkPayloads.UpdateEmbyConfig.TYPE)) {
			send(new ModNetworkPayloads.UpdateEmbyConfig(url, apiKey, userId, movies, shows, libraryIds));
		}
	}

	public static void updatePlexConfig(
		String url,
		String token,
		boolean movies,
		boolean shows,
		List<String> libraryKeys
	) {
		if (canSend(ModNetworkPayloads.UpdatePlexConfig.TYPE)) {
			send(new ModNetworkPayloads.UpdatePlexConfig(url, token, movies, shows, libraryKeys));
		}
	}

	public static void refreshJellyfinLibrary() {
		if (canSend(ModNetworkPayloads.RefreshJellyfinLibrary.TYPE)) {
			send(new ModNetworkPayloads.RefreshJellyfinLibrary());
		}
	}

	public static void unequipPixelGlasses() {
		try {
			send(new ModNetworkPayloads.UnequipPixelGlasses());
		} catch (RuntimeException ignored) {
			// Not connected yet / channel not ready — local head-slot clear still dismisses the overlay.
		}
	}

	public static void sendControl(BlockPos pos, ScreenAction action, float value) {
		if (canSend(ModNetworkPayloads.ScreenControl.TYPE)) {
			send(new ModNetworkPayloads.ScreenControl(pos, action, value));
		}
	}

	public static void sendTune(BlockPos pos, String channelId) {
		if (!canSend(ModNetworkPayloads.ScreenTune.TYPE)) {
			com.pixelreel.PixelReel.LOGGER.warn("Cannot send channel tune for {} - play channel not ready", channelId);
			return;
		}
		send(new ModNetworkPayloads.ScreenTune(pos, channelId));
	}

	public static void setPosterArtwork(BlockPos pos, PosterArt art) {
		if (canSend(ModNetworkPayloads.SetPosterArtwork.TYPE)) {
			send(new ModNetworkPayloads.SetPosterArtwork(pos, art));
		}
	}

	public static void openPosterPicker(BlockPos pos) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			if (minecraft.level != null && WallPosterBlock.posterAt(minecraft.level, pos) != null) {
				minecraft.setScreen(new PosterPickerScreen(pos));
			}
		});
	}

	public static void openMenu(BlockPos pos) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			if (minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof DisplayBlockEntity display) {
				minecraft.setScreen(new MediaSourceScreen(display));
			}
		});
	}

	private static void printClientStatus(BlockPos pos) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return;
		}
		if (!(minecraft.level.getBlockEntity(pos) instanceof DisplayBlockEntity display)) {
			return;
		}
		String url = ClientPlaybackSecrets.knownStreamUrl(display);
		ChannelPlayer player = PlaybackManager.INSTANCE.player(url);
		if (player == null) {
			minecraft.player.sendSystemMessage(Component.translatable("chat.pixelreel.status.player_idle"));
			return;
		}
		String frame = player.videoTexture().frameWidth() > 0
			? player.videoTexture().frameWidth() + "x" + player.videoTexture().frameHeight()
			: "-";
		minecraft.player.sendSystemMessage(
			Component.translatable(
				"chat.pixelreel.status.player",
				player.status().name(),
				frame,
				ChannelService.hostOnly(url),
				Math.round(player.bufferingProgress()) + "%",
				player.errorDetail().isEmpty() ? "-" : player.errorDetail()
			)
		);
	}
}
