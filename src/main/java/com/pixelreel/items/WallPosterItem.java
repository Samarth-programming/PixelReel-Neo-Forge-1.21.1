package com.pixelreel.items;

import com.pixelreel.blocks.WallPosterBlock;
import com.pixelreel.permissions.CinemaPermissions;
import com.pixelreel.poster.PosterLayout;
import com.pixelreel.poster.PosterSize;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/** explains where a poster may hang and refuses placement for players without the permission */
public class WallPosterItem extends BlockItem {
	public WallPosterItem(Block block, Item.Properties properties) {
		super(block, properties);
	}

	private PosterSize size() {
		return this.getBlock() instanceof WallPosterBlock poster ? poster.size() : PosterSize.STANDARD;
	}

	@Override
	public InteractionResult place(BlockPlaceContext context) {
		Player player = context.getPlayer();
		if (player instanceof ServerPlayer serverPlayer && !CinemaPermissions.canPlacePosters(serverPlayer)) {
			serverPlayer.displayClientMessage(Component.translatable("message.pixelreel.poster.no_permission"), true);
			return InteractionResult.FAIL;
		}
		InteractionResult result = super.place(context);
		if (!result.consumesAction() && player instanceof ServerPlayer serverPlayer) {
			PosterSize size = this.size();
			if (size.fillsAvailableWall()) {
				serverPlayer.displayClientMessage(
					Component.translatable("message.pixelreel.poster.cannot_place_fill"),
					true
				);
			} else {
				serverPlayer.displayClientMessage(
					Component.translatable("message.pixelreel.poster.cannot_place", size.widthBlocks(), size.heightBlocks()),
					true
				);
			}
		}
		return result;
	}

	/**
	 * Slide the poster so the clicked block is somewhere on it, not only the bottom-left corner. Huge posters
	 * also grow to fill the clear wall around the click.
	 */
	@Override
	public @Nullable BlockPlaceContext updatePlacementContext(BlockPlaceContext context) {
		Direction clickedFace = context.getClickedFace();
		Direction facing = clickedFace.getAxis().isHorizontal()
			? clickedFace
			: context.getHorizontalDirection().getOpposite();
		BlockPos clicked = context.getClickedPos();
		BlockPos seed = clicked;
		if (!context.getLevel().getBlockState(clicked).canBeReplaced()) {
			seed = clicked.relative(clickedFace);
		}
		PosterLayout.Placement placement = PosterLayout.findPlacement(
			context.getLevel(),
			seed,
			facing,
			this.size(),
			context
		);
		if (placement == null && !seed.equals(clicked)) {
			placement = PosterLayout.findPlacement(context.getLevel(), clicked, facing, this.size(), context);
		}
		if (placement == null) {
			return null;
		}
		return BlockPlaceContext.at(context, placement.origin(), facing);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag flag) {
		super.appendHoverText(stack, context, tooltipComponents, flag);
		tooltipComponents.add(Component.translatable("item.pixelreel.wall_poster.keywords").withStyle(ChatFormatting.DARK_GRAY));
		tooltipComponents.add(Component.translatable(this.size().tooltipKey()).withStyle(ChatFormatting.GRAY));
	}
}
