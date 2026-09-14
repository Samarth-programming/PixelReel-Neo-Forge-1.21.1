package com.pixelreel.registry;

import com.pixelreel.PixelReel;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.RegisterEvent;

public final class ModCreativeTabs {
	public static final ResourceKey<CreativeModeTab> PIXEL_REEL = ResourceKey.create(Registries.CREATIVE_MODE_TAB, PixelReel.id("pixelreel"));

	private ModCreativeTabs() {
	}

	public static void register(RegisterEvent event) {
		event.register(Registries.CREATIVE_MODE_TAB, helper -> helper.register(
			PIXEL_REEL,
			CreativeModeTab.builder()
				.title(Component.translatable("itemGroup.pixelreel.pixelreel"))
				.icon(() -> new ItemStack(ModItems.WALL_TELEVISION))
				.displayItems((parameters, output) -> ModItems.TAB_CONTENTS.forEach(output::accept))
				.build()
		));
	}
}
