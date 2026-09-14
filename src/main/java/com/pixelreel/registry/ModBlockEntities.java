package com.pixelreel.registry;

import com.pixelreel.PixelReel;
import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.blockentities.ScreenPanelBlockEntity;
import com.pixelreel.blockentities.WallPosterBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.RegisterEvent;

public final class ModBlockEntities {
	public static final BlockEntityType<DisplayBlockEntity> DISPLAY = BlockEntityType.Builder.of(
		DisplayBlockEntity::new,
		displayBlocks()
	).build(null);

	public static final BlockEntityType<ScreenPanelBlockEntity> SCREEN_PANEL = BlockEntityType.Builder.of(
		ScreenPanelBlockEntity::new,
		ModBlocks.SCREEN_PANEL
	).build(null);

	public static final BlockEntityType<WallPosterBlockEntity> WALL_POSTER = BlockEntityType.Builder.of(
		WallPosterBlockEntity::new,
		ModBlocks.WALL_POSTER,
		ModBlocks.HUGE_WALL_POSTER
	).build(null);

	private ModBlockEntities() {
	}

	public static void register(RegisterEvent event) {
		event.register(Registries.BLOCK_ENTITY_TYPE, helper -> {
			helper.register(PixelReel.id("display"), DISPLAY);
			helper.register(PixelReel.id("screen_panel"), SCREEN_PANEL);
			helper.register(PixelReel.id("wall_poster"), WALL_POSTER);
		});
	}

	private static Block[] displayBlocks() {
		return ModBlocks.ALL_DISPLAYS.toArray(Block[]::new);
	}
}
