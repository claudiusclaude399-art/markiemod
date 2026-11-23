package dev.clicksounds.audio;

/**
 * One click: the press sound and (optionally) the release sound of the same click.
 * {@code name} is the file name without extension (null for a fresh recording not saved yet).
 */
public record Take(String name, float[] press, float[] release) {
    public Take(float[] press, float[] release) {
        this(null, press, release);
    }

    public float peak() {
        return Math.max(Wav.peak(press), release == null ? 0f : Wav.peak(release));
    }

    /** Where the release starts when previewing the take on its own (frames at {@link AudioEngine#RATE}). */
    public int releaseOffset() {
        return Math.max((int) (AudioEngine.RATE * 0.09f), press.length + (int) (AudioEngine.RATE * 0.01f));
    }

    /** Length of a preview (press, gap, release) in frames. */
    public int timelineLength() {
        return release == null ? press.length : releaseOffset() + release.length;
    }

    public float lengthMs() {
        return timelineLength() * 1000f / AudioEngine.RATE;
    }
}
