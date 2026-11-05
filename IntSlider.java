package dev.pvpmod.gui;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

public class IntSlider extends SliderWidget {
    private final String label;
    private final int min, max;
    private final IntConsumer onChange;

    public IntSlider(int x, int y, int w, int h, String label, int min, int max, int current, IntConsumer onChange) {
        super(x, y, w, h, Text.empty(), (Math.max(min, Math.min(max, current)) - min) / (double) (max - min));
        this.label = label;
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        updateMessage();
    }

    private int actual() {
        return min + (int) Math.round(this.value * (max - min));
    }

    @Override
    protected void updateMessage() {
        if (label == null) return;
        setMessage(Text.literal(label + " " + actual()));
    }

    @Override
    protected void applyValue() {
        onChange.accept(actual());
    }
}
