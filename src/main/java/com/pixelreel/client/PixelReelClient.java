package com.pixelreel.client;

import com.pixelreel.ClientBridge;
import com.pixelreel.PixelReel;
import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.blocks.DisplayBlock;
import com.pixelreel.client.playback.PlaybackManager;
import com.pixelreel.client.playback.video.VlcRuntime;
import com.pixelreel.client.render.DisplayBlockEntityRenderer;
import com.pixelreel.client.render.GlassesOverlay;
import com.pixelreel.client.render.WallPosterRenderer;
import com.pixelreel.client.texture.PosterCache;
import com.pixelreel.items.PixelGlassesItem;
import com.pixelreel.registry.ModBlockEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

@Mod(value = PixelReel.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = PixelReel.MOD_ID, value = Dist.CLIENT)
public class PixelReelClient {
	public PixelReelClient(IEventBus modEventBus) {
		VlcRuntime.ensureInitialised();
		ClientNetworking.register();
		modEventBus.addListener(GlassesControls::registerKeys);
		modEventBus.addListener(PixelReelClient::registerRenderers);

		ClientBridge.setHandler(new ClientBridge.Handler() {
			@Override
			public void openChannelMenu(BlockPos controllerPos) {
				ClientNetworking.openMenu(controllerPos);
			}

			@Override
			public void releasePlayback(BlockPos controllerPos) {
				PlaybackManager.INSTANCE.release(controllerPos);
			}
		});
	}

	private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(ModBlockEntities.DISPLAY, DisplayBlockEntityRenderer::new);
		event.registerBlockEntityRenderer(ModBlockEntities.WALL_POSTER, WallPosterRenderer::new);
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft minecraft = Minecraft.getInstance();
		PlaybackManager.INSTANCE.clientTick();
		PosterCache.INSTANCE.clientTick();
		GlassesControls.tick(minecraft);
		tickAutoplayPrompt(minecraft);
	}

	@SubscribeEvent
	public static void onRenderGui(RenderGuiEvent.Post event) {
		GlassesOverlay.render(event.getGuiGraphics(), event.getPartialTick());
	}

	@SubscribeEvent
	public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
		PlaybackManager.INSTANCE.releaseAll();
		PosterCache.INSTANCE.clear();
		ClientChannelCache.INSTANCE.clear();
		ClientMediaCache.INSTANCE.clear();
		com.pixelreel.client.poster.CustomPosterClient.clear();
		com.pixelreel.client.playback.ClientPlaybackSecrets.clear();
	}

	@SubscribeEvent
	public static void onShuttingDown(GameShuttingDownEvent event) {
		PlaybackManager.INSTANCE.releaseAll();
		PosterCache.INSTANCE.clear();
		VlcRuntime.shutdown();
	}

	@SubscribeEvent
	public static void onScreenOpening(ScreenEvent.Opening event) {
		Minecraft client = Minecraft.getInstance();
		if (!(event.getNewScreen() instanceof PauseScreen) || client.player == null) {
			return;
		}
		if (!PixelGlassesItem.isWearing(client.player)) {
			return;
		}
		GlassesControls.requestRemove(client);
		event.setCanceled(true);
	}

	private static void tickAutoplayPrompt(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
			return;
		}
		if (minecraft.player.tickCount % 20 != 0) {
			return;
		}
		HitResult hit = minecraft.hitResult;
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		DisplayBlockEntity display = DisplayBlock.controllerAt(minecraft.level, blockHit.getBlockPos());
		if (display == null || !display.hasAutoplayPending()) {
			return;
		}
		long remaining = Math.max(0L, (display.getAutoplayAtMillis() - System.currentTimeMillis() + 999L) / 1000L);
		minecraft.player.displayClientMessage(
			Component.translatable("gui.pixelreel.playback.next_overlay", display.getNextEpisodeTitle(), remaining),
			true
		);
	}
}
