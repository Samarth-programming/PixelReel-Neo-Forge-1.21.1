package com.pixelreel.registry;

import com.pixelreel.PixelReel;
import com.pixelreel.blocks.DisplayBlock;
import com.pixelreel.blocks.DisplayType;
import com.pixelreel.blocks.PosterPanelBlock;
import com.pixelreel.blocks.ScreenPanelBlock;
import com.pixelreel.blocks.WallPosterBlock;
import com.pixelreel.poster.PosterSize;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.RegisterEvent;

public final class ModBlocks {
	public static final DisplayBlock COMPACT_TELEVISION = createDisplay(DisplayType.COMPACT_TELEVISION);
	public static final DisplayBlock WALL_TELEVISION = createDisplay(DisplayType.WALL_TELEVISION);
	public static final DisplayBlock ULTRAWIDE_MONITOR = createDisplay(DisplayType.ULTRAWIDE_MONITOR);
	public static final DisplayBlock CINEMA_SCREEN = createDisplay(DisplayType.CINEMA_SCREEN);
	public static final DisplayBlock CURVED_CINEMA_SCREEN = createDisplay(DisplayType.CURVED_CINEMA_SCREEN);
	public static final DisplayBlock GIANT_CINEMA_SCREEN = createDisplay(DisplayType.GIANT_CINEMA_SCREEN);
	public static final ScreenPanelBlock SCREEN_PANEL = new ScreenPanelBlock(panelProperties());
	public static final WallPosterBlock WALL_POSTER = new WallPosterBlock(posterProperties(), PosterSize.STANDARD);
	public static final WallPosterBlock HUGE_WALL_POSTER = new WallPosterBlock(posterProperties(), PosterSize.HUGE);
	public static final PosterPanelBlock POSTER_PANEL = new PosterPanelBlock(posterProperties().noLootTable());

	public static final List<DisplayBlock> ALL_DISPLAYS = List.of(
		COMPACT_TELEVISION, WALL_TELEVISION, ULTRAWIDE_MONITOR, CINEMA_SCREEN, CURVED_CINEMA_SCREEN, GIANT_CINEMA_SCREEN
	);

	private ModBlocks() {
	}

	public static void register(RegisterEvent event) {
		event.register(Registries.BLOCK, helper -> {
			helper.register(PixelReel.id(DisplayType.COMPACT_TELEVISION.id()), COMPACT_TELEVISION);
			helper.register(PixelReel.id(DisplayType.WALL_TELEVISION.id()), WALL_TELEVISION);
			helper.register(PixelReel.id(DisplayType.ULTRAWIDE_MONITOR.id()), ULTRAWIDE_MONITOR);
			helper.register(PixelReel.id(DisplayType.CINEMA_SCREEN.id()), CINEMA_SCREEN);
			helper.register(PixelReel.id(DisplayType.CURVED_CINEMA_SCREEN.id()), CURVED_CINEMA_SCREEN);
			helper.register(PixelReel.id(DisplayType.GIANT_CINEMA_SCREEN.id()), GIANT_CINEMA_SCREEN);
			helper.register(PixelReel.id("screen_panel"), SCREEN_PANEL);
			helper.register(PixelReel.id(PosterSize.STANDARD.id()), WALL_POSTER);
			helper.register(PixelReel.id(PosterSize.HUGE.id()), HUGE_WALL_POSTER);
			helper.register(PixelReel.id("poster_panel"), POSTER_PANEL);
		});
	}

	private static DisplayBlock createDisplay(DisplayType type) {
		BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_BLACK)
			.strength(1.5F, 6.0F)
			.sound(SoundType.METAL)
			.noOcclusion()
			.pushReaction(PushReaction.BLOCK)
			.isRedstoneConductor((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isViewBlocking((state, level, pos) -> false);
		return new DisplayBlock(properties, type);
	}

	private static BlockBehaviour.Properties panelProperties() {
		return BlockBehaviour.Properties.of()
			.mapColor(MapColor.NONE)
			.strength(-1.0F, 3600000.0F)
			.noOcclusion()
			.pushReaction(PushReaction.BLOCK)
			.noLootTable()
			.isRedstoneConductor((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isViewBlocking((state, level, pos) -> false);
	}

	private static BlockBehaviour.Properties posterProperties() {
		return BlockBehaviour.Properties.of()
			.mapColor(MapColor.TERRACOTTA_WHITE)
			.strength(0.2F)
			.sound(SoundType.WOOL)
			.noOcclusion()
			.noCollission()
			.pushReaction(PushReaction.DESTROY)
			.isRedstoneConductor((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isViewBlocking((state, level, pos) -> false);
	}
}
