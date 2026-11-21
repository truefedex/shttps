package com.phlox.simpleserver.screenshare;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Minimal fragmented MP4 (fMP4) muxer for a single H.264 video track, written from scratch because
 * {@code android.media.MediaMuxer} can only write to a seekable file and gives no control over
 * fragment boundaries - both of which we need in order to push segments to a browser over a
 * WebSocket.
 * <p>
 * The output is what Media Source Extensions expects:
 * <ul>
 *     <li>one initialization segment - {@code ftyp} + {@code moov} (with {@code mvex}, so the
 *     {@code stbl} tables stay empty and all timing lives in the fragments)</li>
 *     <li>one media segment per frame - {@code moof} + {@code mdat}</li>
 * </ul>
 * One frame per fragment costs about 120 bytes of box overhead per frame but gives the lowest
 * possible latency, which is what a live screen view needs.
 * <p>
 * Sample data must be in AVCC form (4-byte big endian length prefix per NAL unit), matching the
 * {@code lengthSizeMinusOne = 3} written into the {@code avcC} record.
 * <p>
 * <b>Frame timing.</b> A fragment has to declare the duration of its sample, which is only known
 * once the following frame arrives. So the muxer holds one frame back: {@link #addFrame} returns
 * the segment of the <i>previous</i> frame. The capture pipeline is expected to keep frames
 * coming while the screen is still - by repeating the last frame after a short idle period, or by
 * flushing the held one on a timer - which bounds the resulting latency and keeps the media
 * timeline free of gaps. MSE stalls on gaps in the buffered range.
 * <p>
 * Not thread safe: all calls come from the encoder's output thread.
 */
public class FragmentedMp4Muxer {
    /** Microseconds, so sample durations taken from MediaCodec timestamps need no rounding. */
    private static final long TIMESCALE = 1_000_000L;
    private static final int TRACK_ID = 1;
    private static final long FALLBACK_SAMPLE_DURATION_US = 33_333L;//~30 fps

    /** {@code trun} sample_flags for an IDR frame: sample_depends_on = 2, is_non_sync = 0. */
    private static final int SAMPLE_FLAGS_KEY_FRAME = 0x02000000;
    /** ...and for a predicted frame: sample_depends_on = 1, is_non_sync = 1. */
    private static final int SAMPLE_FLAGS_NON_KEY_FRAME = 0x01010000;

    public static final class Segment {
        public final byte[] data;
        public final boolean keyFrame;
        public final long presentationTimeUs;

        public Segment(byte[] data, boolean keyFrame, long presentationTimeUs) {
            this.data = data;
            this.keyFrame = keyFrame;
            this.presentationTimeUs = presentationTimeUs;
        }
    }

    private int sequenceNumber = 1;
    private long baseMediaDecodeTime = 0;
    private long lastSampleDurationUs = FALLBACK_SAMPLE_DURATION_US;

    private byte[] pendingFrame;
    private long pendingPresentationTimeUs;
    private boolean pendingKeyFrame;

    private String codecString;

    /**
     * Builds the initialization segment for the given parameter sets.
     *
     * @param sps raw SPS NAL unit, without the Annex B start code
     * @param pps raw PPS NAL unit, without the Annex B start code
     */
    public byte[] createInitSegment(byte[] sps, byte[] pps, int width, int height) {
        if (sps == null || sps.length < 4 || pps == null || pps.length == 0) {
            throw new IllegalArgumentException("Invalid H.264 parameter sets");
        }
        codecString = String.format(Locale.US, "avc1.%02x%02x%02x", sps[1], sps[2], sps[3]);

        BoxWriter w = new BoxWriter(1024);
        writeFileTypeBox(w);
        writeMovieBox(w, sps, pps, width, height);
        return w.toByteArray();
    }

    /**
     * @return the MSE codec string of the stream, or null before the init segment was built
     */
    public String getCodecString() {
        return codecString;
    }

    /**
     * Queues a frame and returns the media segment of the previously queued one, or null if this
     * was the first frame since the muxer was created.
     *
     * @param avccFrame AVCC formatted access unit
     */
    public synchronized Segment addFrame(byte[] avccFrame, long presentationTimeUs, boolean keyFrame) {
        Segment ready = null;
        if (pendingFrame != null) {
            long durationUs = presentationTimeUs - pendingPresentationTimeUs;
            if (durationUs <= 0) {
                //timestamps should be monotonic for a surface encoder without B-frames, but do not
                //let a misbehaving one produce a zero-length or backwards sample
                durationUs = lastSampleDurationUs;
            }
            lastSampleDurationUs = durationUs;
            ready = buildMediaSegment(pendingFrame, durationUs, pendingKeyFrame,
                    pendingPresentationTimeUs);
        }
        pendingFrame = avccFrame;
        pendingPresentationTimeUs = presentationTimeUs;
        pendingKeyFrame = keyFrame;
        return ready;
    }

    /**
     * Emits the held frame without waiting for the one that would tell its duration, assuming the
     * duration of the previous sample.
     * <p>
     * A screen only produces frames when it changes, and the encoder's "repeat the last frame"
     * setting is not honoured by every device. When frames stop arriving, the newest one would
     * otherwise stay here for as long as the screen stays still - which is exactly when the viewer
     * has nothing else to look at. On a device that produces a single frame after the encoder
     * starts, that frame is the whole picture, and holding it back leaves the viewer with a black
     * screen until something moves.
     *
     * @return the segment of the held frame, or null if none is held
     */
    public synchronized Segment flushPendingFrame() {
        if (pendingFrame == null) {
            return null;
        }
        Segment segment = buildMediaSegment(pendingFrame, lastSampleDurationUs, pendingKeyFrame,
                pendingPresentationTimeUs);
        pendingFrame = null;
        return segment;
    }

    // --- initialization segment ---------------------------------------------------------------

    private void writeFileTypeBox(BoxWriter w) {
        int box = w.startBox("ftyp");
        w.fourCC("iso5");//major brand
        w.u32(512);//minor version
        w.fourCC("iso5");
        w.fourCC("iso6");
        w.fourCC("avc1");
        w.fourCC("mp41");
        w.fourCC("dash");
        w.endBox(box);
    }

    private void writeMovieBox(BoxWriter w, byte[] sps, byte[] pps, int width, int height) {
        int moov = w.startBox("moov");

        int mvhd = w.startFullBox("mvhd", 0, 0);
        w.u32(0);//creation time
        w.u32(0);//modification time
        w.u32(TIMESCALE);
        w.u32(0);//duration - unknown for a live stream
        w.u32(0x00010000);//rate 1.0
        w.u16(0x0100);//volume 1.0
        w.u16(0);//reserved
        w.u32(0);//reserved
        w.u32(0);//reserved
        writeUnityMatrix(w);
        for (int i = 0; i < 6; i++) {
            w.u32(0);//pre_defined
        }
        w.u32(TRACK_ID + 1);//next track id
        w.endBox(mvhd);

        int trak = w.startBox("trak");

        int tkhd = w.startFullBox("tkhd", 0, 0x000003);//track enabled | track in movie
        w.u32(0);//creation time
        w.u32(0);//modification time
        w.u32(TRACK_ID);
        w.u32(0);//reserved
        w.u32(0);//duration
        w.u32(0);//reserved
        w.u32(0);//reserved
        w.u16(0);//layer
        w.u16(0);//alternate group
        w.u16(0);//volume - video track
        w.u16(0);//reserved
        writeUnityMatrix(w);
        w.u32((long) width << 16);//16.16 fixed point
        w.u32((long) height << 16);
        w.endBox(tkhd);

        int mdia = w.startBox("mdia");

        int mdhd = w.startFullBox("mdhd", 0, 0);
        w.u32(0);//creation time
        w.u32(0);//modification time
        w.u32(TIMESCALE);
        w.u32(0);//duration
        w.u16(0x55C4);//language: "und"
        w.u16(0);//pre_defined
        w.endBox(mdhd);

        int hdlr = w.startFullBox("hdlr", 0, 0);
        w.u32(0);//pre_defined
        w.fourCC("vide");
        w.u32(0);//reserved
        w.u32(0);//reserved
        w.u32(0);//reserved
        w.asciiZ("VideoHandler");
        w.endBox(hdlr);

        int minf = w.startBox("minf");

        int vmhd = w.startFullBox("vmhd", 0, 1);
        w.u16(0);//graphics mode
        w.u16(0);//opcolor r
        w.u16(0);//opcolor g
        w.u16(0);//opcolor b
        w.endBox(vmhd);

        int dinf = w.startBox("dinf");
        int dref = w.startFullBox("dref", 0, 0);
        w.u32(1);//entry count
        int url = w.startFullBox("url ", 0, 1);//self contained
        w.endBox(url);
        w.endBox(dref);
        w.endBox(dinf);

        int stbl = w.startBox("stbl");

        int stsd = w.startFullBox("stsd", 0, 0);
        w.u32(1);//entry count
        writeAvcSampleEntry(w, sps, pps, width, height);
        w.endBox(stsd);

        //all timing lives in the fragments, so the sample tables stay empty
        int stts = w.startFullBox("stts", 0, 0);
        w.u32(0);
        w.endBox(stts);
        int stsc = w.startFullBox("stsc", 0, 0);
        w.u32(0);
        w.endBox(stsc);
        int stsz = w.startFullBox("stsz", 0, 0);
        w.u32(0);//sample size
        w.u32(0);//sample count
        w.endBox(stsz);
        int stco = w.startFullBox("stco", 0, 0);
        w.u32(0);
        w.endBox(stco);

        w.endBox(stbl);
        w.endBox(minf);
        w.endBox(mdia);
        w.endBox(trak);

        int mvex = w.startBox("mvex");
        int trex = w.startFullBox("trex", 0, 0);
        w.u32(TRACK_ID);
        w.u32(1);//default sample description index
        w.u32(0);//default sample duration
        w.u32(0);//default sample size
        w.u32(0);//default sample flags
        w.endBox(trex);
        w.endBox(mvex);

        w.endBox(moov);
    }

    private void writeAvcSampleEntry(BoxWriter w, byte[] sps, byte[] pps, int width, int height) {
        int avc1 = w.startBox("avc1");
        for (int i = 0; i < 6; i++) {
            w.u8(0);//reserved
        }
        w.u16(1);//data reference index
        w.u16(0);//pre_defined
        w.u16(0);//reserved
        w.u32(0);//pre_defined
        w.u32(0);//pre_defined
        w.u32(0);//pre_defined
        w.u16(width);
        w.u16(height);
        w.u32(0x00480000);//horizontal resolution 72 dpi
        w.u32(0x00480000);//vertical resolution 72 dpi
        w.u32(0);//reserved
        w.u16(1);//frame count
        for (int i = 0; i < 32; i++) {
            w.u8(0);//compressor name
        }
        w.u16(0x0018);//depth
        w.u16(0xFFFF);//pre_defined = -1

        int avcC = w.startBox("avcC");
        w.u8(1);//configuration version
        w.u8(sps[1]);//profile
        w.u8(sps[2]);//profile compatibility
        w.u8(sps[3]);//level
        w.u8(0xFF);//6 bits reserved + lengthSizeMinusOne = 3 (4 byte NAL length prefixes)
        w.u8(0xE1);//3 bits reserved + one SPS
        w.u16(sps.length);
        w.bytes(sps);
        w.u8(1);//one PPS
        w.u16(pps.length);
        w.bytes(pps);
        w.endBox(avcC);

        w.endBox(avc1);
    }

    private static void writeUnityMatrix(BoxWriter w) {
        w.u32(0x00010000);
        w.u32(0);
        w.u32(0);
        w.u32(0);
        w.u32(0x00010000);
        w.u32(0);
        w.u32(0);
        w.u32(0);
        w.u32(0x40000000);
    }

    // --- media segments ------------------------------------------------------------------------

    private Segment buildMediaSegment(byte[] frame, long durationUs, boolean keyFrame,
                                      long presentationTimeUs) {
        BoxWriter w = new BoxWriter(frame.length + 128);

        int moof = w.startBox("moof");

        int mfhd = w.startFullBox("mfhd", 0, 0);
        w.u32(sequenceNumber++);
        w.endBox(mfhd);

        int traf = w.startBox("traf");

        //default-base-is-moof: sample data offsets are relative to the start of this moof box
        int tfhd = w.startFullBox("tfhd", 0, 0x020000);
        w.u32(TRACK_ID);
        w.endBox(tfhd);

        int tfdt = w.startFullBox("tfdt", 1, 0);
        w.u64(baseMediaDecodeTime);
        w.endBox(tfdt);

        //data-offset | sample-duration | sample-size | sample-flags
        int trun = w.startFullBox("trun", 0, 0x000701);
        w.u32(1);//sample count
        int dataOffsetPosition = w.size();
        w.u32(0);//data offset, patched below once the moof size is known
        w.u32(durationUs);
        w.u32(frame.length);
        w.u32(keyFrame ? SAMPLE_FLAGS_KEY_FRAME : SAMPLE_FLAGS_NON_KEY_FRAME);
        w.endBox(trun);

        w.endBox(traf);
        w.endBox(moof);

        int moofSize = w.size() - moof;
        //the sample data starts right after the mdat box header
        w.patchU32(dataOffsetPosition, moofSize + 8);

        int mdat = w.startBox("mdat");
        w.bytes(frame);
        w.endBox(mdat);

        baseMediaDecodeTime += durationUs;
        return new Segment(w.toByteArray(), keyFrame, presentationTimeUs);
    }

    // --- byte writing --------------------------------------------------------------------------

    /**
     * Growable big endian byte sink with box size back-patching. Extends
     * {@link ByteArrayOutputStream} only to reuse its growth logic and its protected buffer, which
     * we need in order to patch already written box sizes in place.
     */
    private static final class BoxWriter extends ByteArrayOutputStream {
        BoxWriter(int initialCapacity) {
            super(initialCapacity);
        }

        void u8(int value) {
            write(value & 0xFF);
        }

        void u16(int value) {
            write((value >> 8) & 0xFF);
            write(value & 0xFF);
        }

        void u32(long value) {
            write((int) ((value >> 24) & 0xFF));
            write((int) ((value >> 16) & 0xFF));
            write((int) ((value >> 8) & 0xFF));
            write((int) (value & 0xFF));
        }

        void u64(long value) {
            u32((value >>> 32) & 0xFFFFFFFFL);
            u32(value & 0xFFFFFFFFL);
        }

        void bytes(byte[] value) {
            write(value, 0, value.length);
        }

        void fourCC(String type) {
            for (int i = 0; i < 4; i++) {
                write(type.charAt(i));
            }
        }

        /** Null terminated ASCII string, as used by the hdlr box name field. */
        void asciiZ(String value) {
            for (int i = 0; i < value.length(); i++) {
                write(value.charAt(i));
            }
            write(0);
        }

        int startBox(String type) {
            int position = count;
            u32(0);//size placeholder
            fourCC(type);
            return position;
        }

        int startFullBox(String type, int version, int flags) {
            int position = startBox(type);
            u8(version);
            u8(flags >> 16);
            u8(flags >> 8);
            u8(flags);
            return position;
        }

        void endBox(int boxPosition) {
            patchU32(boxPosition, count - boxPosition);
        }

        void patchU32(int position, long value) {
            buf[position] = (byte) (value >> 24);
            buf[position + 1] = (byte) (value >> 16);
            buf[position + 2] = (byte) (value >> 8);
            buf[position + 3] = (byte) value;
        }
    }
}
