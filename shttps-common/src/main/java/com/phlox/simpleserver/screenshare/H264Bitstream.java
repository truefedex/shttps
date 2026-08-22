package com.phlox.simpleserver.screenshare;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversion between the two ways an H.264 access unit is written down.
 * <p>
 * Encoders hand out <b>Annex B</b>: NAL units separated by {@code 00 00 01} start codes. MP4 wants
 * <b>AVCC</b>: each NAL unit prefixed with its length as a 4 byte big endian number, matching the
 * {@code lengthSizeMinusOne = 3} that {@link FragmentedMp4Muxer} writes into the {@code avcC}
 * record.
 * <p>
 * Every encoder this project uses emits Annex B - Android's {@code MediaCodec} and FFmpeg's
 * libopenh264 alike - so the conversion is the same everywhere and lives here rather than in any
 * one platform's encoder.
 */
public final class H264Bitstream {
    private H264Bitstream() {}

    /**
     * Splits an Annex B buffer into its NAL units, start codes removed. The buffer is consumed.
     */
    public static List<byte[]> splitAnnexB(ByteBuffer buffer) {
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return splitAnnexB(data);
    }

    /** Splits an Annex B byte array into its NAL units, start codes removed. */
    public static List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> nalUnits = new ArrayList<>(4);
        int startCode = nextStartCode(data, 0);
        while (startCode >= 0) {
            int nalStart = startCode + 3;
            int nextStartCode = nextStartCode(data, nalStart);
            int nalEnd = nextStartCode < 0 ? data.length : nextStartCode;
            //drop the zero byte of a four byte start code and any trailing_zero_8bits; the last
            //byte of a NAL unit is never zero, the rbsp stop bit guarantees that
            while (nalEnd > nalStart && data[nalEnd - 1] == 0) {
                nalEnd--;
            }
            if (nalEnd > nalStart) {
                byte[] nal = new byte[nalEnd - nalStart];
                System.arraycopy(data, nalStart, nal, 0, nal.length);
                nalUnits.add(nal);
            }
            startCode = nextStartCode;
        }
        return nalUnits;
    }

    /** Concatenates NAL units into one AVCC sample: 4 byte big endian length, then the unit. */
    public static byte[] toAvcc(List<byte[]> nalUnits) {
        int size = 0;
        for (byte[] nal : nalUnits) {
            size += nal.length + 4;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        for (byte[] nal : nalUnits) {
            int length = nal.length;
            out.write((length >> 24) & 0xFF);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
            out.write(nal, 0, length);
        }
        return out.toByteArray();
    }

    /** The {@code nal_unit_type} of a NAL unit with its start code already removed. */
    public static int nalType(byte[] nalUnit) {
        return nalUnit.length == 0 ? -1 : nalUnit[0] & 0x1F;
    }

    private static int nextStartCode(byte[] data, int from) {
        for (int i = from; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }
}
