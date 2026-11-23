package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.Packs;
import dev.clicksounds.audio.SampleBank;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.clicksounds.gui.ClickSoundsScreen.*;

/** Lists every clickpack so you can preview one and switch the mouse (or keyboard) to it. */
public class PackPickerScreen extends Screen {
    private static final int ROW = 22;
    private int perPage = 7;

    private final Screen parent;
    private boolean mouse;
    private int page = -1; // -1 = open on the page holding the active pack
    private int top;
    private String status = "";
    private int statusColor = GRAY;
    /** Packs loaded just for previewing, so trying one doesn't switch to it. */
    private final Map<String, SampleBank> previewBanks = new HashMap<>();

    public PackPickerScreen(Screen parent, boolean mouse) {
        super(Text.empty());
        this.parent = parent;
        this.mouse = mouse;
    }

    private SampleBank bank() {
        return mouse ? ClickSoundsClient.MOUSE : ClickSoundsClient.KEYBOARD;
    }

    private String device() {
        return mouse ? Packs.MOUSE : Packs.KEYBOARD;
    }

    @Override
    protected void init() {
        List<String> fresh = Packs.importZips(); // pick up .zip packs dropped in while the game runs
        if (!fresh.isEmpty()) setStatus("Imported " + String.join(", ", fresh) + " from .zip", GREEN);
        List<String> packs = Packs.list();
        int cx = this.width / 2;
        top = Math.max(42, this.height / 2 - 96);
        // as many rows as fit above the two button rows and the status line
        perPage = Math.max(3, Math.min(7, (this.height - top - 4 - 8 - 72 - 12) / ROW));
        int pages = Math.max(1, (packs.size() + perPage - 1) / perPage);
        // open on the page holding the active pack
        if (page < 0 || page >= pages) page = Math.max(0, packs.indexOf(bank().pack()) / perPage);

        // mouse / keyboard tabs
        ButtonWidget mTab = ButtonWidget.builder(Text.literal(mouse ? "> Mouse <" : "Mouse"), b -> switchDevice(true))
            .dimensions(cx - 155, top - 24, 150, 20).build();
        mTab.active = !mouse;
        addDrawableChild(mTab);
        ButtonWidget kTab = ButtonWidget.builder(Text.literal(!mouse ? "> Keyboard <" : "Keyboard"), b -> switchDevice(false))
            .dimensions(cx + 5, top - 24, 150, 20).build();
        kTab.active = mouse;
        addDrawableChild(kTab);

        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= packs.size()) break;
            String name = packs.get(idx);
            String summary = Packs.summary(name, device());
            boolean current = name.equals(bank().pack());
            int y = top + 4 + i * ROW;

            ButtonWidget play = ButtonWidget.builder(Text.literal("▶"), b -> preview(name))
                .dimensions(cx - 155, y, 20, 20).tooltip(Tooltip.of(Text.literal("Hear '" + name + "' without switching"))).build();
            play.active = !summary.equals("empty");
            addDrawableChild(play);

            String label = this.textRenderer.trimToWidth((current ? "✔ " : "") + name, 150) + "  -  " + summary;
            label = this.textRenderer.trimToWidth(label, 276);
            ButtonWidget pick = ButtonWidget.builder(Text.literal(label), b -> select(name))
                .dimensions(cx - 131, y, 286, 20).tooltip(Tooltip.of(Text.literal(current
                    ? "In use for the " + device()
                    : "Use '" + name + "' for the " + device() + "\n" + summary))).build();
            addDrawableChild(pick);
        }

        int y = top + 4 + perPage * ROW + 4;
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> { page = (page + pages - 1) % pages; clearAndInit(); })
                .dimensions(cx - 155, y, 20, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> { page = (page + 1) % pages; clearAndInit(); })
                .dimensions(cx + 135, y, 20, 20).build());
        }
        String other = mouse ? "keyboard" : "mouse";
        addDrawableChild(ButtonWidget.builder(Text.literal("Use for " + other + " too"), b -> {
            ClickSoundsClient.setPack(!mouse, bank().pack());
            setStatus("The " + other + " now uses '" + bank().pack() + "' too", GREEN);
        }).dimensions(cx - 131, y, 128, 20).tooltip(Tooltip.of(Text.literal(
            "Switch the " + other + " to '" + bank().pack() + "' as well"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("New pack"), b -> {
            String name = Packs.create();
            select(name);
            setStatus("Made '" + name + "' - record into it from its clickpack screen", GREEN);
        }).dimensions(cx + 3, y, 128, 20).build());
        y += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Rename..."), b -> client.setScreen(new RenamePackScreen(this, bank().pack())))
            .dimensions(cx - 131, y, 128, 20).tooltip(Tooltip.of(Text.literal("Rename '" + bank().pack() + "'"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Share as .zip"), b -> {
            try {
                java.nio.file.Path zip = Packs.export(bank().pack());
                setStatus("Saved " + zip.getFileName() + " in config/clicksounds/exported", GREEN);
                Util.getOperatingSystem().open(zip.getParent().toFile());
            } catch (Exception e) {
                setStatus("Couldn't export: " + e.getMessage(), RED);
            }
        }).dimensions(cx + 3, y, 128, 20).tooltip(Tooltip.of(Text.literal(
            "Zip '" + bank().pack() + "' so you can send it to someone. They drop the .zip in their packs folder."))).build());
        y += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Open packs folder"), b -> {
            try {
                Files.createDirectories(Packs.packsDir());
                Util.getOperatingSystem().open(Packs.packsDir().toFile());
            } catch (Exception e) {
                setStatus("Couldn't open folder: " + e.getMessage(), RED);
            }
        }).dimensions(cx - 131, y, 128, 20).tooltip(Tooltip.of(Text.literal(
            "Drop downloaded clickpacks here (a folder or a .zip each), then come back to this screen."))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(cx + 3, y, 128, 20).build());
    }

    private void switchDevice(boolean toMouse) {
        mouse = toMouse;
        page = -1;
        status = "";
        clearAndInit();
    }

    private void select(String name) {
        ClickSoundsClient.setPack(mouse, name);
        previewBanks.remove(name);
        setStatus((mouse ? "Mouse" : "Keyboard") + " now uses '" + bank().pack() + "' (" + bank().summary() + ")", GREEN);
        clearAndInit();
    }

    private void preview(String name) {
        SampleBank b = name.equals(bank().pack()) ? bank() : previewBanks.computeIfAbsent(name, n -> {
            SampleBank tmp = new SampleBank(device());
            tmp.load(n);
            return tmp;
        });
        float vol = Math.max(0.3f, mouse ? ClickConfig.mouseVolume : ClickConfig.keyboardVolume);
        b.previewRandom(vol, ClickConfig.variation, ClickConfig.releaseSounds);
    }

    void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer, "Choose a " + device() + " clickpack", cx, Math.max(4, top - 38), WHITE);

        List<String> packs = Packs.list();
        if (packs.isEmpty()) ctx.drawCenteredTextWithShadow(this.textRenderer, "No packs yet", cx, top + 40, GRAY);

        int bottom = top + 4 + perPage * ROW + 4 + 72;
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(status, this.width - 8), cx,
            Math.min(this.height - 11, bottom + 2), statusColor);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
