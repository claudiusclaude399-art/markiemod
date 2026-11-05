package dev.pvpmod.gui;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;

public class FloatSlider extends SliderWidget {
    private final String label;
    private final float min, max;
    private final Consumer<Float> onChange;

    public FloatSlider(int x, int y, int w, int h, String label, float min, float max,
                       float current, Consumer<Float> onChange) {
        super(x, y, w, h, Text.empty(), (Math.max(min, Math.min(max, current)) - min) / (max - min));
        this.label = label;
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        updateMessage();
    }

    private float actual() {
        return min + (float) this.value * (max - min);
    }

    @Override
    protected void updateMessage() {
        if (label == null) return;
        setMessage(Text.literal(label + ": " + String.format("%.2f", actual())));
    }

    @Override
    protected void applyValue() {
        onChange.accept(actual());
    }
}
