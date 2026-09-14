package com.pixelreel.client.gui.poster;

import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.client.ClientMediaCache;
import com.pixelreel.client.ClientNetworking;
import com.pixelreel.client.playback.ClientPlaybackSecrets;
import com.pixelreel.client.gui.GuiColors;
import com.pixelreel.client.gui.ondemand.BrowseResultListener;
import com.pixelreel.client.gui.ondemand.OnDemandPosterCard;
import com.pixelreel.client.gui.shared.SharedPoster;
import com.pixelreel.client.texture.PosterCache;
import com.pixelreel.jellyfin.JellyfinItemSummary;
import com.pixelreel.jellyfin.JellyfinStatus;
import com.pixelreel.networking.ModNetworkPayloads;
import com.pixelreel.ondemand.OnDemandProvider;
import com.pixelreel.poster.PosterArt;
import com.pixelreel.poster.WatchingArtwork;
import com.pixelreel.poster.WatchingDisplays;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Chooses which movie, show or local image a single hung poster displays. */
public class PosterPickerScreen extends Screen implements BrowseResultListener {
	private static final int PAGE_SIZE = 48;
	private static final int HEADER_HEIGHT = 78;
	private static final int FOOTER_HEIGHT = 48;
	private static final int CARD_GAP = 8;
	private static final int SEARCH_DEBOUNCE_TICKS = 12;

	private enum Tab {
		MOVIES,
		SHOWS,
		WATCHING,
		CUSTOM
	}

	private final BlockPos posterPos;
	private final List<AbstractWidget> cards = new ArrayList<>();

	private Tab tab = Tab.MOVIES;
	private OnDemandProvider provider = OnDemandProvider.JELLYFIN;
	private String search = "";
	private EditBox searchBox;
	private EditBox urlBox;
	private Component customStatus = Component.empty();
	private int columns = 1;
	private int gridLeft;
	private double scroll;
	private int page;
	private boolean loadingPage;
	private int searchDebounce;

	public PosterPickerScreen(BlockPos posterPos) {
		super(Component.translatable("gui.pixelreel.poster.title"));
		this.posterPos = posterPos;
	}

	@Override
	protected void init() {
		this.cards.clear();
		ClientNetworking.requestMediaFeatures();
		this.alignProvider();

		int centre = this.width / 2;
		this.addRenderableWidget(this.tabButton("gui.pixelreel.poster.tab_movies", Tab.MOVIES, centre - 148));
		this.addRenderableWidget(this.tabButton("gui.pixelreel.poster.tab_shows", Tab.SHOWS, centre - 72));
		this.addRenderableWidget(this.tabButton("gui.pixelreel.poster.tab_watching", Tab.WATCHING, centre + 4));
		this.addRenderableWidget(this.tabButton("gui.pixelreel.poster.tab_custom", Tab.CUSTOM, centre + 80));

		if (this.tab == Tab.WATCHING) {
			this.rebuildWatchingCards();
		} else if (this.tab == Tab.CUSTOM) {
			this.urlBox = new EditBox(this.font, centre - 150, 52, 196, 18, Component.translatable("gui.pixelreel.poster.url_hint"));
			this.urlBox.setMaxLength(PosterArt.MAX_URL);
			this.urlBox.setHint(Component.translatable("gui.pixelreel.poster.url_hint"));
			this.addRenderableWidget(this.urlBox);
			this.addRenderableWidget(
				Button.builder(Component.translatable("gui.pixelreel.poster.hang_url"), button -> this.hangUrl())
					.bounds(centre + 52, 51, 70, 20)
					.build()
			);
			this.addRenderableWidget(
				Button.builder(Component.translatable("gui.pixelreel.poster.choose_file"), button ->
					com.pixelreel.client.poster.PosterFilePicker.pickAndUpload(this.posterPos, line -> this.customStatus = line)
				).bounds(centre - 70, 76, 140, 20).build()
			);
		} else {
			this.searchBox = new EditBox(this.font, centre - 120, 51, 180, 18, Component.translatable("gui.pixelreel.jellyfin.search"));
			this.searchBox.setMaxLength(64);
			this.searchBox.setHint(Component.translatable("gui.pixelreel.poster.search_hint"));
			this.searchBox.setValue(this.search);
			this.searchBox.setResponder(value -> {
				String next = value == null ? "" : value;
				if (next.equals(this.search)) {
					return;
				}
				this.search = next;
				this.page = 0;
				this.scroll = 0.0;
				this.searchDebounce = SEARCH_DEBOUNCE_TICKS;
			});
			this.addRenderableWidget(this.searchBox);
			this.addRenderableWidget(
				Button.builder(Component.translatable("gui.pixelreel.menu.refresh"), button -> this.requestPage(true))
					.bounds(centre + 66, 50, 54, 20).build()
			);
			List<OnDemandProvider> providers = this.availableProviders();
			if (providers.size() > 1) {
				this.addRenderableWidget(
					Button.builder(Component.literal(this.provider.displayName()), button -> this.cycleProvider(providers))
						.bounds(centre - 178, 50, 54, 20).build()
				);
			}
			this.reflow();
			this.requestPage(false);
		}

		this.buildFooter();
	}

	private Button tabButton(String translationKey, Tab target, int x) {
		Button button = Button.builder(Component.translatable(translationKey), ignored -> this.selectTab(target))
			.bounds(x, 26, 72, 20)
			.build();
		button.active = this.tab != target;
		return button;
	}

	private void buildFooter() {
		int centre = this.width / 2;
		int y = this.height - 28;
		if (this.tab == Tab.MOVIES || this.tab == Tab.SHOWS) {
			this.addRenderableWidget(
				Button.builder(Component.literal("<"), button -> this.turnPage(-1)).bounds(centre - 122, y, 20, 20).build()
			);
			this.addRenderableWidget(
				Button.builder(Component.literal(">"), button -> this.turnPage(1)).bounds(centre + 102, y, 20, 20).build()
			);
		}
		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.pixelreel.poster.clear"), button -> this.bind(PosterArt.EMPTY))
				.bounds(centre - 92, y, 84, 20).build()
		);
		this.addRenderableWidget(
			Button.builder(Component.translatable("gui.pixelreel.menu.close"), button -> this.onClose())
				.bounds(centre + 8, y, 84, 20).build()
		);
	}

	private void selectTab(Tab target) {
		if (this.tab == target) {
			return;
		}
		this.tab = target;
		this.page = 0;
		this.scroll = 0.0;
		this.search = "";
		this.rebuildWidgets();
	}

	private void cycleProvider(List<OnDemandProvider> providers) {
		int index = providers.indexOf(this.provider);
		this.provider = providers.get((index + 1) % providers.size());
		this.page = 0;
		this.scroll = 0.0;
		this.rebuildWidgets();
	}

	private List<OnDemandProvider> availableProviders() {
		return ClientMediaCache.INSTANCE.features().providersFor(this.browseKind());
	}

	private ModNetworkPayloads.BrowseKind browseKind() {
		return this.tab == Tab.SHOWS ? ModNetworkPayloads.BrowseKind.SERIES : ModNetworkPayloads.BrowseKind.MOVIES;
	}

	private void alignProvider() {
		List<OnDemandProvider> providers = this.availableProviders();
		if (!providers.isEmpty() && !providers.contains(this.provider)) {
			this.provider = providers.getFirst();
		}
	}

	private void turnPage(int delta) {
		int totalPages = Math.max(1, (ClientMediaCache.INSTANCE.browseTotal() + PAGE_SIZE - 1) / PAGE_SIZE);
		int next = Math.clamp(this.page + delta, 0, totalPages - 1);
		if (next == this.page) {
			return;
		}
		this.page = next;
		this.scroll = 0.0;
		this.requestPage(false);
	}

	private void requestPage(boolean force) {
		this.searchDebounce = 0;
		this.clearCards();
		if (this.availableProviders().isEmpty()) {
			this.loadingPage = false;
			return;
		}
		this.loadingPage = true;
		ClientMediaCache.INSTANCE.beginBrowse();
		ClientNetworking.requestJellyfinBrowse(this.provider, this.browseKind(), this.search, this.page, force);
	}

	@Override
	public void onBrowseUpdated() {
		if (this.tab == Tab.WATCHING || this.tab == Tab.CUSTOM
			|| ClientMediaCache.INSTANCE.browseKind() != this.browseKind()
			|| ClientMediaCache.INSTANCE.browseProvider() != this.provider) {
			return;
		}
		this.loadingPage = false;
		this.page = ClientMediaCache.INSTANCE.browsePage();
		PosterCache.INSTANCE.clearPage();
		this.rebuildLibraryCards();
		this.clampScroll();
	}

	public void onFeaturesUpdated() {
		this.alignProvider();
		if (this.tab != Tab.WATCHING && this.tab != Tab.CUSTOM && this.cards.isEmpty() && !this.availableProviders().isEmpty()) {
			this.requestPage(false);
		}
	}

	private void clearCards() {
		for (AbstractWidget card : this.cards) {
			this.removeWidget(card);
		}
		this.cards.clear();
	}

	private void reflow() {
		int available = this.width - 24;
		this.columns = Math.max(1, (available + CARD_GAP) / (OnDemandPosterCard.CARD_WIDTH + CARD_GAP));
		int gridWidth = this.columns * (OnDemandPosterCard.CARD_WIDTH + CARD_GAP) - CARD_GAP;
		this.gridLeft = (this.width - gridWidth) / 2;
	}

	private void rebuildWatchingCards() {
		this.clearCards();
		ClientNetworking.requestChannels(false);
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		List<DisplayBlockEntity> screens = WatchingDisplays.nearby(minecraft.level, this.posterPos);
		int width = Math.min(320, this.width - 40);
		int left = (this.width - width) / 2;
		for (DisplayBlockEntity display : screens) {
			this.addCard(new WatchingScreenRow(left, 0, width, display, this::bindWatching));
		}
		this.columns = 1;
		this.gridLeft = left;
		this.applyCardVisibility();
	}

	private void bindWatching(DisplayBlockEntity display) {
		this.bind(PosterArt.watching(display.getBlockPos()));
	}

	private int rowHeight() {
		return this.tab == Tab.WATCHING ? WatchingScreenRow.HEIGHT : OnDemandPosterCard.CARD_HEIGHT;
	}

	private void rebuildLibraryCards() {
		this.clearCards();
		List<JellyfinItemSummary> items = ClientMediaCache.INSTANCE.browseItems();
		for (int i = 0; i < items.size(); i++) {
			JellyfinItemSummary item = items.get(i);
			this.addCard(new OnDemandPosterCard(0, 0, item, this::bindLibraryItem));
			PosterCache.INSTANCE.getByUrl(item.id(), item.imageUrl());
		}
		this.applyCardVisibility();
	}

	private void addCard(AbstractWidget card) {
		this.cards.add(card);
		this.addRenderableWidget(card);
	}

	private void bindLibraryItem(JellyfinItemSummary item) {
		this.bind(new PosterArt(this.provider, item.id(), item.imageUrl(), item.title()));
	}

	private void hangUrl() {
		String url = this.urlBox == null ? "" : this.urlBox.getValue().strip();
		if (!com.pixelreel.poster.PosterImages.isSafeHttpUrl(url)) {
			this.customStatus = Component.translatable("gui.pixelreel.poster.url_invalid");
			return;
		}
		this.bind(PosterArt.fromUrl(url, "Custom Poster"));
	}

	private void bind(PosterArt art) {
		ClientNetworking.setPosterArtwork(this.posterPos, art);
		this.onClose();
	}

	private void applyCardVisibility() {
		int top = HEADER_HEIGHT;
		int bottom = this.height - FOOTER_HEIGHT;
		int cols = Math.max(1, this.columns);
		for (int i = 0; i < this.cards.size(); i++) {
			AbstractWidget card = this.cards.get(i);
			int cardY = HEADER_HEIGHT + (i / cols) * (this.rowHeight() + CARD_GAP) - (int)this.scroll;
			card.setX(this.tab == Tab.WATCHING ? this.gridLeft : this.gridLeft + (i % cols) * (OnDemandPosterCard.CARD_WIDTH + CARD_GAP));
			card.setY(cardY);
			boolean visible = cardY + this.rowHeight() >= top && cardY + 8 <= bottom;
			card.visible = visible;
			card.active = visible;
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, GuiColors.TEXT);
		graphics.fill(0, this.height - FOOTER_HEIGHT, this.width, this.height, GuiColors.FOOTER);
		Status status = this.statusLine();
		graphics.drawCenteredString(this.font, status.line(), this.width / 2, this.height - FOOTER_HEIGHT + 6, status.color());
		super.render(graphics, mouseX, mouseY, partialTicks);
	}

	private record Status(Component line, int color) {
	}

	private Status statusLine() {
		if (this.tab == Tab.WATCHING) {
			if (this.cards.isEmpty()) {
				return new Status(Component.translatable("gui.pixelreel.poster.watching_none"), GuiColors.TEXT_DIM);
			}
			return new Status(Component.translatable("gui.pixelreel.poster.watching_help"), GuiColors.TEXT_DIM);
		}
		if (this.tab == Tab.CUSTOM) {
			return new Status(
				this.customStatus.getString().isEmpty()
					? Component.translatable("gui.pixelreel.poster.custom_help")
					: this.customStatus,
				GuiColors.TEXT_DIM
			);
		}
		if (this.availableProviders().isEmpty()) {
			return new Status(Component.translatable("gui.pixelreel.poster.no_library"), GuiColors.ERROR);
		}
		if (this.loadingPage) {
			return new Status(Component.translatable("gui.pixelreel.menu.loading"), GuiColors.TEXT_DIM);
		}
		JellyfinStatus status = ClientMediaCache.INSTANCE.browseStatus();
		if (!status.configured()) {
			return new Status(
				Component.translatable("gui.pixelreel.source.ondemand_not_configured", this.provider.displayName()),
				GuiColors.ERROR
			);
		}
		if (!status.authenticated()) {
			return new Status(
				Component.translatable("gui.pixelreel.source.ondemand_error", this.provider.displayName(), status.detail()),
				GuiColors.ERROR
			);
		}
		if (this.cards.isEmpty()) {
			return new Status(Component.translatable("gui.pixelreel.jellyfin.empty"), GuiColors.ERROR);
		}
		int total = ClientMediaCache.INSTANCE.browseTotal();
		return new Status(
			Component.translatable(
				"gui.pixelreel.jellyfin.page",
				total,
				this.page + 1,
				Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)
			),
			GuiColors.TEXT_DIM
		);
	}

	private int contentHeight() {
		int cols = Math.max(1, this.columns);
		int rows = (this.cards.size() + cols - 1) / cols;
		return Math.max(0, rows * (this.rowHeight() + CARD_GAP) - CARD_GAP);
	}

	private void clampScroll() {
		int viewHeight = this.height - FOOTER_HEIGHT - HEADER_HEIGHT;
		this.scroll = Math.clamp(this.scroll, 0.0, Math.max(0, this.contentHeight() - viewHeight));
		this.applyCardVisibility();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalDelta, double verticalDelta) {
		if (super.mouseScrolled(mouseX, mouseY, horizontalDelta, verticalDelta)) {
			return true;
		}
		this.scroll -= verticalDelta * 24.0;
		this.clampScroll();
		return true;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.searchDebounce > 0 && --this.searchDebounce == 0) {
			this.page = 0;
			this.requestPage(false);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static final class WatchingScreenRow extends AbstractWidget {
		static final int HEIGHT = 36;

		private final DisplayBlockEntity display;
		private final java.util.function.Consumer<DisplayBlockEntity> onSelect;

		private WatchingScreenRow(
			int x,
			int y,
			int width,
			DisplayBlockEntity display,
			java.util.function.Consumer<DisplayBlockEntity> onSelect
		) {
			super(x, y, width, HEIGHT, Component.literal(WatchingArtwork.title(display)));
			this.display = display;
			this.onSelect = onSelect;
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			this.onSelect.accept(this.display);
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
			int x = this.getX();
			int y = this.getY();
			graphics.fill(x, y, x + this.width, y + HEIGHT, this.isHovered() ? 0xE01C242C : 0xE0101418);
			int thumbW = 22;
			int thumbH = HEIGHT - 8;
			graphics.fill(x + 4, y + 4, x + 4 + thumbW, y + 4 + thumbH, 0xFF06080A);
			String image = ClientPlaybackSecrets.imageUrl(this.display);
			if (image.isEmpty()) {
				ClientPlaybackSecrets.prefetch(this.display);
				image = WatchingArtwork.imageUrl(this.display);
			}
			if (!image.isEmpty()) {
				PosterCache.Poster thumb = PosterCache.INSTANCE.getWatchingArtwork(
					this.display.getBlockPos().asLong(),
					WatchingArtwork.cacheId(this.display),
					WatchingArtwork.title(this.display),
					image
				);
				if (thumb.state() == PosterCache.State.READY && thumb.texture() != null) {
					SharedPoster.blitCover(graphics, thumb, x + 4, y + 4, thumbW, thumbH);
				} else {
					SharedPoster.blitPlaceholder(graphics, PosterCache.PLACEHOLDER, x + 4, y + 4, thumbW, thumbH);
				}
			} else {
				SharedPoster.blitPlaceholder(graphics, PosterCache.PLACEHOLDER, x + 4, y + 4, thumbW, thumbH);
			}
			var font = Minecraft.getInstance().font;
			Component screenName = Component.translatable(this.display.type().translationKey());
			String nowPlaying = WatchingArtwork.title(this.display);
			if (nowPlaying.isEmpty()) {
				nowPlaying = this.display.shouldPlay()
					? this.display.getChannelName()
					: Component.translatable("gui.pixelreel.poster.watching_idle").getString();
			}
			graphics.drawString(font, screenName, x + thumbW + 12, y + 6, GuiColors.TEXT);
			graphics.drawString(
				font,
				Component.literal(font.plainSubstrByWidth(nowPlaying, this.width - thumbW - 20)),
				x + thumbW + 12,
				y + 18,
				GuiColors.TEXT_DIM
			);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}
	}
}
