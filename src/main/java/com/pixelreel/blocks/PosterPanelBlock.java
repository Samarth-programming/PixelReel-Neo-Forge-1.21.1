package com.pixelreel.blocks;

import com.mojang.serialization.MapCodec;
import com.pixelreel.poster.PosterLayout;
import com.pixelreel.poster.PosterSize;
import com.pixelreel.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** filler cell of a hung poster; carries its grid position so it can find the anchor without a block entity */
public class PosterPanelBlock extends Block {
	public static final MapCodec<PosterPanelBlock> CODEC = simpleCodec(PosterPanelBlock::new);
	public static final EnumProperty<Direction> FACING = EnumProperty.create(
		"facing",
		Direction.class,
		Direction.Plane.HORIZONTAL
	);
	public static final IntegerProperty COLUMN = IntegerProperty.create("column", 0, PosterSize.MAX_SPAN - 1);
	public static final IntegerProperty ROW = IntegerProperty.create("row", 0, PosterSize.MAX_SPAN - 1);

	public PosterPanelBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(
			this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(COLUMN, 0).setValue(ROW, 0)
		);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, COLUMN, ROW);
	}

	public static BlockPos anchorOf(BlockPos pos, BlockState state) {
		return PosterLayout.anchorPos(pos, state.getValue(FACING), state.getValue(COLUMN), state.getValue(ROW));
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		return null;
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
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		WallPosterBlock.breakFromPanel(level, anchorOf(pos, state), !player.getAbilities().instabuild);
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock())) {
			WallPosterBlock.breakFromPanel(level, anchorOf(pos, state), true);
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
		BlockState anchor = level.getBlockState(anchorOf(pos, state));
		if (anchor.getBlock() instanceof WallPosterBlock) {
			return new ItemStack(anchor.getBlock());
		}
		return new ItemStack(ModItems.WALL_POSTER);
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
		return WallPosterBlock.interactWithPoster(level, anchorOf(pos, state), player);
	}
}
