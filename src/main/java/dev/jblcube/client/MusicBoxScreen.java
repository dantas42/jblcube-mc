package dev.jblcube.client;

import dev.jblcube.data.MusicBoxData;
import dev.jblcube.data.MusicBoxTrack;
import dev.jblcube.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class MusicBoxScreen extends Screen {
    private static final int PANEL_WIDTH = 348;
    private static final int PANEL_HEIGHT = 210;
    private static final int PADDING = 10;
    private static final int MAX_URL_LENGTH = 512;
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_VISIBLE_ROWS = 4;
    private static final int SCROLLBAR_SLOT = 8;
    private static final int REMOVE_BOX_SIZE = 12;

    private static final int TITLE_Y = 8;
    private static final int DIVIDER_Y = 21;
    private static final int URL_Y = 28;
    private static final int LIST_Y = 54;
    private static final int BAR_HEIGHT = 6;

    private static final int COLOR_PANEL_TOP = 0xF2161A20;
    private static final int COLOR_PANEL_BOTTOM = 0xF00A0C0F;
    private static final int COLOR_BORDER = 0xFF6B4A26;
    private static final int COLOR_ACCENT = 0xFFD9A441;
    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_TEXT_DIM = 0xFF9A9A9A;
    private static final int COLOR_ERROR = 0xFFFF6B6B;
    private static final int COLOR_TROUGH = 0xFF1E1E1E;
    private static final int COLOR_ROW = 0xFF141414;
    private static final int COLOR_ROW_HOVER = 0xFF262626;
    private static final int COLOR_ROW_SELECTED = 0xFF4A3C22;

    private final ClientMusicBoxManager manager;
    private final MusicBoxTarget target;

    /**
     * The panel is a fixed size, so on a small window or a large GUI scale the rows at the bottom
     * used to fall off the screen, which is where "Clear all" and "Done" live. Everything below is
     * measured in {@link #init()} and anchored to the bottom of the panel, clamped to the screen,
     * so the buttons stay reachable and the track list is what shrinks.
     */
    private int panelLeft;
    private int panelTop;
    private int listTop;
    private int listHeight;
    private int infoY;
    private int barY;
    private int buttonY;
    private int doneY;

    private EditBox urlBox;
    private TrackList trackList;
    private Button playButton;
    private Button repeatButton;
    private Button clearButton;
    private VolumeSlider volumeSlider;
    private String message = "";
    private int renderedTrackCount = -1;

    /**
     * @param stack   the held cube to edit, or null when editing a placed one
     * @param cubePos the placed cube to edit, or null when editing the held one
     */
    public MusicBoxScreen(ClientMusicBoxManager manager, ItemStack stack, BlockPos cubePos) {
        super(Component.translatable("screen.jblcube.title"));
        this.manager = manager;
        this.target = stack != null
                ? new HeldBoxTarget(manager, stack)
                : new PlacedCubeTarget(manager, cubePos);
    }

    @Override
    protected void init() {
        int left = panelLeft = (this.width - PANEL_WIDTH) / 2;
        int top = panelTop = (this.height - PANEL_HEIGHT) / 2;

        // Measured from the bottom up: on a screen too short for the whole panel the track list gives
        // up rows, never the buttons.
        int visibleBottom = Math.min(top + PANEL_HEIGHT, this.height);
        doneY = visibleBottom - PADDING - 18;
        buttonY = doneY - 22;
        barY = buttonY - 6 - BAR_HEIGHT;
        infoY = barY - 16;
        listTop = top + LIST_Y;
        listHeight = Math.max(ROW_HEIGHT,
                Math.min(ROW_HEIGHT * MAX_VISIBLE_ROWS, infoY - 6 - listTop));

        urlBox = new EditBox(this.font, left + PADDING, top + URL_Y, PANEL_WIDTH - PADDING * 2 - 66, 18,
                Component.translatable("screen.jblcube.url"));
        urlBox.setMaxLength(MAX_URL_LENGTH);
        urlBox.setHint(Component.translatable("screen.jblcube.url_hint"));
        addRenderableWidget(urlBox);
        setInitialFocus(urlBox);

        addRenderableWidget(Button.builder(Component.translatable("screen.jblcube.add"), button -> addTracks())
                .bounds(left + PANEL_WIDTH - PADDING - 62, top + URL_Y, 62, 18)
                .build());

        trackList = new TrackList(this.minecraft, PANEL_WIDTH - PADDING * 2, listHeight, listTop);
        trackList.setX(left + PADDING);
        addRenderableWidget(trackList);

        addRenderableWidget(Button.builder(Component.literal("<<"), button -> skip(-1))
                .bounds(left + PADDING, buttonY, 22, 18).build());
        playButton = addRenderableWidget(Button.builder(Component.empty(), button -> togglePlay())
                .bounds(left + PADDING + 25, buttonY, 84, 18).build());
        addRenderableWidget(Button.builder(Component.literal(">>"), button -> skip(1))
                .bounds(left + PADDING + 112, buttonY, 22, 18).build());
        repeatButton = addRenderableWidget(Button.builder(Component.empty(), button -> toggleRepeat())
                .bounds(left + PADDING + 138, buttonY, 52, 18).build());
        volumeSlider = addRenderableWidget(new VolumeSlider(left + PADDING + 194, buttonY,
                PANEL_WIDTH - PADDING * 2 - 194, 18, data().volume()));

        clearButton = addRenderableWidget(Button
                .builder(Component.translatable("screen.jblcube.clear_all"), button -> clearTracks())
                .bounds(left + PADDING, doneY, 78, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(left + PANEL_WIDTH - PADDING - 68, doneY, 68, 18).build());

        refreshList();
        refreshButtons();
    }

    private MusicBoxData data() {
        return target.data();
    }

    private void refreshList() {
        trackList.reset();
        List<MusicBoxTrack> tracks = data().tracks();
        for (int index = 0; index < tracks.size(); index++) {
            trackList.addTrack(index, tracks.get(index));
        }
        renderedTrackCount = tracks.size();
    }

    private void refreshButtons() {
        MusicBoxData data = data();
        playButton.setMessage(Component.translatable(data.playing() ? "screen.jblcube.pause" : "screen.jblcube.play"));
        playButton.active = data.trackCount() > 0;
        repeatButton.setMessage(Component.translatable(data.repeat() ? "screen.jblcube.repeat_on" : "screen.jblcube.repeat_off"));
        if (clearButton != null) {
            clearButton.active = data.trackCount() > 0;
        }
        if (volumeSlider != null) {
            volumeSlider.refreshValue(data.volume());
        }
    }

    private void applyLocal(MusicBoxData updated) {
        target.applyLocal(updated);
    }

    private void togglePlay() {
        MusicBoxData data = data();
        int index = data.currentIndex() >= 0 ? data.currentIndex() : 0;
        boolean play = !data.playing();
        MusicBoxData updated = data.selectTrack(index, play);
        applyLocal(updated);
        target.pushSelect(updated);
        refreshButtons();
        playClick();
    }

    private void skip(int direction) {
        MusicBoxData data = data();
        if (data.trackCount() == 0) {
            return;
        }
        MusicBoxData advanced = direction < 0 ? data.advance(true) : data.advance(false);
        if (advanced.currentIndex() == data.currentIndex() && !advanced.playing()) {
            return;
        }
        applyLocal(advanced);
        target.pushSelect(advanced);
        refreshButtons();
        playClick();
    }

    private void toggleRepeat() {
        MusicBoxData updated = data().withRepeat(!data().repeat());
        applyLocal(updated);
        target.pushSettings(updated);
        refreshButtons();
        playClick();
    }

    private void addTracks() {
        String input = urlBox.getValue();
        if (input == null || input.isBlank()) {
            message = Component.translatable("screen.jblcube.invalid_url").getString();
            return;
        }

        message = Component.translatable("screen.jblcube.resolving").getString();
        manager.runAsync(() -> {
            // Local resolution first; when the machine has no tools at all, the server expands it.
            List<MusicBoxTrack> tracks = NetworkAudio.INSTANCE.expand(input);
            Minecraft.getInstance().execute(() -> {
                if (tracks.isEmpty()) {
                    message = Component.translatable("screen.jblcube.no_tracks").getString();
                    return;
                }
                // Adding a link only loads it: the box waits for an explicit play, so a cube never
                // starts making noise by itself when it is placed or when a track is pasted.
                boolean first = data().trackCount() == 0;
                MusicBoxData updated = data().addTracks(tracks);
                if (first) {
                    updated = updated.selectTrack(0, false);
                }
                applyLocal(updated);
                target.pushAddTracks(updated, tracks, false);
                manager.prefetchTrack(updated.tracks().get(Math.max(0, updated.currentIndex())));
                urlBox.setValue("");
                message = "";
                refreshList();
                refreshButtons();
                playClick();
            });
        });
    }

    private void clearTracks() {
        if (data().trackCount() == 0) {
            return;
        }
        MusicBoxData updated = data().clearTracks();
        applyLocal(updated);
        target.pushClear(updated);
        refreshList();
        refreshButtons();
        playClick();
    }

    private void playClick() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                    .forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    /**
     * {@link Screen#render} already draws the background, so calling {@code super.render} after the
     * panel used to run the blur pass and the menu vignette a second time <em>on top of</em> our own
     * drawing, which is what made the whole screen look smeared. The background is therefore drawn
     * once, then the panel, then the widgets, then the overlay text.
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        if (data().trackCount() != renderedTrackCount) {
            refreshList();
        }

        renderPanel(graphics, panelLeft, panelTop);
        renderHeader(graphics, panelLeft, panelTop);

        for (Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }

        renderInfo(graphics, panelLeft, panelTop);
        renderProgress(graphics, panelLeft, panelTop);
    }

    private void renderPanel(GuiGraphics graphics, int left, int top) {
        graphics.fillGradient(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT,
                COLOR_PANEL_TOP, COLOR_PANEL_BOTTOM);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, COLOR_BORDER);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, COLOR_BORDER);
        graphics.fill(left, top, left + 1, top + PANEL_HEIGHT, COLOR_BORDER);
        graphics.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, COLOR_BORDER);
    }

    private void renderHeader(GuiGraphics graphics, int left, int top) {
        int titleX = left + (PANEL_WIDTH - this.font.width(this.title)) / 2;
        graphics.drawString(this.font, this.title, titleX, top + TITLE_Y, COLOR_ACCENT, true);

        Component count = Component.translatable("screen.jblcube.count", data().trackCount());
        graphics.drawString(this.font, count, left + PANEL_WIDTH - PADDING - this.font.width(count),
                top + TITLE_Y, COLOR_TEXT_DIM, true);

        graphics.hLine(left + PADDING, left + PANEL_WIDTH - PADDING, top + DIVIDER_Y, 0x30FFFFFF);
    }

    private void renderInfo(GuiGraphics graphics, int left, int top) {
        int y = infoY;

        // "message" is already translated text, manager.status() is a translation key.
        String statusKey = manager.status();
        if (!message.isEmpty()) {
            graphics.drawString(this.font, message, left + PADDING, y, COLOR_ERROR, true);
        } else if (loading()) {
            // Resolving a URL and opening the decoder take a moment; saying so beats a dead play button.
            Component status = Component.translatable("screen.jblcube.loading");
            graphics.drawString(this.font, status, left + PADDING, y, COLOR_ACCENT, true);
        } else if (statusKey != null && !statusKey.isEmpty()) {
            Component status = Component.translatable(statusKey);
            graphics.drawString(this.font, status, left + PADDING, y, COLOR_ACCENT, true);
        }

        Component time = timeLabel();
        graphics.drawString(this.font, time, left + PANEL_WIDTH - PADDING - this.font.width(time), y,
                COLOR_TEXT_DIM, true);
    }

    private boolean loading() {
        return target.loading();
    }

    private Component timeLabel() {
        MusicBoxTrack track = data().currentTrack();
        if (track == null) {
            return Component.translatable("screen.jblcube.no_track");
        }

        int duration = playbackDuration(track);
        int position = playbackPosition();
        return Component.literal(format(position / 1000) + " / " + format(Math.max(0, duration) / 1000));
    }

    /**
     * A placed cube is decoded independently by every client, so the length only comes from the
     * track metadata, while the held cube gets it from its live decoder.
     */
    private int playbackDuration(MusicBoxTrack track) {
        int duration = target.durationMillis();
        if (duration > 0) {
            return duration;
        }
        // An empty or half-cleared playlist has no track at all, so the bar must simply stay empty.
        return track == null ? -1 : track.durationMillis();
    }

    private int playbackPosition() {
        int position = target.positionMillis();
        return position > 0 ? position : data().positionMillis();
    }

    private void renderProgress(GuiGraphics graphics, int left, int top) {
        MusicBoxTrack track = data().currentTrack();
        int duration = playbackDuration(track);
        int position = playbackPosition();

        int barX = left + PADDING;
        int barWidth = PANEL_WIDTH - PADDING * 2;

        graphics.fill(barX, barY, barX + barWidth, barY + BAR_HEIGHT, COLOR_TROUGH);
        graphics.hLine(barX, barX + barWidth, barY, 0x33000000);
        graphics.hLine(barX, barX + barWidth, barY + BAR_HEIGHT - 1, 0x33000000);

        if (duration > 0 && track != null) {
            int filled = Math.max(0, Math.min(barWidth - 2, (int) ((long) (barWidth - 2) * position / duration)));
            if (filled > 0) {
                graphics.fillGradient(barX + 1, barY + 1, barX + 1 + filled, barY + BAR_HEIGHT - 1,
                        0xFFE8B65A, 0xFFB07C22);
            }
        } else if (loading()) {
            // Indeterminate sweep, so the bar reads as "working" instead of "broken".
            int sweep = Math.max(12, barWidth / 5);
            int span = barWidth - 2;
            int offset = (int) ((System.currentTimeMillis() / 12L) % span);
            int start = barX + 1 + Math.max(0, Math.min(span - sweep, offset - sweep));
            graphics.fillGradient(start, barY + 1, start + sweep, barY + BAR_HEIGHT - 1,
                    0x66E8B65A, 0x33E8B65A);
        }
    }

    private static String format(int seconds) {
        if (seconds < 0) {
            return "--:--";
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (urlBox != null && urlBox.isFocused()) {
                addTracks();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private final class VolumeSlider extends AbstractSliderButton {
        private float lastSent = Float.NaN;
        private long lastSentAt;

        private VolumeSlider(int x, int y, int width, int height, double value) {
            super(x, y, width, height, Component.empty(), value);
            updateMessage();
        }

        private void refreshValue(float volume) {
            if (Math.abs(this.value - volume) > 0.001D) {
                this.value = volume;
                updateMessage();
            }
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(Math.round(value * 100) + "%"));
        }

        @Override
        protected void applyValue() {
            float volume = (float) value;
            MusicBoxData updated = data().withVolume(volume);
            applyLocal(updated);

            // Dragging the slider fires this on every mouse step, so only tell the server when the
            // value really changed and at most a few times per second.
            long now = System.currentTimeMillis();
            if (Math.abs(volume - lastSent) < 0.01F && now - lastSentAt < 250L) {
                return;
            }
            lastSent = volume;
            lastSentAt = now;
            target.pushSettings(updated);
        }
    }

    private final class TrackList extends ObjectSelectionList<TrackList.TrackEntry> {
        private TrackList(net.minecraft.client.Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ROW_HEIGHT);
            centerListVertically = false;
        }

        private void reset() {
            clearEntries();
        }

        private void addTrack(int index, MusicBoxTrack track) {
            addEntry(new TrackEntry(index, track));
        }

        /**
         * Rows are laid out from {@code getY() + 4 - scrollAmount}, the same offset
         * {@link AbstractSelectionList} uses to pick the entry under the cursor. Deriving the row
         * index here instead of asking the entry for its own position is what makes the remove box
         * clickable: {@code Entry} has no y of its own, so the previous hit test fell back to the
         * list's y and only ever lined up with the first row.
         */
        private int rowAt(double mouseY) {
            return (int) (mouseY - (getY() + 4 - getScrollAmount())) / ROW_HEIGHT;
        }

        private int removeX() {
            return getX() + getWidth() - SCROLLBAR_SLOT - REMOVE_BOX_SIZE - 2;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !isMouseOver(mouseX, mouseY)) {
                return super.mouseClicked(mouseX, mouseY, button);
            }

            int row = rowAt(mouseY);
            if (row < 0 || row >= getItemCount()) {
                return false;
            }

            if (mouseX >= removeX() && mouseX <= removeX() + REMOVE_BOX_SIZE
                    && mouseY >= getY() + 4 - getScrollAmount() + row * ROW_HEIGHT + 3
                    && mouseY <= getY() + 4 - getScrollAmount() + row * ROW_HEIGHT + ROW_HEIGHT - 3) {
                removeRow(row);
                return true;
            }

            selectRow(row);
            return true;
        }

        private void removeRow(int row) {
            MusicBoxData updated = data().removeTrack(row);
            applyLocal(updated);
            target.pushRemoveTrack(updated, row);
            refreshList();
            refreshButtons();
            playClick();
        }

private void selectRow(int row) {
                // Selecting a row must not start playback: play only happens on the play button.
                MusicBoxData updated = data().selectTrack(row, false);
            applyLocal(updated);
            target.pushSelect(updated);
            refreshButtons();
            playClick();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY() - 3, getX() + getWidth(), getBottom() + 3, COLOR_ROW);
            graphics.fill(getX(), getY() - 3, getX() + getWidth(), getY() - 2, 0x33000000);
            graphics.fill(getX(), getBottom() + 2, getX() + getWidth(), getBottom() + 3, 0x33000000);
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
        }

        private final class TrackEntry extends ObjectSelectionList.Entry<TrackEntry> {
            private final int index;
            private final MusicBoxTrack track;

            private TrackEntry(int index, MusicBoxTrack track) {
                this.index = index;
                this.track = track;
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                return false;
            }

            @Override
            public void render(GuiGraphics graphics, int rowIndex, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovered, float partialTick) {
                MusicBoxData current = data();
                boolean selected = current.currentIndex() == index;
                boolean playing = selected && current.playing();

                int background = playing ? COLOR_ROW_SELECTED : selected ? 0xFF32281A : hovered ? COLOR_ROW_HOVER : 0x00000000;
                if (background != 0) {
                    graphics.fill(left, top, left + width, top + height, background);
                }
                graphics.hLine(left, left + width, top + height - 1, 0x22FFFFFF);

                int textX = left + 6;
                if (playing) {
                    // Little "now playing" marker so the active row is obvious at a glance.
                    graphics.fill(textX, top + 6, textX + 4, top + 8, COLOR_ACCENT);
                    graphics.fill(textX + 4, top + 5, textX + 6, top + 9, COLOR_ACCENT);
                    textX += 12;
                }

                String label = (index + 1) + ". " + track.title();
                graphics.drawString(font, font.plainSubstrByWidth(label, width - (textX - left) - 24), textX,
                        top + 5, selected ? 0xFFFFFFFF : COLOR_TEXT, true);

                int boxX = removeX();
                int boxY = top + 3;
                boolean removeHovered = mouseX >= boxX && mouseX <= boxX + REMOVE_BOX_SIZE
                        && mouseY >= boxY && mouseY <= boxY + REMOVE_BOX_SIZE;
                graphics.fill(boxX, boxY, boxX + REMOVE_BOX_SIZE, boxY + REMOVE_BOX_SIZE,
                        removeHovered ? 0xCC8B3A3A : 0x77333333);
                graphics.drawString(font, "x", boxX + 4, boxY + 4, removeHovered ? 0xFFFFFFFF : 0xFFBBBBBB, false);
            }

            @Override
            public Component getNarration() {
                return Component.literal(track.title());
            }
        }
    }

    public static boolean isMusicBox(ItemStack stack) {
        return ModItems.isCube(stack);
    }
}