package dev.clicksounds.audio;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/** Lists / opens specific microphones and output devices by name. */
public final class Devices {
    private Devices() {}

    public static List<String> microphones() { return list(TargetDataLine.class); }
    public static List<String> outputs() { return list(SourceDataLine.class); }

    private static List<String> list(Class<? extends Line> type) {
        List<String> names = new ArrayList<>();
        try {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer m = AudioSystem.getMixer(info);
                if (m.isLineSupported(new Line.Info(type)) && !names.contains(info.getName())) names.add(info.getName());
            }
        } catch (Exception ignored) {}
        return names;
    }

    /** Opens a line of the given format on the named device, or the system default if the name is empty / not found. */
    public static Line get(String device, DataLine.Info info) throws LineUnavailableException {
        if (device != null && !device.isEmpty()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (!mi.getName().equals(device)) continue;
                Mixer m = AudioSystem.getMixer(mi);
                if (m.isLineSupported(info)) return m.getLine(info);
            }
        }
        if (!AudioSystem.isLineSupported(info)) throw new LineUnavailableException("format not supported");
        return AudioSystem.getLine(info);
    }
}
