package com.pixelreel.blockentities;

import com.pixelreel.blocks.WallPosterBlock;
import com.pixelreel.poster.PosterArt;
import com.pixelreel.poster.PosterSize;
import com.pixelreel.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** remembers which artwork this hanging shows */
public class WallPosterBlockEntity extends BlockEntity {
	private PosterArt art = PosterArt.EMPTY;

	public WallPosterBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.WALL_POSTER, pos, state);
	}

	public PosterArt art() {
		return this.art;
	}

	public void setArt(PosterArt art) {
		this.art = art == null ? PosterArt.EMPTY : art;
		this.setChanged();
		if (this.level != null && !this.level.isClientSide()) {
			BlockState state = this.getBlockState();
			this.level.sendBlockUpdated(this.worldPosition, state, state, Block.UPDATE_ALL);
		}
	}

	public Direction facing() {
		BlockState state = this.getBlockState();
		return state.getBlock() instanceof WallPosterBlock ? state.getValue(WallPosterBlock.FACING) : Direction.NORTH;
	}

	public PosterSize size() {
		return this.getBlockState().getBlock() instanceof WallPosterBlock poster ? poster.size() : PosterSize.STANDARD;
	}

	@Override
	protected void saveAdditional(CompoundTag output, HolderLookup.Provider registries) {
		super.saveAdditional(output, registries);
		com.pixelreel.media.MediaProxy.forWorldSave(this.art).save(output);
	}

	@Override
	protected void loadAdditional(CompoundTag input, HolderLookup.Provider registries) {
		super.loadAdditional(input, registries);
		this.art = PosterArt.load(input);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		com.pixelreel.media.MediaProxy.forClientArtwork(this.art).save(tag);
		return tag;
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
