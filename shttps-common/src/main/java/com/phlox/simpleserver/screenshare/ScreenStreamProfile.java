package com.phlox.simpleserver.screenshare;

/**
 * What a capture pipeline is asked to produce: a quality level, the size the picture is actually
 * worth encoding at, and whether the mouse pointer is part of it.
 * <p>
 * Level and size differ because of who is watching. A level says how large the picture <i>may</i>
 * be; a viewer whose window is 1300 pixels wide gains nothing from 2560 of them, only a bigger bill
 * for the encoder and the link. So the size here is the level's limit brought down to what the
 * largest viewer can show.
 * <p>
 * The pointer is in the picture unless every viewer can draw it for itself from the positions
 * sent alongside the stream, see {@link ScreenStreamListener#onCursor}. A pointer that is part of
 * the video is as late as the video; one the browser draws is not late at all for whoever is moving
 * it. But a viewer that does not know how would be left with no pointer whatsoever, so one such
 * viewer is enough to keep it in the picture for everybody.
 * <p>
 * Immutable, and compared by value: a pipeline restarts only when this actually changes.
 */
public final class ScreenStreamProfile {
    /** What a source produces until someone asks for something else. */
    public static final ScreenStreamProfile DEFAULT = new ScreenStreamProfile(
            ScreenStreamQuality.LOW, ScreenStreamQuality.LOW.maxDimension());

    public final ScreenStreamQuality quality;
    /** The longer side of the encoded picture is at most this many pixels. */
    public final int maxDimension;
    /** Whether the pipeline draws the mouse pointer into the frames. */
    public final boolean cursorInPicture;

    public ScreenStreamProfile(ScreenStreamQuality quality, int maxDimension) {
        this(quality, maxDimension, true);
    }

    public ScreenStreamProfile(ScreenStreamQuality quality, int maxDimension, boolean cursorInPicture) {
        this.quality = quality;
        this.maxDimension = Math.max(2, Math.min(maxDimension, quality.maxDimension()));
        this.cursorInPicture = cursorInPicture;
    }

    public long bitRateFor(int width, int height, int frameRate) {
        return quality.bitRateFor(width, height, frameRate);
    }

    /** @return this profile with the pointer drawn into the picture, whatever it said before */
    public ScreenStreamProfile withCursorInPicture() {
        return cursorInPicture ? this : new ScreenStreamProfile(quality, maxDimension, true);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScreenStreamProfile)) {
            return false;
        }
        ScreenStreamProfile that = (ScreenStreamProfile) other;
        return quality == that.quality && maxDimension == that.maxDimension
                && cursorInPicture == that.cursorInPicture;
    }

    @Override
    public int hashCode() {
        return (quality.hashCode() * 31 + maxDimension) * 31 + (cursorInPicture ? 1 : 0);
    }

    @Override
    public String toString() {
        return quality.wireName() + "@" + maxDimension + (cursorInPicture ? "" : " without pointer");
    }
}
