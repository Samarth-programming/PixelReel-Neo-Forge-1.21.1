package com.pixelreel.registry;

import com.pixelreel.PixelReel;
import com.pixelreel.blocks.DisplayBlock;
import com.pixelreel.blocks.DisplayType;
import com.pixelreel.items.DisplayBlockItem;
import com.pixelreel.items.PixelGlassesItem;
import com.pixelreel.items.WallPosterItem;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.RegisterEvent;

public final class ModItems {
	public static final Item COMPACT_TELEVISION = createDisplayItem(ModBlocks.COMPACT_TELEVISION, "item.pixelreel.display.type.television");
	public static final Item WALL_TELEVISION = createDisplayItem(ModBlocks.WALL_TELEVISION, "item.pixelreel.display.type.television");
	public static final Item ULTRAWIDE_MONITOR = createDisplayItem(ModBlocks.ULTRAWIDE_MONITOR, "item.pixelreel.display.type.monitor");
	public static final Item CINEMA_SCREEN = createDisplayItem(ModBlocks.CINEMA_SCREEN, "item.pixelreel.display.type.cinema");
	public static final Item CURVED_CINEMA_SCREEN = createDisplayItem(ModBlocks.CURVED_CINEMA_SCREEN, "item.pixelreel.display.type.cinema");
	public static final Item GIANT_CINEMA_SCREEN = createDisplayItem(ModBlocks.GIANT_CINEMA_SCREEN, "item.pixelreel.display.type.cinema");
	public static final Item WALL_POSTER = new WallPosterItem(ModBlocks.WALL_POSTER, new Item.Properties());
	public static final Item HUGE_WALL_POSTER = new WallPosterItem(ModBlocks.HUGE_WALL_POSTER, new Item.Properties());
	public static final Item PIXEL_GLASSES = new PixelGlassesItem(new Item.Properties().stacksTo(1));

	public static final List<Item> TAB_CONTENTS = List.of(
		COMPACT_TELEVISION,
		WALL_TELEVISION,
		ULTRAWIDE_MONITOR,
		CINEMA_SCREEN,
		CURVED_CINEMA_SCREEN,
		GIANT_CINEMA_SCREEN,
		WALL_POSTER,
		HUGE_WALL_POSTER,
		PIXEL_GLASSES
	);

	private ModItems() {
	}

	public static void register(RegisterEvent event) {
		event.register(Registries.ITEM, helper -> {
			registerBlockItem(helper, DisplayType.COMPACT_TELEVISION.id(), COMPACT_TELEVISION);
			registerBlockItem(helper, DisplayType.WALL_TELEVISION.id(), WALL_TELEVISION);
			registerBlockItem(helper, DisplayType.ULTRAWIDE_MONITOR.id(), ULTRAWIDE_MONITOR);
			registerBlockItem(helper, DisplayType.CINEMA_SCREEN.id(), CINEMA_SCREEN);
			registerBlockItem(helper, DisplayType.CURVED_CINEMA_SCREEN.id(), CURVED_CINEMA_SCREEN);
			registerBlockItem(helper, DisplayType.GIANT_CINEMA_SCREEN.id(), GIANT_CINEMA_SCREEN);
			helper.register(PixelReel.id("wall_poster"), WALL_POSTER);
			bindBlockItem(WALL_POSTER);
			helper.register(PixelReel.id("huge_wall_poster"), HUGE_WALL_POSTER);
			bindBlockItem(HUGE_WALL_POSTER);
			helper.register(PixelReel.id("pixel_glasses"), PIXEL_GLASSES);
		});
	}

	private static void registerBlockItem(RegisterEvent.RegisterHelper<Item> helper, String id, Item item) {
		helper.register(PixelReel.id(id), item);
		bindBlockItem(item);
	}

	private static Item createDisplayItem(DisplayBlock block, String tooltipKey) {
		return new DisplayBlockItem(block, new Item.Properties(), tooltipKey);
	}

	private static void bindBlockItem(Item item) {
		if (item instanceof BlockItem blockItem) {
			Item.BY_BLOCK.put(blockItem.getBlock(), item);
		}
	}
}
