package rs117.hd.config;

public enum AntiAliasingMode {
    DISABLED(1),
    MSAA_2X(2),
    MSAA_4X(4),
    MSAA_8X(8),
    MSAA_16X(16);

    private final int samples;

    AntiAliasingMode(int samples) {
        this.samples = samples;
    }

    public int getSamples() {
        return samples;
    }
}
