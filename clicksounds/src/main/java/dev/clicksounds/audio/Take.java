package dev.clicksounds.audio;

/** One recorded click: the press sound and (optionally) the release sound of the same click. */
public record Take(float[] press, float[] release) {
    public float peak() {
        return Math.max(Wav.peak(press), release == null ? 0f : Wav.peak(release));
    }
}
