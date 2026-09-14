package com.pixelreel;

import com.pixelreel.audio.AudioZoneClaimSessions;
import com.pixelreel.channels.ChannelService;
import com.pixelreel.commands.TvCommand;
import com.pixelreel.config.ConfigManager;
import com.pixelreel.media.MediaProxy;
import com.pixelreel.networking.ModNetworkPayloads;
import com.pixelreel.networking.PlaybackSecrets;
import com.pixelreel.ondemand.OnDemandCatalog;
import com.pixelreel.poster.CustomPosterStore;
import com.pixelreel.registry.ModBlockEntities;
import com.pixelreel.registry.ModBlocks;
import com.pixelreel.registry.ModCreativeTabs;
import com.pixelreel.registry.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(PixelReel.MOD_ID)
public class PixelReel {
	public static final String MOD_ID = "pixelreel";
	public static final Logger LOGGER = LoggerFactory.getLogger("pixelreel");

	public PixelReel(IEventBus modEventBus) {
		ConfigManager.load();
		modEventBus.addListener(ModBlocks::register);
		modEventBus.addListener(ModItems::register);
		modEventBus.addListener(ModBlockEntities::register);
		modEventBus.addListener(ModCreativeTabs::register);
		modEventBus.addListener(ModNetworkPayloads::register);
		NeoForge.EVENT_BUS.register(ModEvents.class);
		LOGGER.info("pixelReel initialised");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	public static final class ModEvents {
		private ModEvents() {
		}

		@SubscribeEvent
		public static void onServerStarting(ServerStartingEvent event) {
			MediaProxy.start();
		}

		@SubscribeEvent
		public static void onServerStarted(ServerStartedEvent event) {
			LOGGER.info("pixelReel configuration file: {}", ConfigManager.path());
			CustomPosterStore.attach(event.getServer());
			ChannelService.INSTANCE.channels(false);
			OnDemandCatalog.refreshConfigured(false);
		}

		@SubscribeEvent
		public static void onServerStopped(ServerStoppedEvent event) {
			MediaProxy.stop();
			CustomPosterStore.detach();
			ChannelService.INSTANCE.invalidateCache();
			OnDemandCatalog.invalidateAll();
		}

		@SubscribeEvent
		public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
			if (event.getEntity() instanceof ServerPlayer player) {
				PlaybackSecrets.forget(player);
			}
		}

		@SubscribeEvent
		public static void onRegisterCommands(RegisterCommandsEvent event) {
			TvCommand.register(event.getDispatcher());
		}

		@SubscribeEvent
		public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
			InteractionResult result = AudioZoneClaimSessions.handleUse(event);
			if (result != InteractionResult.PASS) {
				event.setCancellationResult(result);
				event.setCanceled(true);
			}
		}
	}
}
