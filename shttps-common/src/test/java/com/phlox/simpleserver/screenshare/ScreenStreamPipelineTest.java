package com.phlox.simpleserver.screenshare;

import static com.phlox.simpleserver.screenshare.Mp4BoxReader.baseMediaDecodeTime;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.findBox;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.parseBoxes;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.readU32;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.sampleDuration;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.sampleFlags;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.sampleSize;
import static com.phlox.simpleserver.screenshare.Mp4BoxReader.sequenceNumber;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the whole encoder-side pipeline - Annex B splitting, AVCC conversion and fMP4 muxing -
 * with a real H.264 bitstream instead of synthetic bytes.
 * <p>
 * The fixture is 40 frames of 320x240 Constrained Baseline video (two key frames, at 0 and 20)
 * produced by a browser's WebCodecs encoder configured for {@code annexb} output, which is the
 * same shape Android's {@code MediaCodec} hands to its encoder. Everything the tests
 * below assert was cross checked against ffmpeg, which demuxes and decodes the muxer's output
 * without a warning, and against Chrome, which plays it through Media Source Extensions.
 * <p>
 * Fixture layout: {@code u32 frameCount} then per frame
 * {@code u64 presentationTimeUs, u8 isKeyFrame, u32 length, length bytes of Annex B}.
 */
public class ScreenStreamPipelineTest {
    private static final String FIXTURE = "/screenshare/h264-annexb-capture.bin";
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    /** What the fixture's SPS says: Constrained Baseline (0x42 / 0x40) at level 3.0 (0x1e). */
    private static final String EXPECTED_CODEC = "avc1.42401e";

    private static final int NAL_TYPE_IDR = 5;
    private static final int NAL_TYPE_SPS = 7;
    private static final int NAL_TYPE_PPS = 8;
    private static final int NAL_TYPE_AUD = 9;

    private List<Frame> frames;

    private static final class Frame {
        long presentationTimeUs;
        boolean keyFrame;
        byte[] annexB;
    }

    @BeforeEach
    public void loadFixture() throws IOException {
        ByteBuffer in = ByteBuffer.wrap(readResource(FIXTURE));
        int count = in.getInt();
        frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Frame frame = new Frame();
            frame.presentationTimeUs = in.getLong();
            frame.keyFrame = in.get() != 0;
            frame.annexB = new byte[in.getInt()];
            in.get(frame.annexB);
            frames.add(frame);
        }
        assertEquals(40, frames.size(), "fixture should hold 40 frames");
    }

    @Test
    public void parameterSetsAreRecoveredFromTheBitstream() {
        List<byte[]> nalUnits = H264Bitstream.splitAnnexB(ByteBuffer.wrap(frames.get(0).annexB));

        //a real encoder puts an access unit delimiter in front, so the parameter sets have to be
        //located by NAL type rather than by position - which is what the encoders do
        int spsAt = indexOfNalType(nalUnits, NAL_TYPE_SPS);
        int ppsAt = indexOfNalType(nalUnits, NAL_TYPE_PPS);
        assertTrue(spsAt >= 0, "the opening access unit must carry an SPS");
        assertTrue(ppsAt >= 0, "the opening access unit must carry a PPS");
        assertTrue(spsAt < ppsAt, "SPS comes before PPS");
        assertEquals(NAL_TYPE_AUD, nalUnits.get(0)[0] & 0x1F, "this fixture starts with an access unit delimiter");
        assertEquals(NAL_TYPE_IDR, nalUnits.get(nalUnits.size() - 1)[0] & 0x1F, "the slice is last");

        for (byte[] nal : nalUnits) {
            assertTrue(nal.length > 0, "a NAL unit is never empty");
            assertTrue(!(nal.length >= 3 && nal[0] == 0 && nal[1] == 0 && nal[2] == 1), "the start code must be stripped, not left at the front");
            assertTrue(nal[nal.length - 1] != 0, "a NAL unit never ends with a zero byte, the rbsp stop bit prevents it");
        }

        //later frames are plain slices: no parameter sets are repeated in this stream, which is
        //exactly why the avcC record has to be built from the opening access unit
        List<byte[]> later = H264Bitstream.splitAnnexB(ByteBuffer.wrap(frames.get(1).annexB));
        assertEquals(-1, indexOfNalType(later, NAL_TYPE_SPS));
    }

    private static int indexOfNalType(List<byte[]> nalUnits, int type) {
        for (int i = 0; i < nalUnits.size(); i++) {
            if ((nalUnits.get(i)[0] & 0x1F) == type) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void annexBConvertsToAvccWithoutLosingBytes() {
        for (Frame frame : frames) {
            List<byte[]> nalUnits = H264Bitstream.splitAnnexB(ByteBuffer.wrap(frame.annexB));
            assertTrue(!nalUnits.isEmpty(), "every access unit holds at least one NAL unit");
            byte[] avcc = H264Bitstream.toAvcc(nalUnits);

            //walk the length prefixed units back out and compare them with what went in
            int at = 0;
            for (byte[] expected : nalUnits) {
                int length = readU32(avcc, at);
                assertEquals(expected.length, length, "length prefix must match the NAL unit that follows");
                byte[] actual = new byte[length];
                System.arraycopy(avcc, at + 4, actual, 0, length);
                assertArrayEquals(expected, actual);
                at += 4 + length;
            }
            assertEquals(avcc.length, at, "AVCC output must be exactly the prefixed units, nothing more");
        }
    }

    @Test
    public void initSegmentDescribesTheRealBitstream() {
        FragmentedMp4Muxer muxer = new FragmentedMp4Muxer();
        byte[] init = buildInitSegment(muxer);

        assertEquals(EXPECTED_CODEC, muxer.getCodecString());

        List<Mp4BoxReader.Box> top = parseBoxes(init);
        assertEquals(2, top.size());
        assertEquals("ftyp", top.get(0).type);
        assertEquals("moov", top.get(1).type);

        Mp4BoxReader.Box avcC = findBox(init, "moov/trak/mdia/minf/stbl/stsd/avc1/avcC");
        assertNotNull(avcC);
        //profile_idc / profile compatibility / level_idc lifted straight out of the real SPS
        assertEquals(0x42, init[avcC.payload + 1] & 0xFF);
        assertEquals(0x40, init[avcC.payload + 2] & 0xFF);
        assertEquals(0x1e, init[avcC.payload + 3] & 0xFF);
    }

    @Test
    public void everyFrameBecomesOneContiguousFragment() {
        FragmentedMp4Muxer muxer = new FragmentedMp4Muxer();
        buildInitSegment(muxer);

        List<FragmentedMp4Muxer.Segment> segments = new ArrayList<>();
        List<Frame> emitted = new ArrayList<>();
        for (Frame frame : frames) {
            byte[] avcc = H264Bitstream.toAvcc(
                    H264Bitstream.splitAnnexB(ByteBuffer.wrap(frame.annexB)));
            FragmentedMp4Muxer.Segment segment =
                    muxer.addFrame(avcc, frame.presentationTimeUs, frame.keyFrame);
            if (segment == null) {
                continue;
            }
            segments.add(segment);
            //the muxer holds one frame back, so segment N carries frame N
            emitted.add(frames.get(segments.size() - 1));
        }
        assertEquals(frames.size() - 1, segments.size(), "one fragment per frame, minus the one still held back");

        long expectedDecodeTime = 0;
        for (int i = 0; i < segments.size(); i++) {
            byte[] data = segments.get(i).data;
            Frame source = emitted.get(i);

            List<Mp4BoxReader.Box> top = parseBoxes(data);
            assertEquals(2, top.size(), "a media segment is exactly moof + mdat");
            assertEquals("moof", top.get(0).type);
            assertEquals("mdat", top.get(1).type);

            assertEquals(i + 1, sequenceNumber(data), "fragment sequence numbers must increase by one");
            assertEquals(expectedDecodeTime, baseMediaDecodeTime(data), "a hole in the timeline would stall MSE playback");

            long duration = sampleDuration(data);
            assertEquals(frames.get(i + 1).presentationTimeUs - source.presentationTimeUs, duration, "sample duration must be the gap to the next frame's timestamp");
            expectedDecodeTime += duration;

            assertEquals(source.keyFrame ? 0x02000000 : 0x01010000, sampleFlags(data), "sync samples must be flagged so a decoder can start on them");

            //the sample really is the frame, at the offset the trun promises
            int dataOffset = readU32(data, findBox(data, "moof/traf/trun").payload + 8);
            assertEquals(top.get(1).payload, top.get(0).start + dataOffset);
            byte[] expectedSample = H264Bitstream.toAvcc(
                    H264Bitstream.splitAnnexB(ByteBuffer.wrap(source.annexB)));
            assertEquals(expectedSample.length, sampleSize(data));
            assertArrayEquals(expectedSample, java.util.Arrays.copyOfRange(
                    data, top.get(1).payload, top.get(1).payload + expectedSample.length));
        }

        //the fixture is a constant 30 fps capture, so the media span has to come out exact
        assertEquals(frames.get(frames.size() - 1).presentationTimeUs
                - frames.get(0).presentationTimeUs, expectedDecodeTime);

        int keyFrames = 0;
        for (FragmentedMp4Muxer.Segment segment : segments) {
            if (segment.keyFrame) {
                keyFrames++;
            }
        }
        assertEquals(2, keyFrames, "the fixture was encoded with key frames at 0 and 20");
    }

    @Test
    public void firstFrameIsHeldUntilItsDurationIsKnown() {
        FragmentedMp4Muxer muxer = new FragmentedMp4Muxer();
        buildInitSegment(muxer);
        Frame first = frames.get(0);
        assertNull(muxer.addFrame(H264Bitstream.toAvcc(
                        H264Bitstream.splitAnnexB(ByteBuffer.wrap(first.annexB))),
                        first.presentationTimeUs, first.keyFrame), "the opening frame can not be emitted before the next one dates it");
    }

    private byte[] buildInitSegment(FragmentedMp4Muxer muxer) {
        List<byte[]> nalUnits = H264Bitstream.splitAnnexB(ByteBuffer.wrap(frames.get(0).annexB));
        byte[] sps = null;
        byte[] pps = null;
        for (byte[] nal : nalUnits) {
            int type = nal[0] & 0x1F;
            if (type == NAL_TYPE_SPS && sps == null) {
                sps = nal;
            } else if (type == NAL_TYPE_PPS && pps == null) {
                pps = nal;
            }
        }
        assertNotNull(sps, "fixture must carry an SPS");
        assertNotNull(pps, "fixture must carry a PPS");
        return muxer.createInitSegment(sps, pps, WIDTH, HEIGHT);
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream in = ScreenStreamPipelineTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "missing test fixture " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        }
    }
}
