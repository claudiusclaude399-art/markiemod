package dev.markiesdetector.glass;

/** Critically-ish damped spring, the motion behind every animation in the glass UI. */
public final class Spring {
    private float value, velocity, target;
    private final float stiffness, damping;

    public Spring(float initial) { this(initial, 170f, 22f); }

    public Spring(float initial, float stiffness, float damping) {
        this.value = initial;
        this.target = initial;
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public Spring to(float t) { this.target = t; return this; }

    public Spring snap(float v) { value = v; target = v; velocity = 0; return this; }

    public float get() { return value; }

    public float target() { return target; }

    public float update(float dt) {
        dt = Math.min(dt, 0.1f);
        int steps = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float f = -stiffness * (value - target) - damping * velocity;
            velocity += f * h;
            value += velocity * h;
        }
        if (Math.abs(value - target) < 0.0005f && Math.abs(velocity) < 0.0005f) { value = target; velocity = 0; }
        return value;
    }

    public static float easeOutCubic(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }
}
