package com.pixelreel.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.pixelreel.PixelReel;
import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.blockentities.WallPosterBlockEntity;
import com.pixelreel.blocks.WallPosterBlock;
import com.pixelreel.channels.ChannelEntry;
import com.pixelreel.client.ClientChannelCache;
import com.pixelreel.client.playback.ClientPlaybackSecrets;
import com.pixelreel.client.texture.PosterCache;
import com.pixelreel.poster.PosterArt;
import com.pixelreel.poster.PosterLayout;
import com.pixelreel.poster.WatchingArtwork;
import com.pixelreel.poster.WatchingDisplays;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Draws hung poster artwork. Geometry is built facing north and then yaw-rotated the same way the
 * display renderer does it, which keeps the image the right way round instead of mirrored on east/west walls.
 */
public class WallPosterRenderer implements BlockEntityRenderer<WallPosterBlockEntity> {
	private static final ResourceLocation FRAME = PixelReel.id("textures/block/tv_body.png");
	private static final ResourceLocation BLANK = PixelReel.id("textures/block/screen_off.png");

	private static final float PIXEL = 0.0625F;
	/** Right-hand edge of the anchor cell in the north-facing frame; sheet coordinates grow away from it. */
	private static final float SHEET_ORIGIN_X = 16.0F;
	private static final float BACK_DEPTH_PX = 15.9F;
	private static final int MIN_BLOCK_LIGHT = 7;

	public WallPosterRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(
		WallPosterBlockEntity blockEntity,
		float partialTicks,
		PoseStack poseStack,
		MultiBufferSource bufferSource,
		int packedLight,
		int packedOverlay
	) {
		Direction facing = blockEntity.facing();
		BlockState state = blockEntity.getBlockState();
		float widthPx = WallPosterBlock.hungWidth(state) * 16.0F;
		float heightPx = WallPosterBlock.hungHeight(state) * 16.0F;
		int light = brighten(packedLight);

		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F);
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - facing.toYRot()));
		poseStack.translate(-0.5F, -0.5F, -0.5F);

		PoseStack.Pose pose = poseStack.last();
		quad(pose, bufferSource, FRAME, 0.0F, widthPx, 0.0F, heightPx,
			PosterLayout.FRAME_DEPTH_PX, false, light, 0.0F, 1.0F, 0.0F, 1.0F);
		quad(pose, bufferSource, FRAME, 0.0F, widthPx, 0.0F, heightPx,
			BACK_DEPTH_PX, true, light, 0.0F, 1.0F, 0.0F, 1.0F);

		float border = PosterLayout.FRAME_BORDER_PX;
		float artWidth = widthPx - border * 2.0F;
		float artHeight = heightPx - border * 2.0F;
		PosterArt art = blockEntity.art();
		PosterCache.Poster poster = resolveArtwork(blockEntity, art);

		float artLeft = border;
		float artRight = border + artWidth;
		float artBottom = border;
		float artTop = border + artHeight;
		ResourceLocation artwork = BLANK;
		float[] uv = {0.0F, 1.0F, 0.0F, 1.0F};
		if (poster != null && poster.state() == PosterCache.State.READY && poster.texture() != null) {
			artwork = poster.texture();
			float[] slot = contain(poster.width(), poster.height(), artWidth, artHeight);
			artLeft = border + slot[0];
			artRight = border + slot[1];
			artBottom = border + slot[2];
			artTop = border + slot[3];
		}
		quad(pose, bufferSource, artwork, artLeft, artRight, artBottom, artTop,
			PosterLayout.ART_DEPTH_PX, false, light, uv[0], uv[1], uv[2], uv[3]);

		poseStack.popPose();
	}

	private static PosterCache.Poster resolveArtwork(WallPosterBlockEntity blockEntity, PosterArt art) {
		if (art.isEmpty()) {
			return null;
		}
		if (art.isWatching()) {
			DisplayBlockEntity display = WatchingDisplays.at(blockEntity.getLevel(), art.watchingDisplayPos());
			if (display == null) {
				display = WatchingDisplays.nearestPlaying(blockEntity.getLevel(), blockEntity.getBlockPos());
			}
			if (display == null) {
				return null;
			}
			// Artwork sits on the media server, so it arrives with the stream URL rather than with the block.
			ClientPlaybackSecrets.prefetch(display);
			String image = ClientPlaybackSecrets.imageUrl(display);
			if (!image.isEmpty()) {
				return PosterCache.INSTANCE.getWatchingArtwork(
					display.getBlockPos().asLong(),
					WatchingArtwork.cacheId(display),
					WatchingArtwork.title(display),
					image
				);
			}
			ChannelEntry channel = ClientChannelCache.INSTANCE.byId(display.getChannelId());
			return channel == null ? null : PosterCache.INSTANCE.get(channel);
		}
		// A catalog item's artwork comes from the server's own media host; a bare URL was typed by a player.
		boolean fromCatalog = !art.itemId().isEmpty();
		return PosterCache.INSTANCE.getPlacedArtwork(art.cacheKey(), art.title(), art.imageUrl(), fromCatalog);
	}

	/** Posters are printed paper, not a screen, so lift the block light just enough to read them in a dim theater. */
	private static int brighten(int packedLight) {
		int block = Math.max(LightTexture.block(packedLight), MIN_BLOCK_LIGHT);
		return LightTexture.pack(block, LightTexture.sky(packedLight));
	}

	/**
	 * Draws the whole poster inside the hanging. Nothing is cropped; a 2:3 hang matches typical artwork so the
	 * picture fills the frame.
	 * Returns left, right, bottom, top in slot pixels.
	 */
	private static float[] contain(int textureWidth, int textureHeight, float slotWidth, float slotHeight) {
		if (textureWidth <= 0 || textureHeight <= 0 || slotWidth <= 0.0F || slotHeight <= 0.0F) {
			return new float[]{0.0F, slotWidth, 0.0F, slotHeight};
		}
		float textureAspect = (float)textureWidth / textureHeight;
		float slotAspect = slotWidth / slotHeight;
		float drawWidth;
		float drawHeight;
		if (textureAspect > slotAspect) {
			drawWidth = slotWidth;
			drawHeight = slotWidth / textureAspect;
		} else {
			drawHeight = slotHeight;
			drawWidth = slotHeight * textureAspect;
		}
		float left = (slotWidth - drawWidth) * 0.5F;
		float bottom = (slotHeight - drawHeight) * 0.5F;
		return new float[]{left, left + drawWidth, bottom, bottom + drawHeight};
	}

	private static void quad(
		PoseStack.Pose pose,
		MultiBufferSource bufferSource,
		ResourceLocation texture,
		float sheetMin,
		float sheetMax,
		float bottom,
		float top,
		float depth,
		boolean backFace,
		int packedLight,
		float uMin,
		float uMax,
		float vMin,
		float vMax
	) {
		if (sheetMax <= sheetMin || top <= bottom) {
			return;
		}
		VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutout(texture));
		// Sheet coordinates run left-to-right for a viewer facing the poster, and local x runs the other way.
		float xLeft = SHEET_ORIGIN_X - sheetMin;
		float xRight = SHEET_ORIGIN_X - sheetMax;
		float normalZ = backFace ? 1.0F : -1.0F;
		if (backFace) {
			vertex(buffer, pose, xLeft, bottom, depth, uMin, vMax, normalZ, packedLight);
			vertex(buffer, pose, xLeft, top, depth, uMin, vMin, normalZ, packedLight);
			vertex(buffer, pose, xRight, top, depth, uMax, vMin, normalZ, packedLight);
			vertex(buffer, pose, xRight, bottom, depth, uMax, vMax, normalZ, packedLight);
		} else {
			vertex(buffer, pose, xLeft, bottom, depth, uMin, vMax, normalZ, packedLight);
			vertex(buffer, pose, xRight, bottom, depth, uMax, vMax, normalZ, packedLight);
			vertex(buffer, pose, xRight, top, depth, uMax, vMin, normalZ, packedLight);
			vertex(buffer, pose, xLeft, top, depth, uMin, vMin, normalZ, packedLight);
		}
	}

	private static void vertex(
		VertexConsumer buffer,
		PoseStack.Pose pose,
		float xPixels,
		float yPixels,
		float zPixels,
		float u,
		float v,
		float normalZ,
		int packedLight
	) {
		buffer.addVertex(pose, xPixels * PIXEL, yPixels * PIXEL, zPixels * PIXEL)
			.setColor(255, 255, 255, 255)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(packedLight)
			.setNormal(pose, 0.0F, 0.0F, normalZ);
	}

	@Override
	public boolean shouldRenderOffScreen(WallPosterBlockEntity blockEntity) {
		// The anchor sits in the bottom-left cell, so most of the artwork lives outside its own block bounds.
		return true;
	}

	@Override
	public AABB getRenderBoundingBox(WallPosterBlockEntity blockEntity) {
		BlockState state = blockEntity.getBlockState();
		Direction facing = blockEntity.facing();
		BlockPos anchor = blockEntity.getBlockPos();
		int width = Math.max(1, WallPosterBlock.hungWidth(state));
		int height = Math.max(1, WallPosterBlock.hungHeight(state));
		BlockPos far = PosterLayout.cellPos(anchor, facing, width - 1, height - 1);
		return new AABB(anchor).minmax(new AABB(far)).inflate(0.25);
	}

	@Override
	public int getViewDistance() {
		return 160;
	}
}
