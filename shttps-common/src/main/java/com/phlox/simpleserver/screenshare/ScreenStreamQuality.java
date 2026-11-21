package com.phlox.simpleserver.screenshare;

import org.jetbrains.annotations.Nullable;

/**
 * How much picture a screen stream carries: the size it is scaled down to and the bit rate that
 * size is given.
 * <p>
 * {@link #LOW} is what every build served before there was a choice, and is still what a client
 * that asks for nothing gets - a page from an older version, cached in a browser, must not suddenly
 * be sent four times the data. The other two exist for the case that level was never good at: a
 * large desktop watched over a local network, where 1280 pixels across turns text into mush and
 * the link could carry ten times as much.
 * <p>
 * The order of the constants is the order of quality, and code relies on it.
 */
public enum ScreenStreamQuality {
    LOW("low", 1280, 10, 4_000_000L),
    MEDIUM("medium", 1920, 8, 10_000_000L),
    HIGH("high", 2560, 6, 24_000_000L);

    private static final long MIN_BIT_RATE = 800_000L;

    private final String wireName;
    private final int maxDimension;
    /** Pixels per second that one bit per second pays for; smaller means more bits per pixel. */
    private final int pixelsPerBit;
    private final long maxBitRate;

    ScreenStreamQuality(String wireName, int maxDimension, int pixelsPerBit, long maxBitRate) {
        this.wireName = wireName;
        this.maxDimension = maxDimension;
        this.pixelsPerBit = pixelsPerBit;
        this.maxBitRate = maxBitRate;
    }

    /** The name this level goes by in the protocol and in the configuration. */
    public String wireName() {
        return wireName;
    }

    /** The longer side of the picture is scaled down to at most this. */
    public int maxDimension() {
        return maxDimension;
    }

    /**
     * Screen content is mostly flat areas and text, so it needs far less than camera footage - but
     * what it does need goes up faster than the pixel count once the text is small enough to read,
     * which is why the better levels are also given more bits per pixel.
     */
    public long bitRateFor(int width, int height, int frameRate) {
        long bitRate = (long) width * height * frameRate / pixelsPerBit;
        return Math.max(MIN_BIT_RATE, Math.min(maxBitRate, bitRate));
    }

    public ScreenStreamQuality lower() {
        return ordinal() == 0 ? this : values()[ordinal() - 1];
    }

    public ScreenStreamQuality higher() {
        return ordinal() == values().length - 1 ? this : values()[ordinal() + 1];
    }

    public static ScreenStreamQuality min(ScreenStreamQuality a, ScreenStreamQuality b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    /** @return the level of that name, or null for anything else - including {@code "auto"} */
    public static @Nullable ScreenStreamQuality fromWireName(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (ScreenStreamQuality quality : values()) {
            if (quality.wireName.equalsIgnoreCase(name.trim())) {
                return quality;
            }
        }
        return null;
    }
}
