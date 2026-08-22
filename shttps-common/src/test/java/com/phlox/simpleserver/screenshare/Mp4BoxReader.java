package com.phlox.simpleserver.screenshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal MP4 box walker for the tests. Parsing a level asserts that the child box sizes tile
 * their parent exactly, which is what catches a mistake in the muxer's size back-patching.
 */
final class Mp4BoxReader {
    static final class Box {
        final String type;
        /** offset of the box header */
        final int start;
        /** offset of the first byte after the 8 byte header */
        final int payload;
        final int end;

        Box(String type, int start, int payload, int end) {
            this.type = type;
            this.start = start;
            this.payload = payload;
            this.end = end;
        }
    }

    private static final Set<String> CONTAINERS = new HashSet<>(Arrays.asList(
            "moov", "trak", "mdia", "minf", "dinf", "stbl", "mvex", "moof", "traf"));

    /** Boxes whose children start after a fixed amount of payload, or -1 if it has no children. */
    private static int childrenOffset(String type) {
        switch (type) {
            case "stsd":
                return 8;//version + flags + entry_count
            case "avc1":
                return 78;//VisualSampleEntry header
            default:
                return CONTAINERS.contains(type) ? 0 : -1;
        }
    }

    static List<Box> parseBoxes(byte[] data, int from, int to) {
        List<Box> boxes = new ArrayList<>();
        int at = from;
        while (at < to) {
            assertTrue(at + 8 <= to, "truncated box header at " + at);
            int size = readU32(data, at);
            String type = new String(data, at + 4, 4, StandardCharsets.US_ASCII);
            assertTrue(size >= 8, "box " + type + " has an impossible size " + size);
            assertTrue(at + size <= to, "box " + type + " at " + at + " overruns its parent by "
                    + (at + size - to));
            boxes.add(new Box(type, at, at + 8, at + size));
            at += size;
        }
        assertEquals(to, at, "boxes must fill their parent exactly");
        return boxes;
    }

    static List<Box> parseBoxes(byte[] data) {
        return parseBoxes(data, 0, data.length);
    }

    /**
     * @param path slash separated box types, e.g. {@code moov/trak/mdia}
     * @return the box, or null if the path does not exist
     */
    static Box findBox(byte[] data, String path) {
        List<Box> level = parseBoxes(data);
        Box found = null;
        for (String type : path.split("/")) {
            found = null;
            for (Box box : level) {
                if (box.type.equals(type)) {
                    found = box;
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            int offset = childrenOffset(found.type);
            level = offset < 0 ? Collections.emptyList()
                    : parseBoxes(data, found.payload + offset, found.end);
        }
        return found;
    }

    static int readU16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    static int readU24(byte[] data, int at) {
        return ((data[at] & 0xFF) << 16) | ((data[at + 1] & 0xFF) << 8) | (data[at + 2] & 0xFF);
    }

    static int readU32(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    static long readU64(byte[] data, int at) {
        return ((long) readU32(data, at) << 32) | (readU32(data, at + 4) & 0xFFFFFFFFL);
    }

    // --- fragment fields the tests care about ----------------------------------------------

    static int sequenceNumber(byte[] segment) {
        return readU32(segment, findBox(segment, "moof/mfhd").payload + 4);
    }

    static long baseMediaDecodeTime(byte[] segment) {
        return readU64(segment, findBox(segment, "moof/traf/tfdt").payload + 4);
    }

    static long sampleDuration(byte[] segment) {
        return readU32(segment, findBox(segment, "moof/traf/trun").payload + 12);
    }

    static int sampleSize(byte[] segment) {
        return readU32(segment, findBox(segment, "moof/traf/trun").payload + 16);
    }

    static int sampleFlags(byte[] segment) {
        return readU32(segment, findBox(segment, "moof/traf/trun").payload + 20);
    }

    private Mp4BoxReader() {}
}
