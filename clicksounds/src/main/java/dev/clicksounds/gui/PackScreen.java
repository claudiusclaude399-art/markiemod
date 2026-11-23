package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.Genre;
import dev.clicksounds.audio.Packs;
import dev.clicksounds.audio.SampleBank;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Locale;

import static dev.clicksounds.gui.ClickSoundsScreen.*;

/**
 * One device's clickpack: a tab per genre (clicks / soft clicks / hard clicks), each showing its
 * takes as sound waves to play, redo or record.
 */
public class PackScreen extends Screen {
    private final Screen parent;
    private final boolean mouse;
    private final SampleBank bank;
    private Genre genre;
    private int page;
    private String status = "";
    private int statusColor = GRAY;
    private int top;
    private boolean confirmDelete;
    private static final int ROW = 22;

    public PackScreen(Screen parent, boolean mouse, Genre genre) {
        super(Text.literal(mouse ? "Mouse clickpack" : "Keyboard clickpack"));
        this.parent = parent;
        this.mouse = mouse;
        this.bank = mouse ? ClickSoundsClient.MOUSE : ClickSoundsClient.KEYBOARD;
        this.genre = genre;
    }

    public void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    public Genre genre() {
        return genre;
    }

    private float volume() {
        return Math.max(0.3f, mouse ? ClickConfig.mouseVolume : ClickConfig.keyboardVolume);
    }

    private int pages() {
        return Math.max(1, (bank.size(genre) + SampleBank.TAKES - 1) / SampleBank.TAKES);
    }

    @Override
    protected void init() {
        confirmDelete = false;
        page = Math.min(page, pages() - 1);
        int cx = this.width / 2;
        top = Math.max(28, this.height / 2 - 92);

        // genre tabs
        int tx = cx - 155;
        for (Genre g : Genre.values()) {
            int n = bank.size(g);
            String label = (g == genre ? "> " : "") + g.title + " (" + n + ")" + (g == genre ? " <" : "");
            ButtonWidget tab = ButtonWidget.builder(Text.literal(label), b -> {
                genre = g;
                page = 0;
                status = "";
                clearAndInit();
            }).dimensions(tx, top, 100, 20).tooltip(Tooltip.of(Text.literal(g.description
                + "\nFolders: " + g.clicksFolder + "/ and " + g.releasesFolder + "/"))).build();
            tab.active = g != genre;
            addDrawableChild(tab);
            tx += 105;
        }

        // takes of this genre as sound waves
        int gridTop = top + 24;
        int size = bank.size(genre);
        for (int i = 0; i < SampleBank.TAKES; i++) {
            int idx = page * SampleBank.TAKES + i;
            int x = i < 5 ? cx - 155 : cx + 5;
            int y = gridTop + (i % 5) * ROW;
            boolean has = idx < size;
            addDrawableChild(new WaveformButton(x, y, 100, 20, bank, genre, idx, this::volume));
            int slot = has ? idx : size;
            ButtonWidget redo = ButtonWidget.builder(Text.literal(has ? "Redo" : "Record"),
                b -> client.setScreen(new RecordScreen(this, mouse, genre, slot, this::setStatus)))
                .dimensions(x + 104, y, 46, 20).build();
            redo.active = has || idx == size; // only the first empty slot can be recorded into
            addDrawableChild(redo);
        }

        int y = gridTop + 5 * ROW + 4;
        addDrawableChild(ButtonWidget.builder(Text.literal("Play all"), b -> {
            int ms = bank.previewAll(genre, volume());
            setStatus("Playing all " + bank.size(genre) + " " + genre.title.toLowerCase(Locale.ROOT)
                + String.format(Locale.ROOT, " (%.1f s)", ms / 1000f), GRAY);
        }).dimensions(cx - 155, y, 100, 20).build()).active = size > 0;
        addDrawableChild(ButtonWidget.builder(Text.literal("Record 10"),
            b -> client.setScreen(new RecordScreen(this, mouse, genre, -1, this::setStatus)))
            .dimensions(cx - 50, y, 100, 20).tooltip(Tooltip.of(Text.literal(
                "Record 10 new " + genre.title.toLowerCase(Locale.ROOT) + " (replaces these)"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Delete all"), b -> {
            if (!confirmDelete) {
                confirmDelete = true;
                b.setMessage(Text.literal("Sure? Click again"));
                return;
            }
            bank.clear(genre);
            setStatus("Deleted all " + genre.title.toLowerCase(Locale.ROOT) + " in '" + bank.pack() + "'", YELLOW);
            clearAndInit();
        }).dimensions(cx + 55, y, 100, 20).build()).active = size > 0;

        y += 24;
        if (pages() > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { page = (page + pages() - 1) % pages(); clearAndInit(); })
                .dimensions(cx - 155, y, 20, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { page = (page + 1) % pages(); clearAndInit(); })
                .dimensions(cx + 135, y, 20, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("New pack"), b -> {
            String name = Packs.create();
            if (mouse) ClickConfig.mousePack = name; else ClickConfig.keyboardPack = name;
            ClickSoundsClient.loadPacks();
            setStatus("Made pack '" + name + "' - rename its folder any time", GREEN);
            clearAndInit();
        }).dimensions(cx - 130, y, 125, 20).tooltip(Tooltip.of(Text.literal(
            "Start an empty pack and switch the " + (mouse ? "mouse" : "keyboard") + " to it"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
            .dimensions(cx + 5, y, 125, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal(this.title.getString() + ": " + bank.pack()), cx, Math.max(4, top - 24), WHITE);
        String sub = genre.description;
        if (genre == Genre.SOFT) sub += " (< " + ClickConfig.softMs + " ms apart)" + (ClickConfig.softClicks ? "" : " - OFF");
        if (genre == Genre.HARD) sub += " (> " + ClickConfig.hardMs + " ms pause)" + (ClickConfig.hardClicks ? "" : " - OFF");
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(sub, this.width - 8), cx, Math.max(16, top - 12), GRAY);

        // one footer line: counts (or the latest status) + colour legend
        int bottom = top + 24 + 5 * ROW + 4 + 24 + 20;
        int ly = Math.min(this.height - 22, bottom + 4);
        String left;
        int leftColor;
        if (!status.isEmpty()) { left = status; leftColor = statusColor; }
        else {
            int releases = bank.releaseCount(genre);
            left = (pages() > 1 ? "Page " + (page + 1) + "/" + pages() + "  " : "")
                + bank.size(genre) + " clicks, " + releases + " release" + (releases == 1 ? "" : "s");
            leftColor = DARK_GRAY;
        }
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(left, this.width - 8), cx, ly, leftColor);
        String a = "\u25A0 click", b2 = "\u25A0 release";
        int wa = this.textRenderer.getWidth(a), wb = this.textRenderer.getWidth(b2), lx = cx - (wa + 12 + wb) / 2;
        ctx.drawTextWithShadow(this.textRenderer, a, lx, ly + 11, Waveforms.PRESS);
        ctx.drawTextWithShadow(this.textRenderer, b2, lx + wa + 12, ly + 11, Waveforms.RELEASE);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
