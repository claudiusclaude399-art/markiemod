package dev.clicksounds.gui;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/** 0% - 200% volume slider. */
public class VolumeSlider extends SliderWidget {
    private final String label;
    private final Consumer<Float> onChange;

    public VolumeSlider(int x, int y, int w, int h, String label, float current, Consumer<Float> onChange) {
        super(x, y, w, h, Text.empty(), Math.max(0f, Math.min(2f, current)) / 2f);
        this.label = label;
        this.onChange = onChange;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        if (label == null) return;
        setMessage(Text.literal(label + ": " + Math.round(this.value * 200) + "%"));
    }

    @Override
    protected void applyValue() {
        onChange.accept((float) (this.value * 2.0));
    }
}
