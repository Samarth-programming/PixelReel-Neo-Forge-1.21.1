package com.pixelreel.client.gui.poster;

import com.pixelreel.client.gui.ondemand.OnDemandPosterCard;
import com.pixelreel.client.gui.shared.SharedPoster;
import com.pixelreel.client.texture.PosterCache;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Poster card backed by an image file in config/pixelreel-posters. */
public class LocalPosterCard extends AbstractWidget {
	private static final int COLOR_CARD = 0xE0101418;
	private static final int COLOR_CARD_HOVER = 0xE01C242C;
	private static final int COLOR_POSTER_BACK = 0xFF06080A;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_TEXT_FAINT = 0xFF6E7880;

	private final String fileName;
	private final Consumer<String> onSelect;

	public LocalPosterCard(int x, int y, String fileName, Consumer<String> onSelect) {
		super(x, y, OnDemandPosterCard.CARD_WIDTH, OnDemandPosterCard.CARD_HEIGHT, Component.literal(fileName));
		this.fileName = fileName;
		this.onSelect = onSelect;
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		this.onSelect.accept(this.fileName);
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		int x = this.getX();
		int y = this.getY();
		boolean hovered = this.isHovered();
		graphics.fill(x, y, x + this.width, y + this.height, hovered ? COLOR_CARD_HOVER : COLOR_CARD);

		int thumbX = x + 3;
		int thumbY = y + 3;
		int thumbWidth = this.width - 6;
		int thumbHeight = OnDemandPosterCard.POSTER_HEIGHT - 6;
		graphics.fill(thumbX, thumbY, thumbX + thumbWidth, thumbY + thumbHeight, COLOR_POSTER_BACK);

		var font = Minecraft.getInstance().font;
		PosterCache.Poster thumb = PosterCache.INSTANCE.getLocalPreview(this.fileName);
		if (thumb.state() == PosterCache.State.READY && thumb.texture() != null) {
			SharedPoster.blitCover(graphics, thumb, thumbX, thumbY, thumbWidth, thumbHeight);
		} else {
			SharedPoster.blitPlaceholder(graphics, PosterCache.PLACEHOLDER, thumbX, thumbY, thumbWidth, thumbHeight);
			Component label = thumb.state() == PosterCache.State.LOADING
				? Component.translatable("gui.pixelreel.menu.loading_art")
				: Component.literal(font.plainSubstrByWidth(this.fileName, thumbWidth - 4));
			graphics.drawCenteredString(font, label, x + this.width / 2, thumbY + thumbHeight / 2 - 4, COLOR_TEXT_FAINT);
		}

		graphics.drawString(
			font,
			Component.literal(font.plainSubstrByWidth(LocalPosterLibrary.titleOf(this.fileName), this.width - 8)),
			x + 4,
			y + OnDemandPosterCard.POSTER_HEIGHT + 6,
			COLOR_TEXT
		);

		if (hovered) {
			graphics.renderComponentTooltip(font, List.of(
				Component.literal(this.fileName).withStyle(ChatFormatting.WHITE),
				Component.translatable("gui.pixelreel.poster.click_hang").withStyle(ChatFormatting.BLUE)
			), mouseX, mouseY);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}
