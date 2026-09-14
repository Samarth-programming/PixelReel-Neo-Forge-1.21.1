package com.pixelreel.blocks;

import com.mojang.serialization.MapCodec;
import com.pixelreel.blockentities.WallPosterBlockEntity;
import com.pixelreel.networking.ModNetworkPayloads;
import com.pixelreel.permissions.CinemaPermissions;
import com.pixelreel.poster.PosterLayout;
import com.pixelreel.poster.PosterSize;
import com.pixelreel.registry.ModBlocks;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** the bottom-left cell of a hung poster; the other cells are {@link PosterPanelBlock} fillers */
public class WallPosterBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = EnumProperty.create(
		"facing",
		Direction.class,
		Direction.Plane.HORIZONTAL
	);
	public static final IntegerProperty HUNG_WIDTH = IntegerProperty.create("hung_width", 1, PosterSize.MAX_SPAN);
	public static final IntegerProperty HUNG_HEIGHT = IntegerProperty.create("hung_height", 1, PosterSize.MAX_SPAN);

	private final PosterSize size;

	public WallPosterBlock(Properties properties, PosterSize size) {
		super(properties);
		this.size = size;
		this.registerDefaultState(
			this.stateDefinition.any()
				.setValue(FACING, Direction.NORTH)
				.setValue(HUNG_WIDTH, size.widthBlocks())
				.setValue(HUNG_HEIGHT, size.heightBlocks())
		);
	}

	public static int hungWidth(BlockState state) {
		return state.hasProperty(HUNG_WIDTH) ? state.getValue(HUNG_WIDTH) : PosterSize.STANDARD.widthBlocks();
	}

	public static int hungHeight(BlockState state) {
		return state.hasProperty(HUNG_HEIGHT) ? state.getValue(HUNG_HEIGHT) : PosterSize.STANDARD.heightBlocks();
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		PosterSize posterSize = this.size;
		return simpleCodec(properties -> new WallPosterBlock(properties, posterSize));
	}

	public PosterSize size() {
		return this.size;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, HUNG_WIDTH, HUNG_HEIGHT);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new WallPosterBlockEntity(pos, state);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction facing = context.getClickedFace();
		if (!facing.getAxis().isHorizontal()) {
			facing = context.getHorizontalDirection().getOpposite();
		}
		PosterLayout.Placement placement = PosterLayout.fitFromOrigin(
			context.getLevel(),
			context.getClickedPos(),
			facing,
			this.size
		);
		if (placement == null) {
			return null;
		}
		return this.defaultBlockState()
			.setValue(FACING, facing)
			.setValue(HUNG_WIDTH, placement.width())
			.setValue(HUNG_HEIGHT, placement.height());
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		return PosterLayout.hasBacking(level, pos, state.getValue(FACING));
	}

	@Override
	protected BlockState updateShape(
		BlockState state,
		Direction direction,
		BlockState neighborState,
		LevelAccessor level,
		BlockPos pos,
		BlockPos neighborPos
	) {
		if (direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) {
			return Blocks.AIR.defaultBlockState();
		}
		return state;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
		super.setPlacedBy(level, pos, state, by, itemStack);
		if (level.isClientSide()) {
			return;
		}
		this.placePanels(level, pos, state);
		if (by instanceof ServerPlayer player) {
			openPicker(player, pos);
		}
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock())) {
			this.removePanels(level, pos, state);
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	private void placePanels(Level level, BlockPos anchor, BlockState state) {
		Direction facing = state.getValue(FACING);
		int width = hungWidth(state);
		int height = hungHeight(state);
		BlockState panel = ModBlocks.POSTER_PANEL.defaultBlockState().setValue(PosterPanelBlock.FACING, facing);
		for (int column = 0; column < width; column++) {
			for (int row = 0; row < height; row++) {
				BlockPos cell = PosterLayout.cellPos(anchor, facing, column, row);
				if (cell.equals(anchor)) {
					continue;
				}
				level.setBlock(
					cell,
					panel.setValue(PosterPanelBlock.COLUMN, column).setValue(PosterPanelBlock.ROW, row),
					Block.UPDATE_ALL
				);
			}
		}
	}

	private void removePanels(Level level, BlockPos anchor, BlockState state) {
		Direction facing = state.getValue(FACING);
		int width = hungWidth(state);
		int height = hungHeight(state);
		for (int column = 0; column < width; column++) {
			for (int row = 0; row < height; row++) {
				BlockPos cell = PosterLayout.cellPos(anchor, facing, column, row);
				if (cell.equals(anchor)) {
					continue;
				}
				BlockState existing = level.getBlockState(cell);
				if (existing.getBlock() instanceof PosterPanelBlock
					&& PosterPanelBlock.anchorOf(cell, existing).equals(anchor)) {
					level.removeBlock(cell, false);
				}
			}
		}
	}

	/** Breaking any filler cell takes the whole poster down, so players never leave a half-hung frame behind. */
	static void breakFromPanel(Level level, BlockPos anchor, boolean dropItem) {
		if (level.isClientSide() || !(level.getBlockState(anchor).getBlock() instanceof WallPosterBlock)) {
			return;
		}
		level.destroyBlock(anchor, dropItem);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return PosterLayout.shape(state.getValue(FACING));
	}

	@Override
	protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
		return PosterLayout.shape(state.getValue(FACING));
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
		return Shapes.empty();
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return true;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0F;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		return interactWithPoster(level, pos, player);
	}

	public static InteractionResult interactWithPoster(Level level, BlockPos anchor, Player player) {
		if (!(level.getBlockEntity(anchor) instanceof WallPosterBlockEntity)) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			openPicker(serverPlayer, anchor);
		}
		return InteractionResult.SUCCESS;
	}

	private static void openPicker(ServerPlayer player, BlockPos anchor) {
		if (!CinemaPermissions.canPlacePosters(player)) {
			PacketDistributor.sendToPlayer(
				player,
				new ModNetworkPayloads.ScreenNotice(anchor, "message.pixelreel.poster.no_permission")
			);
			return;
		}
		PacketDistributor.sendToPlayer(player, new ModNetworkPayloads.OpenPosterPicker(anchor));
	}

	public static @Nullable WallPosterBlockEntity posterAt(@Nullable BlockGetter level, @Nullable BlockPos pos) {
		if (level == null || pos == null) {
			return null;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity instanceof WallPosterBlockEntity poster) {
			return poster;
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof PosterPanelBlock)) {
			return null;
		}
		return level.getBlockEntity(PosterPanelBlock.anchorOf(pos, state)) instanceof WallPosterBlockEntity poster
			? poster
			: null;
	}
}
