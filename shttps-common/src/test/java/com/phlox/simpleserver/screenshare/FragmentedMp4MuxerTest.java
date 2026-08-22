package com.phlox.simpleserver.screenshare;

import static com.phlox.simpleserver.screenshare.Mp4BoxReader.baseMediaDecodeTime;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.findBox;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.parseBoxes;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.readU16;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.readU24;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.readU32;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.sampleDuration;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.sampleFlags;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.sequenceNumber;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Structural checks for the hand written fMP4 muxer: every box size has to describe the bytes that
 * actually follow it, the box tree has to contain what MSE looks for, and the sample offsets in a
 * fragment have to point at the real sample data.
 *
 * @see ScreenStreamPipelineTest for the same muxer driven by a recorded H.264 bitstream
 */
public class FragmentedMp4MuxerTest {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    //nal_unit_type 7, profile_idc 0x42 (baseline), constraints 0xC0, level_idc 0x1F (3.1)
    private static final byte[] SPS = {0x67, 0x42, (byte) 0xC0, 0x1F, 0x11, 0x22, 0x33, 0x44};
    //nal_unit_type 8
    private static final byte[] PPS = {0x68, (byte) 0xCE, 0x3C, (byte) 0x80};

    @Test
    public void initSegmentIsStructurallyValid() {
        FragmentedMp4Muxer muxer = new FragmentedMp4Muxer();
        byte[] init = muxer.createInitSegment(SPS, PPS, WIDTH, HEIGHT);

        List<Mp4BoxReader.Box> top = parseBoxes(init);
        assertEquals(2, top.size(), "init segment must be exactly ftyp + moov");
        assertEquals("ftyp", top.get(0).type);
        assertEquals("moov", top.get(1).type);

        //everything MSE needs to set up a video SourceBuffer
        assertNotNull(findBox(init, "moov/mvhd"));
        assertNotNull(findBox(init, "moov/trak/tkhd"));
        assertNotNull(findBox(init, "moov/trak/mdia/mdhd"));
        assertNotNull(findBox(init, "moov/trak/mdia/hdlr"));
        assertNotNull(findBox(init, "moov/trak/mdia/minf/vmhd"));
        assertNotNull(findBox(init, "moov/trak/mdia/minf/dinf/dref"));
        assertNotNull(findBox(init, "moov/trak/mdia/minf/stbl/stsd/avc1"));
        //without mvex a player treats the file as non fragmented and plays nothing
        assertNotNull(findBox(init, "moov/mvex/trex"));

        //the sample tables must be empty - all timing comes from the fragments
        assertEquals(0, readU32(init, findBox(init, "moov/trak/mdia/minf/stbl/stts").payload + 4));
        assertEquals(0, readU32(init, findBox(init, "moov/trak/mdia/minf/stbl/stsc").payload + 4));
        assertEquals(0, readU32(init, findBox(init, "moov/trak/mdia/minf/stbl/stsz").payload + 8));
        assertEquals(0, readU32(init, findBox(init, "moov/trak/mdia/minf/stbl/stco").payload + 4));
    }

    @Test
    public void avcConfigurationRecordMatchesParameterSets() {
        FragmentedMp4Muxer muxer = new FragmentedMp4Muxer();
        byte[] init = muxer.createInitSegment(SPS, PPS, WIDTH, HEIGHT);

        Mp4BoxReader.Box avc1 = findBox(init, "moov/trak/mdia/minf/stbl/stsd/avc1");
        assertNotNull(avc1);
        //VisualSampleEntry carries the display size the browser will size the <video> to
        assertEquals(WIDTH, readU16(init, avc1.payload + 24));
        assertEquals(HEIGHT, readU16(init, avc1.payload + 26));

        Mp4BoxReader.Box avcC = findBox(init, "moov/trak/mdia/minf/stbl/stsd/avc1/avcC");
        assertNotNull(avcC);
        int p = avcC.payload;
        assertEquals(1, init[p] & 0xFF);//configuration version
        assertEquals(SPS[1] & 0xFF, init[p + 1] & 0xFF);//profile
        assertEquals(SPS[2] & 0xFF, init[p + 2] & 0xFF);//profile compatibility
        assertEquals(SPS[3] & 0xFF, init[p + 3] & 0xFF);//level
        //lengthSizeMinusOne = 3, i.e. the 4 byte NAL length prefixes the muxer writes
        assertEquals(3, init[p + 4] & 0x03);
        assertEquals(1, init[p + 5] & 0x1F);//one SPS
        assertEquals(SPS.length, readU16(init, p + 6));
        assertArrayEquals(SPS, Arrays.copyOfRange(init, p + 8, p + 8 + SPS.length));
        int ppsAt = p + 8 + SPS.length;
        assertEquals(1, init[ppsAt] & 0xFF);//one PPS
        assertEquals(PPS.length, readU16(init, ppsAt + 1));
        assertArrayEquals(PPS, Arrays.copyOfRange(init, ppsAt + 3, ppsAt + 3 + PPS.length));

        assertEquals("avc1.42c01f", muxer.getCodecString());
    }

    @Test
    public void firstFrameIsHeldBackUntilItsDurationIsKnown() {
        FragmentedMp4Muxer muxer = new FragmentedMp4Muxer();
        muxer.createInitSegment(SPS, PPS, WIDTH, HEIGHT);

        assertNull(muxer.addFrame(frame(1, 100), 1_000_000L, true), "nothing can be emitted before the duration of frame 1 is known");

        FragmentedMp4Muxer.Segment first = muxer.addFrame(frame(2, 100), 1_040_000L, false);
        assertNotNull(first);
        assertTrue(first.keyFrame, "the emitted segment is frame 1, which was the key frame");
        assertEquals(1_000_000L, first.presentationTimeUs, "...and carries frame 1's timestamp, not frame 2's");
        assertEquals(40_000, sampleDuration(first.data), "its duration is the gap to frame 2");

        //the media timeline starts at zero even though the first frame came in at 1s
        assertEquals(0, baseMediaDecodeTime(first.data));
    }

    @Test
    public void mediaSegmentPointsAtItsSampleData() {
        FragmentedMp4Muxer muxer = new FragmentedMp4Muxer();
        muxer.createInitSegment(SPS, PPS, WIDTH, HEIGHT);

        byte[] frame1 = frame(1, 700);
        byte[] frame2 = frame(2, 300);
        assertNull(muxer.addFrame(frame1, 0L, true));
        FragmentedMp4Muxer.Segment segment = muxer.addFrame(frame2, 33_000L, false);
        assertNotNull(segment);

        byte[] data = segment.data;
        List<Mp4BoxReader.Box> top = parseBoxes(data);
        assertEquals(2, top.size(), "a media segment is exactly moof + mdat");
        assertEquals("moof", top.get(0).type);
        assertEquals("mdat", top.get(1).type);

        Mp4BoxReader.Box trun = findBox(data, "moof/traf/trun");
        assertNotNull(trun);
        //data-offset | sample-duration | sample-size | sample-flags
        assertEquals(0x000701, readU24(data, trun.payload + 1));
        assertEquals(1, readU32(data, trun.payload + 4));//sample count

        //tfhd sets default-base-is-moof, so the offset is measured from the first byte of moof
        Mp4BoxReader.Box tfhd = findBox(data, "moof/traf/tfhd");
        assertNotNull(tfhd);
        assertEquals(0x020000, readU24(data, tfhd.payload + 1));

        int dataOffset = readU32(data, trun.payload + 8);
        int mdatPayload = top.get(1).payload;
        assertEquals(mdatPayload, top.get(0).start + dataOffset, "trun data offset must land on the first byte of the mdat payload");

        assertEquals(frame1.length, Mp4BoxReader.sampleSize(data));
        //0x02000000: sample_depends_on = 2 (I frame), is_non_sync_sample = 0
        assertEquals(0x02000000, sampleFlags(data));
        assertArrayEquals(frame1, Arrays.copyOfRange(data, mdatPayload, mdatPayload + frame1.length), "mdat must contain exactly the sample");
    }

    @Test
    public void decodeTimeAndSequenceNumberAdvanceContiguously() {
        FragmentedMp4Muxer muxer = new FragmentedMp4Muxer();
        muxer.createInitSegment(SPS, PPS, WIDTH, HEIGHT);

        long[] timestamps = {0L, 30_000L, 90_000L, 120_000L, 160_000L};
        List<FragmentedMp4Muxer.Segment> segments = new ArrayList<>();
        for (int i = 0; i < timestamps.length; i++) {
            FragmentedMp4Muxer.Segment segment = muxer.addFrame(frame(i, 64), timestamps[i], i == 0);
            if (segment != null) {
                segments.add(segment);
            }
        }
        assertEquals(timestamps.length - 1, segments.size());

        long expectedDecodeTime = 0;
        for (int i = 0; i < segments.size(); i++) {
            byte[] data = segments.get(i).data;
            assertEquals(i + 1, sequenceNumber(data), "fragment sequence numbers must increase by one");

            Mp4BoxReader.Box tfdt = findBox(data, "moof/traf/tfdt");
            assertNotNull(tfdt);
            assertEquals(1, data[tfdt.payload] & 0xFF, "tfdt must use version 1 so the 64 bit decode time fits");
            assertEquals(expectedDecodeTime, baseMediaDecodeTime(data), "a gap in the timeline would stall MSE playback");

            long duration = sampleDuration(data);
            assertEquals(timestamps[i + 1] - timestamps[i], duration, "sample duration must be the real gap to the next frame");
            expectedDecodeTime += duration;

            //only the very first frame was flagged as a key frame
            assertEquals(i == 0 ? 0x02000000 : 0x01010000, sampleFlags(data));
        }
    }

    @Test
    public void nonMonotonicTimestampsDoNotProduceInvalidDurations() {
        FragmentedMp4Muxer muxer = new FragmentedMp4Muxer();
        muxer.createInitSegment(SPS, PPS, WIDTH, HEIGHT);

        muxer.addFrame(frame(1, 32), 100_000L, true);
        assertEquals(20_000, sampleDuration(muxer.addFrame(frame(2, 32), 120_000L, false).data));
        //same timestamp twice: the previous duration is reused rather than writing a zero
        assertEquals(20_000, sampleDuration(muxer.addFrame(frame(3, 32), 120_000L, false).data));
        //and a timestamp going backwards must not produce a negative duration either
        assertEquals(20_000, sampleDuration(muxer.addFrame(frame(4, 32), 110_000L, false).data));
    }

    private static byte[] frame(int seed, int size) {
        byte[] frame = new byte[size];
        for (int i = 0; i < size; i++) {
            frame[i] = (byte) (seed * 31 + i);
        }
        return frame;
    }
}
