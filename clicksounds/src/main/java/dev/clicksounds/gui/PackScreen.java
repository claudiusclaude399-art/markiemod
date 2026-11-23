package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.Genre;
import dev.clicksounds.audio.SampleBank;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

import static dev.clicksounds.gui.ClickSoundsScreen.*;

/**
 * One device's clickpack. Keyboard: a row to pick All keys / W / A / S / D / any other key.
 * Then a tab per genre (clicks / soft clicks / hard clicks), each showing its takes as sound
 * waves to play, redo or record.
 */
public class PackScreen extends Screen {
    private static final int ROW = 22;
    private static final String[] WASD = {"w", "a", "s", "d"};

    private final Screen parent;
    private final boolean mouse;
    private final SampleBank bank;
    /** "" = shared sounds (all keys), else one key's own sounds. Always "" for the mouse. */
    private String scope;
    private Genre genre;
    private int page;
    private String status = "";
    private int statusColor = GRAY;
    private int top;
    private boolean confirmDelete;
    private boolean pickingKey;

    public PackScreen(Screen parent, boolean mouse, Genre genre) {
        this(parent, mouse, "", genre);
    }

    public PackScreen(Screen parent, boolean mouse, String scope, Genre genre) {
        super(Text.literal(mouse ? "Mouse clickpack" : "Keyboard clickpack"));
        this.parent = parent;
        this.mouse = mouse;
        this.bank = mouse ? ClickSoundsClient.MOUSE : ClickSoundsClient.KEYBOARD;
        this.scope = mouse || scope == null ? "" : scope;
        this.genre = genre;
    }

    public void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    public Genre genre() { return genre; }
    public String scope() { return scope; }

    private float volume() {
        return Math.max(0.3f, mouse ? ClickConfig.mouseVolume : ClickConfig.keyboardVolume);
    }

    private int pages() {
        return Math.max(1, (bank.size(scope, genre) + SampleBank.TAKES - 1) / SampleBank.TAKES);
    }

    @Override
    protected void init() {
        confirmDelete = false;
        page = Math.min(page, pages() - 1);
        int cx = this.width / 2;
        int rows = mouse ? 0 : 1; // the keyboard has the key row on top
        top = Math.max(28, this.height / 2 - 92 - rows * 12);
        int y = top;

        if (!mouse) {
            y = keyRow(cx, y);
            y += 24;
        }

        // genre tabs (with page arrows at the ends when a genre has more than 10 takes)
        boolean paged = pages() > 1;
        int tabW = paged ? 86 : 100, gap = paged ? 3 : 5;
        int tx = paged ? cx - 155 + 23 : cx - 155;
        if (paged) {
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { page = (page + pages() - 1) % pages(); clearAndInit(); })
                .dimensions(cx - 155, y, 20, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { page = (page + 1) % pages(); clearAndInit(); })
                .dimensions(cx + 135, y, 20, 20).build());
        }
        for (Genre g : Genre.values()) {
            int n = bank.size(scope, g);
            String label = (g == genre ? "> " : "") + (paged ? g.title.replace(" clicks", "") : g.title) + " (" + n + ")" + (g == genre ? " <" : "");
            ButtonWidget tab = ButtonWidget.builder(Text.literal(label), b -> {
                genre = g;
                page = 0;
                status = "";
                clearAndInit();
            }).dimensions(tx, y, tabW, 20).tooltip(Tooltip.of(Text.literal(g.description
                + "\nFolders: " + folder() + g.clicksFolder + "/ and " + g.releasesFolder + "/"))).build();
            tab.active = g != genre;
            addDrawableChild(tab);
            tx += tabW + gap;
        }

        // takes of this key + genre as sound waves
        int gridTop = y + 24;
        int size = bank.size(scope, genre);
        for (int i = 0; i < SampleBank.TAKES; i++) {
            int idx = page * SampleBank.TAKES + i;
            int x = i < 5 ? cx - 155 : cx + 5;
            int ry = gridTop + (i % 5) * ROW;
            boolean has = idx < size;
            addDrawableChild(new WaveformButton(x, ry, 100, 20, bank, scope, genre, idx, this::volume));
            int slot = has ? idx : size;
            ButtonWidget redo = ButtonWidget.builder(Text.literal(has ? "Redo" : "Record"),
                b -> client.setScreen(new RecordScreen(this, mouse, scope, genre, slot, this::setStatus)))
                .dimensions(x + 104, ry, 46, 20).build();
            redo.active = has || idx == size; // only the first empty slot can be recorded into
            addDrawableChild(redo);
        }

        y = gridTop + 5 * ROW + 4;
        int bw = 74;
        addDrawableChild(ButtonWidget.builder(Text.literal("Play all"), b -> {
            int ms = bank.previewAll(scope, genre, volume());
            setStatus("Playing all " + bank.size(scope, genre) + " " + what() + String.format(Locale.ROOT, " (%.1f s)", ms / 1000f), GRAY);
        }).dimensions(cx - 155, y, bw, 20).build()).active = size > 0;
        addDrawableChild(ButtonWidget.builder(Text.literal("Record 10"),
            b -> client.setScreen(new RecordScreen(this, mouse, scope, genre, -1, this::setStatus)))
            .dimensions(cx - 155 + (bw + 4), y, bw, 20).tooltip(Tooltip.of(Text.literal(
                "Record 10 new " + what() + " (replaces these)"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Delete all"), b -> {
            if (!confirmDelete) {
                confirmDelete = true;
                b.setMessage(Text.literal("Sure?"));
                return;
            }
            bank.clear(scope, genre);
            setStatus("Deleted all " + what() + " in '" + bank.pack() + "'", YELLOW);
            clearAndInit();
        }).dimensions(cx - 155 + 2 * (bw + 4), y, bw, 20).build()).active = size > 0;
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
            .dimensions(cx - 155 + 3 * (bw + 4), y, bw, 20).build());
    }

    /** All keys | W | A | S | D | Other key... */
    private int keyRow(int cx, int y) {
        int x = cx - 155;
        x = keyButton(x, y, 70, "", "All keys", "Shared sounds: used by every key that has no sounds of its own");
        for (String k : WASD) x = keyButton(x, y, 34, k, k.toUpperCase(Locale.ROOT), null);
        boolean otherSelected = !scope.isEmpty() && !List.of(WASD).contains(scope);
        String label = pickingKey ? "Press a key..." : otherSelected ? "> " + SampleBank.keyLabel(scope) + " <" : "Other key...";
        ButtonWidget other = ButtonWidget.builder(Text.literal(this.textRenderer.trimToWidth(label, 90)), b -> {
            pickingKey = true;
            setStatus("Press the key you want to give its own sounds (Esc to cancel)", YELLOW);
            clearAndInit();
        }).dimensions(x, y, cx + 155 - x, 20).tooltip(Tooltip.of(Text.literal(
            "Give any other key (Space, Shift, E ...) its own sounds" + ownKeysLine()))).build();
        other.active = !pickingKey && !otherSelected;
        addDrawableChild(other);
        return y;
    }

    private int keyButton(int x, int y, int w, String key, String label, String tip) {
        boolean selected = scope.equals(key);
        int n = 0;
        for (Genre g : Genre.values()) n += bank.size(key, g);
        ButtonWidget b = ButtonWidget.builder(Text.literal(selected ? "> " + label + " <" : label), btn -> {
            scope = key;
            page = 0;
            status = "";
            clearAndInit();
        }).dimensions(x, y, w, 20).tooltip(Tooltip.of(Text.literal(tip != null ? tip
            : n > 0 ? label + " has its own sounds (" + bank.summary(key) + ")"
            : label + " has no own sounds yet: it uses the All keys sounds"))).build();
        b.active = !selected;
        addDrawableChild(b);
        return x + w + 4;
    }

    private String ownKeysLine() {
        List<String> keys = bank.ownKeys();
        if (keys.isEmpty()) return "";
        StringBuilder b = new StringBuilder("\nKeys with own sounds:");
        for (String k : keys) b.append(' ').append(SampleBank.keyLabel(k));
        return b.toString();
    }

    private String folder() {
        return scope.isEmpty() ? "" : "keys/" + scope + "/";
    }

    private String what() {
        return (scope.isEmpty() ? "" : SampleBank.keyLabel(scope) + " ") + genre.title.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (pickingKey) {
            pickingKey = false;
            if (input.key() != GLFW.GLFW_KEY_ESCAPE) {
                String k = ClickSoundsClient.keyName(input);
                if (k == null) setStatus("That key can't have its own sounds", RED);
                else {
                    scope = k;
                    page = 0;
                    setStatus(bank.size(k, Genre.NORMAL) > 0 ? "" : SampleBank.keyLabel(k) + " has no own sounds yet - hit Record 10", GRAY);
                }
            } else setStatus("", GRAY);
            clearAndInit();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal(this.title.getString() + ": " + bank.pack()), cx, Math.max(4, top - 24), WHITE);
        String sub;
        if (!scope.isEmpty() && bank.size(scope, Genre.NORMAL) + bank.size(scope, Genre.SOFT) + bank.size(scope, Genre.HARD) == 0)
            sub = SampleBank.keyLabel(scope) + " has no own sounds yet, so it uses the All keys sounds";
        else {
            sub = genre.description;
            if (genre == Genre.SOFT) sub += " (< " + ClickConfig.softMs + " ms apart)" + (ClickConfig.softClicks ? "" : " - OFF");
            if (genre == Genre.HARD) sub += " (> " + ClickConfig.hardMs + " ms pause)" + (ClickConfig.hardClicks ? "" : " - OFF");
            if (!scope.isEmpty()) sub = SampleBank.keyLabel(scope) + ": " + sub;
        }
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(sub, this.width - 8), cx, Math.max(16, top - 12), GRAY);

        // one footer line: counts (or the latest status) + colour legend
        int bottom = top + (mouse ? 0 : 24) + 24 + 5 * ROW + 4 + 20;
        int ly = Math.min(this.height - 22, bottom + 4);
        String left;
        int leftColor;
        if (!status.isEmpty()) { left = status; leftColor = statusColor; }
        else {
            int releases = bank.releaseCount(scope, genre);
            left = (pages() > 1 ? "Page " + (page + 1) + "/" + pages() + "  " : "")
                + bank.size(scope, genre) + " clicks, " + releases + " release" + (releases == 1 ? "" : "s");
            leftColor = DARK_GRAY;
        }
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(left, this.width - 8), cx, ly, leftColor);
        String a = "■ click", b2 = "■ release";
        int wa = this.textRenderer.getWidth(a), wb = this.textRenderer.getWidth(b2), lx = cx - (wa + 12 + wb) / 2;
        ctx.drawTextWithShadow(this.textRenderer, a, lx, ly + 11, Waveforms.PRESS);
        ctx.drawTextWithShadow(this.textRenderer, b2, lx + wa + 12, ly + 11, Waveforms.RELEASE);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
