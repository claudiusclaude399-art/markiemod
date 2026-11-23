package dev.clicksounds.gui;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

/** Slider for a time in milliseconds (log scale, so short times are easy to pick). */
public class MsSlider extends SliderWidget {
    private final String label;
    private final int min, max;
    private final IntConsumer onChange;

    public MsSlider(int x, int y, int w, int h, String label, int min, int max, int current, IntConsumer onChange) {
        super(x, y, w, h, Text.empty(), Math.log((double) Math.max(min, Math.min(max, current)) / min) / Math.log((double) max / min));
        this.label = label;
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        updateMessage();
    }

    private int ms() {
        int v = (int) Math.round(min * Math.pow((double) max / min, this.value));
        return v >= 1000 ? Math.round(v / 50f) * 50 : Math.round(v / 5f) * 5;
    }

    @Override
    protected void updateMessage() {
        if (label == null) return;
        setMessage(Text.literal(label + ": " + ms() + " ms"));
    }

    @Override
    protected void applyValue() {
        onChange.accept(ms());
    }
}
