package com.pixelreel.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pixelreel.items.PixelGlassesItem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * added different Ways Ways to leave pixel-glasses fullscreen.
 */
public final class GlassesControls {
	private static KeyMapping removeGlassesKey;

	private GlassesControls() {
	}

	public static void registerKeys(RegisterKeyMappingsEvent event) {
		removeGlassesKey = new KeyMapping(
			"key.pixelreel.remove_pixel_glasses",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_X,
			"key.category.pixelreel.pixelreel"
		);
		event.register(removeGlassesKey);
	}

	public static KeyMapping removeKey() {
		return removeGlassesKey;
	}

	public static void tick(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
			return;
		}
		if (!PixelGlassesItem.isWearing(minecraft.player)) {
			return;
		}
		if (removeGlassesKey != null && removeGlassesKey.consumeClick()) {
			requestRemove(minecraft);
		}
	}

	public static void requestRemove(Minecraft minecraft) {
		if (minecraft.player == null || !PixelGlassesItem.isWearing(minecraft.player)) {
			return;
		}
		minecraft.player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
		ClientNetworking.unequipPixelGlasses();
	}
}
